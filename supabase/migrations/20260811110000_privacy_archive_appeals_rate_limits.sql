-- User privacy center, per-user conversation archive, moderation appeals and
-- conservative server-side messaging abuse protection.

alter table public.conversation_user_states
    add column if not exists archived_at timestamptz;

create table private.moderation_appeals (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.accounts(id) on delete cascade,
    sanction_id uuid not null references private.account_moderation_sanctions(id) on delete cascade,
    statement text not null check (char_length(btrim(statement)) between 20 and 2000),
    state text not null default 'pending' check (state in ('pending', 'in_review', 'accepted', 'rejected')),
    created_at timestamptz not null default now(),
    decided_at timestamptz,
    decided_by uuid references auth.users(id) on delete set null,
    unique (user_id, sanction_id)
);

alter table private.moderation_appeals enable row level security;
revoke all on table private.moderation_appeals from public, anon, authenticated;

create or replace function public.get_privacy_settings()
returns table (show_activity_status boolean)
language sql stable security definer set search_path = '' as $$
    select profile.show_activity_status
      from public.profiles profile
     where profile.id = auth.uid() and private.account_is_active(auth.uid());
$$;

create or replace function public.list_hidden_profiles(page_size integer default 100)
returns setof public.discovery_profile_result
language sql stable security definer set search_path = '' as $$
    select profile.id, profile.display_name, profile.age, profile.bio, profile.intent,
           profile.region_code, profile.verified, profile.avatar_path,
           array[]::text[], null::text, false, coalesce(preference.cursor_version, 1),
           false, null::text
      from private.profile_hides hidden
      join public.profiles profile on profile.id = hidden.target_id
      left join private.profile_preferences preference on preference.user_id = hidden.owner_id
     where hidden.owner_id = auth.uid() and private.account_is_active(auth.uid())
     order by hidden.created_at desc, profile.id
     limit greatest(1, least(page_size, 100));
$$;

create or replace function public.list_blocked_profiles(page_size integer default 100)
returns setof public.discovery_profile_result
language sql stable security definer set search_path = '' as $$
    select profile.id, profile.display_name, profile.age, profile.bio, profile.intent,
           profile.region_code, profile.verified, profile.avatar_path,
           array[]::text[], null::text, false, coalesce(preference.cursor_version, 1),
           false, null::text
      from public.blocks blocked
      join public.profiles profile on profile.id = blocked.blocked_id
      left join private.profile_preferences preference on preference.user_id = blocked.blocker_id
     where blocked.blocker_id = auth.uid() and private.account_is_active(auth.uid())
     order by blocked.created_at desc, profile.id
     limit greatest(1, least(page_size, 100));
$$;

create or replace function public.unblock_user(target_user_id uuid)
returns boolean
language plpgsql security definer set search_path = '' as $$
declare v_user uuid := auth.uid();
begin
    if v_user is null then raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED'; end if;
    if target_user_id is null or target_user_id = v_user then
        raise exception using errcode = 'P0001', message = 'INVALID_BLOCK_TARGET';
    end if;
    delete from public.blocks block
     where block.blocker_id = v_user and block.blocked_id = target_user_id;
    if not found then return false; end if;
    update public.conversations conversation set status = 'active', updated_at = now()
     where conversation.status = 'blocked'
       and v_user in (conversation.participant_a, conversation.participant_b)
       and target_user_id in (conversation.participant_a, conversation.participant_b)
       and private.account_is_active(conversation.participant_a)
       and private.account_is_active(conversation.participant_b)
       and not private.is_blocked_pair(conversation.participant_a, conversation.participant_b);
    insert into public.audit_events(event_type, actor_id, subject_user_id)
    values ('user_unblocked', v_user, target_user_id);
    return true;
end;
$$;

create or replace function public.set_conversation_archived(target_conversation_id uuid, archived boolean)
returns boolean
language plpgsql security definer set search_path = '' as $$
declare v_user uuid := auth.uid();
begin
    if v_user is null then raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED'; end if;
    if not private.can_access_conversation(target_conversation_id, v_user) then
        raise exception using errcode = 'P0001', message = 'CHAT_NOT_AVAILABLE';
    end if;
    insert into public.conversation_user_states(conversation_id, user_id, archived_at)
    values (target_conversation_id, v_user, case when archived then now() else null end)
    on conflict (conversation_id, user_id) do update
       set archived_at = excluded.archived_at;
    return archived;
end;
$$;

create or replace function public.list_archived_conversation_ids()
returns setof uuid
language sql stable security definer set search_path = '' as $$
    select state.conversation_id from public.conversation_user_states state
     where state.user_id = auth.uid() and state.archived_at is not null
       and private.can_access_conversation(state.conversation_id, auth.uid());
$$;

create or replace function public.get_my_active_sanction()
returns table (sanction_id uuid, sanction_kind text, reason text, expires_at timestamptz, appeal_state text)
language sql stable security definer set search_path = '' as $$
    select sanction.id, sanction.sanction_kind, sanction.reason, sanction.expires_at, appeal.state
      from private.account_moderation_sanctions sanction
      left join private.moderation_appeals appeal
        on appeal.sanction_id = sanction.id and appeal.user_id = sanction.user_id
     where sanction.user_id = auth.uid() and sanction.active
     order by sanction.created_at desc limit 1;
$$;

create or replace function public.submit_moderation_appeal(target_sanction_id uuid, appeal_statement text)
returns uuid
language plpgsql security definer set search_path = '' as $$
declare v_user uuid := auth.uid(); v_id uuid;
begin
    if v_user is null then raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED'; end if;
    if appeal_statement is null or char_length(btrim(appeal_statement)) not between 20 and 2000 then
        raise exception using errcode = 'P0001', message = 'INVALID_APPEAL';
    end if;
    if not exists (select 1 from private.account_moderation_sanctions sanction
                    where sanction.id = target_sanction_id and sanction.user_id = v_user and sanction.active) then
        raise exception using errcode = 'P0001', message = 'SANCTION_NOT_AVAILABLE';
    end if;
    if exists (select 1 from private.moderation_appeals appeal where appeal.user_id = v_user
               and appeal.created_at >= now() - interval '24 hours') then
        raise exception using errcode = 'P0001', message = 'APPEAL_RATE_LIMITED';
    end if;
    insert into private.moderation_appeals(user_id, sanction_id, statement)
    values (v_user, target_sanction_id, btrim(appeal_statement)) returning id into v_id;
    insert into public.audit_events(event_type, actor_id, subject_user_id)
    values ('moderation.appeal_submitted', v_user, v_user);
    return v_id;
exception when unique_violation then
    raise exception using errcode = 'P0001', message = 'APPEAL_ALREADY_SUBMITTED';
end;
$$;

create or replace function private.guard_message_rate_limit()
returns trigger language plpgsql security definer set search_path = '' as $$
declare v_user uuid := auth.uid();
begin
    if v_user is null or new.sender_id <> v_user then return new; end if;
    if (select count(*) from public.messages message where message.sender_id = v_user
        and message.created_at >= now() - interval '1 minute') >= 30 then
        raise exception using errcode = 'P0001', message = 'MESSAGE_RATE_LIMITED';
    end if;
    if new.body is not null and (select count(*) from public.messages message
        where message.sender_id = v_user and message.kind = 'text'
          and message.body = new.body and message.created_at >= now() - interval '10 minutes') >= 5 then
        raise exception using errcode = 'P0001', message = 'REPEATED_MESSAGE_LIMITED';
    end if;
    return new;
end;
$$;

drop trigger if exists messages_rate_limit on public.messages;
create trigger messages_rate_limit before insert on public.messages
for each row execute function private.guard_message_rate_limit();

revoke all on function public.get_privacy_settings(), public.list_hidden_profiles(integer),
 public.list_blocked_profiles(integer), public.unblock_user(uuid),
 public.set_conversation_archived(uuid, boolean), public.list_archived_conversation_ids(),
 public.get_my_active_sanction(), public.submit_moderation_appeal(uuid, text) from public, anon;
grant execute on function public.get_privacy_settings(), public.list_hidden_profiles(integer),
 public.list_blocked_profiles(integer), public.unblock_user(uuid),
 public.set_conversation_archived(uuid, boolean), public.list_archived_conversation_ids(),
 public.get_my_active_sanction(), public.submit_moderation_appeal(uuid, text) to authenticated;
revoke all on function private.guard_message_rate_limit() from public, anon, authenticated;
