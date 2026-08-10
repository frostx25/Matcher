-- Staff security console. All reads are contextual and all mutations are
-- normalized, rate-limited, role-checked and audited without sensitive data.

create table private.account_moderation_sanctions (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.accounts (id) on delete cascade,
    sanction_kind text not null check (sanction_kind in ('warning', 'suspension', 'ban')),
    reason text not null check (reason in ('spam', 'harassment', 'fake_profile', 'adult_content', 'abusive_content', 'safety', 'other')),
    active boolean not null default true,
    expires_at timestamptz,
    created_by uuid not null references auth.users (id) on delete restrict,
    created_at timestamptz not null default now(),
    ended_by uuid references auth.users (id) on delete set null,
    ended_at timestamptz,
    constraint account_sanction_expiry check (
        (sanction_kind = 'suspension' and expires_at is not null)
        or (sanction_kind <> 'suspension' and expires_at is null)
    )
);

create index account_moderation_sanctions_active_idx
    on private.account_moderation_sanctions (user_id, created_at desc)
    where active;

alter table private.account_moderation_sanctions enable row level security;
revoke all on table private.account_moderation_sanctions from public, anon, authenticated;

create or replace function private.is_active_moderation_admin(target_user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from private.moderation_staff staff
        join public.accounts account on account.id = staff.user_id
        where staff.user_id = target_user_id
          and staff.active
          and staff.staff_role = 'admin'
          and account.status = 'active'
    );
$$;

create or replace function private.check_moderation_action_rate(actor_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if (
        select count(*)
        from public.audit_events event
        where event.actor_id = check_moderation_action_rate.actor_id
          and event.event_type like 'moderation_console.%'
          and event.created_at >= clock_timestamp() - interval '1 minute'
    ) >= 60 then
        raise exception using errcode = 'P0001', message = 'MODERATION_RATE_LIMITED';
    end if;
end;
$$;

revoke all on function private.is_active_moderation_admin(uuid) from public, anon, authenticated;
revoke all on function private.check_moderation_action_rate(uuid) from public, anon, authenticated;

create function public.get_moderation_console_overview()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_actor uuid := auth.uid();
begin
    if not private.is_active_moderator(v_actor) then
        raise exception using errcode = '42501', message = 'MODERATOR_REQUIRED';
    end if;
    return jsonb_build_object(
        'role', (select staff.staff_role from private.moderation_staff staff where staff.user_id = v_actor),
        'photo_reviews', (select count(*) from private.profile_photo_submissions submission where submission.status = 'pending' and submission.automation_state = 'review'),
        'open_reports', (select count(*) from public.moderation_cases moderation_case where moderation_case.state in ('pending_review', 'in_review')),
        'reported_albums', (select count(distinct marker.moderation_case_id) from private.private_album_report_markers marker join public.moderation_cases moderation_case on moderation_case.id = marker.moderation_case_id where moderation_case.state in ('pending_review', 'in_review')),
        'suspended_accounts', (select count(*) from public.accounts account where account.status = 'suspended'),
        'active_staff', (select count(*) from private.moderation_staff staff where staff.active),
        'oldest_report_at', (select min(moderation_case.created_at) from public.moderation_cases moderation_case where moderation_case.state in ('pending_review', 'in_review')),
        'decisions_24h', (select count(*) from public.audit_events event where event.actor_id is not null and event.created_at >= now() - interval '24 hours' and (event.event_type like 'moderation_console.%' or event.event_type = 'profile_photo.review_decided'))
    );
end;
$$;

create function public.list_moderation_cases(
    page_size integer default 30,
    state_filter text default 'open'
)
returns table (
    case_id uuid,
    report_id uuid,
    reported_user_id uuid,
    display_name text,
    account_status public.account_status,
    reason public.report_reason,
    details text,
    case_state public.moderation_state,
    created_at timestamptz,
    message_id uuid,
    message_body text,
    has_album_evidence boolean,
    repeat_report_count bigint
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_actor uuid := auth.uid();
begin
    if not private.is_active_moderator(v_actor) then
        raise exception using errcode = '42501', message = 'MODERATOR_REQUIRED';
    end if;
    if page_size not between 1 and 50 or state_filter not in ('open', 'resolved', 'dismissed', 'all') then
        raise exception using errcode = 'P0001', message = 'INVALID_MODERATION_FILTER';
    end if;
    return query
    select moderation_case.id, report.id, report.reported_user_id,
           profile.display_name, account.status, report.reason, report.details,
           moderation_case.state, moderation_case.created_at, report.message_id,
           case when report.message_id is not null then left(message.body, 1000) else null end,
           exists (select 1 from private.private_album_report_markers marker where marker.moderation_case_id = moderation_case.id),
           (select count(*) from public.reports repeated where repeated.reported_user_id = report.reported_user_id)
    from public.moderation_cases moderation_case
    join public.reports report on report.id = moderation_case.report_id
    join public.accounts account on account.id = report.reported_user_id
    left join public.profiles profile on profile.id = report.reported_user_id
    left join public.messages message on message.id = report.message_id
    where state_filter = 'all'
       or (state_filter = 'open' and moderation_case.state in ('pending_review', 'in_review'))
       or moderation_case.state::text = state_filter
    order by case when moderation_case.state in ('pending_review', 'in_review') then 0 else 1 end,
             moderation_case.created_at
    limit page_size;
end;
$$;

create function public.list_moderation_album_evidence(target_case_id uuid)
returns table (album_id uuid, album_item_id uuid, object_path text, hold_until timestamptz)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_actor uuid := auth.uid();
begin
    if not private.is_active_moderator(v_actor) then
        raise exception using errcode = '42501', message = 'MODERATOR_REQUIRED';
    end if;
    return query
    select evidence.album_id, evidence.album_item_id, evidence.object_path, evidence.hold_until
    from private.private_album_report_evidence evidence
    where evidence.moderation_case_id = target_case_id
      and evidence.hold_until > now()
    order by evidence.created_at, evidence.object_path
    limit 10;
end;
$$;

create function public.list_moderation_audit(page_size integer default 50)
returns table (
    event_id bigint,
    event_type text,
    actor_id uuid,
    subject_user_id uuid,
    moderation_case_id uuid,
    decision text,
    created_at timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_actor uuid := auth.uid();
begin
    if not private.is_active_moderator(v_actor) then
        raise exception using errcode = '42501', message = 'MODERATOR_REQUIRED';
    end if;
    if page_size not between 1 and 100 then
        raise exception using errcode = 'P0001', message = 'INVALID_PAGE_SIZE';
    end if;
    return query
    select event.id, event.event_type, event.actor_id, event.subject_user_id,
           event.moderation_case_id,
           coalesce(event.metadata ->> 'decision', event.metadata ->> 'action', event.metadata ->> 'role'),
           event.created_at
    from public.audit_events event
    where event.event_type like 'moderation_console.%'
       or event.event_type = 'profile_photo.review_decided'
    order by event.created_at desc, event.id desc
    limit page_size;
end;
$$;

create function public.search_moderation_users(search_text text, page_size integer default 30)
returns table (
    user_id uuid,
    display_name text,
    account_status public.account_status,
    verified boolean,
    report_count bigint,
    active_sanction text,
    created_at timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_actor uuid := auth.uid();
    v_search text := btrim(coalesce(search_text, ''));
begin
    if not private.is_active_moderator(v_actor) then
        raise exception using errcode = '42501', message = 'MODERATOR_REQUIRED';
    end if;
    if char_length(v_search) > 60 or page_size not between 1 and 50 then
        raise exception using errcode = 'P0001', message = 'INVALID_USER_SEARCH';
    end if;
    return query
    select account.id, profile.display_name, account.status, coalesce(profile.verified, false),
           (select count(*) from public.reports report where report.reported_user_id = account.id),
           (select sanction.sanction_kind from private.account_moderation_sanctions sanction where sanction.user_id = account.id and sanction.active order by sanction.created_at desc limit 1),
           account.created_at
    from public.accounts account
    left join public.profiles profile on profile.id = account.id
    where v_search = ''
       or account.id::text = v_search
       or profile.display_name ilike '%' || replace(replace(v_search, '%', '\%'), '_', '\_') || '%' escape '\'
    order by account.created_at desc
    limit page_size;
end;
$$;

create function public.list_moderation_staff()
returns table (user_id uuid, display_name text, staff_role text, active boolean, granted_at timestamptz)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare v_actor uuid := auth.uid();
begin
    if not private.is_active_moderation_admin(v_actor) then
        raise exception using errcode = '42501', message = 'ADMIN_REQUIRED';
    end if;
    return query
    select staff.user_id, profile.display_name, staff.staff_role, staff.active, staff.granted_at
    from private.moderation_staff staff
    left join public.profiles profile on profile.id = staff.user_id
    order by staff.active desc, staff.staff_role, staff.granted_at;
end;
$$;

create function public.moderation_console_action(
    action text,
    target_user_id uuid default null,
    target_case_id uuid default null,
    target_album_id uuid default null,
    target_album_item_id uuid default null,
    reason text default null,
    suspension_hours integer default null
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_actor uuid := auth.uid();
    v_owner uuid;
    v_path text;
begin
    if not private.is_active_moderator(v_actor) then
        raise exception using errcode = '42501', message = 'MODERATOR_REQUIRED';
    end if;
    perform private.check_moderation_action_rate(v_actor);

    if action in ('resolve_case', 'dismiss_case') then
        update public.moderation_cases moderation_case
           set state = case action when 'resolve_case' then 'resolved'::public.moderation_state else 'dismissed'::public.moderation_state end,
               assigned_to = v_actor, resolved_at = now()
         where moderation_case.id = target_case_id
           and moderation_case.state in ('pending_review', 'in_review');
        if not found then raise exception using errcode = 'P0001', message = 'MODERATION_CASE_STALE'; end if;
    elsif action = 'remove_album_item' then
        select marker.owner_id, evidence.object_path into v_owner, v_path
        from private.private_album_report_evidence evidence
        join private.private_album_report_markers marker on marker.moderation_case_id = evidence.moderation_case_id
        where evidence.moderation_case_id = target_case_id
          and evidence.album_item_id = target_album_item_id
          and evidence.album_id = target_album_id
          and evidence.hold_until > now();
        if v_path is null then raise exception using errcode = 'P0001', message = 'ALBUM_EVIDENCE_STALE'; end if;
        perform private.lock_private_album_object_path(v_path);
        update private.private_album_items set status = 'removed_by_moderation', position = null where id = target_album_item_id and album_id = target_album_id;
        perform private.enqueue_private_album_object(v_path);
    elsif action = 'remove_album' then
        select marker.owner_id into v_owner from private.private_album_report_markers marker where marker.moderation_case_id = target_case_id;
        if v_owner is null or not exists (select 1 from private.private_album_report_evidence evidence where evidence.moderation_case_id = target_case_id and evidence.album_id = target_album_id) then
            raise exception using errcode = 'P0001', message = 'ALBUM_EVIDENCE_STALE';
        end if;
        perform private.lock_private_album_owner(v_owner);
        update private.private_albums set status = 'removed_by_moderation' where id = target_album_id and owner_id = v_owner;
        update private.private_album_grants set revoked_at = coalesce(revoked_at, now()), revoke_reason = coalesce(revoke_reason, 'moderation') where album_id = target_album_id;
        for v_path in select item.object_path from private.private_album_items item where item.album_id = target_album_id order by item.object_path loop
            perform private.lock_private_album_object_path(v_path);
            perform private.enqueue_private_album_object(v_path);
        end loop;
        update private.private_album_items set status = 'removed_by_moderation', position = null where album_id = target_album_id;
    elsif action in ('warn_user', 'suspend_user', 'ban_user', 'reactivate_user') then
        if not private.is_active_moderation_admin(v_actor) then raise exception using errcode = '42501', message = 'ADMIN_REQUIRED'; end if;
        if target_user_id is null or target_user_id = v_actor then raise exception using errcode = 'P0001', message = 'INVALID_ACCOUNT_ACTION'; end if;
        if action <> 'reactivate_user' and reason not in ('spam', 'harassment', 'fake_profile', 'adult_content', 'abusive_content', 'safety', 'other') then raise exception using errcode = 'P0001', message = 'INVALID_SANCTION_REASON'; end if;
        if action = 'reactivate_user' then
            update private.account_moderation_sanctions set active = false, ended_by = v_actor, ended_at = now() where user_id = target_user_id and active;
            update public.accounts set status = 'active' where id = target_user_id and status = 'suspended';
            update public.profiles set discovery_visible = true where id = target_user_id;
        else
            if action = 'suspend_user' and suspension_hours not between 1 and 8760 then raise exception using errcode = 'P0001', message = 'INVALID_SUSPENSION_DURATION'; end if;
            insert into private.account_moderation_sanctions (user_id, sanction_kind, reason, active, expires_at, created_by)
            values (target_user_id, case action when 'warn_user' then 'warning' when 'suspend_user' then 'suspension' else 'ban' end, reason, action <> 'warn_user', case when action = 'suspend_user' then now() + make_interval(hours => suspension_hours) else null end, v_actor);
            if action <> 'warn_user' then
                update public.accounts set status = 'suspended' where id = target_user_id and status <> 'deleted';
                update public.profiles set discovery_visible = false where id = target_user_id;
                delete from auth.sessions where user_id = target_user_id;
            end if;
        end if;
    else
        raise exception using errcode = 'P0001', message = 'INVALID_MODERATION_ACTION';
    end if;

    insert into public.audit_events (event_type, actor_id, subject_user_id, moderation_case_id, metadata)
    values ('moderation_console.' || action, v_actor, coalesce(target_user_id, v_owner), target_case_id, jsonb_build_object('action', action));
    return action;
end;
$$;

create function public.manage_moderation_staff(target_email text, new_role text, new_active boolean)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_actor uuid := auth.uid();
    v_target uuid;
    v_existing_role text;
    v_existing_active boolean;
begin
    if not private.is_active_moderation_admin(v_actor) then raise exception using errcode = '42501', message = 'ADMIN_REQUIRED'; end if;
    perform private.check_moderation_action_rate(v_actor);
    if new_role not in ('reviewer', 'admin') or target_email is null or char_length(btrim(target_email)) > 254 then raise exception using errcode = 'P0001', message = 'INVALID_STAFF_CHANGE'; end if;
    select user_record.id into v_target from auth.users user_record where lower(user_record.email) = lower(btrim(target_email));
    if v_target is null or not exists (select 1 from public.accounts account where account.id = v_target and account.status = 'active') then raise exception using errcode = 'P0001', message = 'STAFF_ACCOUNT_NOT_ACTIVE'; end if;
    select staff.staff_role, staff.active into v_existing_role, v_existing_active from private.moderation_staff staff where staff.user_id = v_target for update;
    if v_existing_role = 'admin' and v_existing_active and (new_role <> 'admin' or not new_active)
       and (select count(*) from private.moderation_staff staff where staff.staff_role = 'admin' and staff.active) <= 1 then
        raise exception using errcode = 'P0001', message = 'LAST_ACTIVE_ADMIN';
    end if;
    insert into private.moderation_staff (user_id, staff_role, active, granted_by)
    values (v_target, new_role, new_active, v_actor)
    on conflict (user_id) do update set staff_role = excluded.staff_role, active = excluded.active, granted_at = now(), granted_by = v_actor;
    insert into public.audit_events (event_type, actor_id, subject_user_id, metadata)
    values ('moderation_console.staff_changed', v_actor, v_target, jsonb_build_object('role', new_role, 'active', new_active));
    return new_role;
end;
$$;

revoke all on function public.get_moderation_console_overview() from public, anon;
revoke all on function public.list_moderation_cases(integer, text) from public, anon;
revoke all on function public.list_moderation_album_evidence(uuid) from public, anon;
revoke all on function public.list_moderation_audit(integer) from public, anon;
revoke all on function public.search_moderation_users(text, integer) from public, anon;
revoke all on function public.list_moderation_staff() from public, anon;
revoke all on function public.moderation_console_action(text, uuid, uuid, uuid, uuid, text, integer) from public, anon;
revoke all on function public.manage_moderation_staff(text, text, boolean) from public, anon;

grant execute on function public.get_moderation_console_overview() to authenticated;
grant execute on function public.list_moderation_cases(integer, text) to authenticated;
grant execute on function public.list_moderation_album_evidence(uuid) to authenticated;
grant execute on function public.list_moderation_audit(integer) to authenticated;
grant execute on function public.search_moderation_users(text, integer) to authenticated;
grant execute on function public.list_moderation_staff() to authenticated;
grant execute on function public.moderation_console_action(text, uuid, uuid, uuid, uuid, text, integer) to authenticated;
grant execute on function public.manage_moderation_staff(text, text, boolean) to authenticated;
