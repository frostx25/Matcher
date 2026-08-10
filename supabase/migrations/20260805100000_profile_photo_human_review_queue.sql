-- Human review is a separate, staff-only path. A decision is bound to the
-- exact candidate shown to the reviewer and records only normalized metadata.

create table private.moderation_staff (
    user_id uuid primary key references auth.users (id) on delete cascade,
    staff_role text not null default 'reviewer'
        check (staff_role in ('reviewer', 'admin')),
    active boolean not null default true,
    granted_at timestamptz not null default now(),
    granted_by uuid references auth.users (id) on delete set null
);

alter table private.moderation_staff enable row level security;
revoke all on table private.moderation_staff from anon, authenticated;

create function private.is_active_moderator(target_user_id uuid)
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
          and account.status = 'active'
    );
$$;

revoke all on function private.is_active_moderator(uuid) from public, anon, authenticated;

create function public.list_profile_photo_review_queue(
    page_size integer default 20,
    cursor_created_at timestamptz default null,
    cursor_profile_id uuid default null
)
returns table (
    profile_id uuid,
    display_name text,
    candidate_path text,
    submitted_at timestamptz,
    has_approved_photo boolean
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_reviewer uuid := auth.uid();
begin
    if not private.is_active_moderator(v_reviewer) then
        raise exception using errcode = '42501', message = 'MODERATOR_REQUIRED';
    end if;
    if page_size not between 1 and 50 then
        raise exception using errcode = 'P0001', message = 'INVALID_PAGE_SIZE';
    end if;
    if (cursor_created_at is null) <> (cursor_profile_id is null) then
        raise exception using errcode = 'P0001', message = 'INVALID_CURSOR';
    end if;

    return query
    select
        submission.user_id,
        profile.display_name,
        submission.candidate_path,
        submission.created_at,
        profile.avatar_path is not null
    from private.profile_photo_submissions submission
    join public.profiles profile on profile.id = submission.user_id
    join public.accounts account on account.id = submission.user_id
    where submission.status = 'pending'
      and submission.automation_state = 'review'
      and submission.candidate_path is not null
      and account.status = 'active'
      and (
          cursor_created_at is null
          or (submission.created_at, submission.user_id) >
             (cursor_created_at, cursor_profile_id)
      )
    order by submission.created_at, submission.user_id
    limit page_size;
end;
$$;

create function public.decide_profile_photo_review(
    target_profile_id uuid,
    expected_candidate_path text,
    decision public.profile_photo_moderation_status
)
returns public.profile_photo_moderation_status
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_reviewer uuid := auth.uid();
begin
    if not private.is_active_moderator(v_reviewer) then
        raise exception using errcode = '42501', message = 'MODERATOR_REQUIRED';
    end if;
    if decision is null or decision not in ('approved', 'blocked_adult', 'blocked_abusive') then
        raise exception using errcode = 'P0001', message = 'INVALID_PROFILE_PHOTO_DECISION';
    end if;

    perform 1
    from private.profile_photo_submissions submission
    where submission.user_id = target_profile_id
      and submission.candidate_path = expected_candidate_path
      and submission.status = 'pending'
      and submission.automation_state = 'review'
      and exists (
          select 1
          from storage.objects object
          where object.bucket_id = 'profile-photos'
            and object.name = expected_candidate_path
            and coalesce(object.owner_id, object.owner::text) = target_profile_id::text
      )
    for update;

    if not found then
        raise exception using errcode = 'P0001', message = 'PROFILE_PHOTO_REVIEW_STALE';
    end if;

    update private.profile_photo_submissions submission
       set status = decision,
           automation_state = 'completed',
           automation_error_code = null,
           automation_lease_token = null,
           automation_leased_until = null
     where submission.user_id = target_profile_id;

    if decision = 'approved' then
        update public.profiles profile
           set avatar_path = expected_candidate_path
         where profile.id = target_profile_id;
        if not found then
            raise exception using errcode = 'P0001', message = 'PROFILE_NOT_FOUND';
        end if;
    end if;

    insert into public.audit_events (
        event_type,
        actor_id,
        subject_user_id,
        metadata
    ) values (
        'profile_photo.review_decided',
        v_reviewer,
        target_profile_id,
        jsonb_build_object('decision', decision::text)
    );

    return decision;
end;
$$;

revoke all on function public.list_profile_photo_review_queue(integer, timestamptz, uuid)
    from public, anon;
revoke all on function public.decide_profile_photo_review(uuid, text, public.profile_photo_moderation_status)
    from public, anon;
grant execute on function public.list_profile_photo_review_queue(integer, timestamptz, uuid)
    to authenticated;
grant execute on function public.decide_profile_photo_review(uuid, text, public.profile_photo_moderation_status)
    to authenticated;

comment on table private.moderation_staff is
    'Explicit internal staff allowlist; never exposed to application users.';
comment on function public.list_profile_photo_review_queue(integer, timestamptz, uuid) is
    'Lists only active profile-photo candidates waiting for human review to active moderators.';
comment on function public.decide_profile_photo_review(uuid, text, public.profile_photo_moderation_status) is
    'Applies an audited human decision only to the exact current review candidate.';
