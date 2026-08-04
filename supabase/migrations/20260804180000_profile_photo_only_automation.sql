-- Automated Vision triage is restricted to the single public profile-photo
-- candidate. Chat and private-album photos remain private/authorized and
-- reportable, but their bytes are never sent to the automated provider.

alter table private.profile_photo_submissions
    add column automation_state text not null default 'queued'
        check (automation_state in ('queued', 'processing', 'review', 'completed')),
    add column automation_attempts integer not null default 0
        check (automation_attempts between 0 and 10),
    add column automation_next_attempt_at timestamptz not null default now(),
    add column automation_lease_token uuid,
    add column automation_leased_until timestamptz,
    add column automation_error_code text check (
        automation_error_code is null or automation_error_code in (
            'VISION_UNAVAILABLE', 'VISION_INVALID_RESPONSE', 'MEDIA_NOT_FOUND'
        )
    ),
    add constraint profile_photo_automation_lease_pair check (
        (automation_lease_token is null and automation_leased_until is null)
        or (automation_lease_token is not null and automation_leased_until is not null)
    );

update private.profile_photo_submissions
   set automation_state = case when status = 'pending' then 'queued' else 'completed' end;

create index profile_photo_submissions_worker_idx
    on private.profile_photo_submissions (
        automation_state, automation_next_attempt_at, created_at
    )
    where status = 'pending';

comment on table private.profile_photo_submissions is
    'Single owner-private profile-photo candidate and its automated moderation lease; only the approved path is public.';

create or replace function private.clear_deleted_current_profile_photo()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    if old.bucket_id = 'profile-photos' then
        update public.profiles profile
           set avatar_path = null
         where profile.avatar_path = old.name;

        update private.profile_photo_submissions submission
           set candidate_path = null,
               status = 'none',
               automation_state = 'completed',
               automation_attempts = 0,
               automation_next_attempt_at = now(),
               automation_lease_token = null,
               automation_leased_until = null,
               automation_error_code = null
         where submission.candidate_path = old.name;
    end if;
    return old;
end;
$$;

create or replace function public.submit_profile_photo(object_path text)
returns table (
    submitted_path text,
    moderation_status public.profile_photo_moderation_status
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_object storage.objects%rowtype;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if not private.account_is_active(v_user) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE';
    end if;
    if object_path is null or not private.profile_photo_path_is_valid(v_user, object_path) then
        raise exception using errcode = 'P0001', message = 'INVALID_PROFILE_PHOTO_PATH';
    end if;

    select object.* into v_object
      from storage.objects object
     where object.bucket_id = 'profile-photos'
       and object.name = submit_profile_photo.object_path
       and coalesce(object.owner_id, object.owner::text) = v_user::text
     for update;
    if not found then
        raise exception using errcode = 'P0001', message = 'PROFILE_PHOTO_NOT_FOUND';
    end if;
    if not private.profile_photo_metadata_is_safe(v_object.metadata) then
        raise exception using errcode = 'P0001', message = 'INVALID_PROFILE_PHOTO_METADATA';
    end if;
    if not exists (select 1 from public.profiles profile where profile.id = v_user) then
        raise exception using errcode = 'P0001', message = 'PROFILE_NOT_FOUND';
    end if;

    return query
    insert into private.profile_photo_submissions (
        user_id, candidate_path, status, automation_state,
        automation_attempts, automation_next_attempt_at,
        automation_lease_token, automation_leased_until, automation_error_code
    ) values (
        v_user, submit_profile_photo.object_path, 'pending', 'queued',
        0, now(), null, null, null
    )
    on conflict (user_id) do update
       set candidate_path = excluded.candidate_path,
           status = 'pending',
           automation_state = 'queued',
           automation_attempts = 0,
           automation_next_attempt_at = now(),
           automation_lease_token = null,
           automation_leased_until = null,
           automation_error_code = null
    returning candidate_path, status;
end;
$$;

create or replace function public.moderate_profile_photo(
    profile_id uuid,
    object_path text,
    decision public.profile_photo_moderation_status
)
returns table (
    moderated_path text,
    moderation_status public.profile_photo_moderation_status
)
language plpgsql
security definer
set search_path = ''
as $$
begin
    if auth.role() <> 'service_role' then
        raise exception using errcode = '42501', message = 'SERVICE_ROLE_REQUIRED';
    end if;
    if decision is null or decision not in ('approved', 'blocked_adult', 'blocked_abusive') then
        raise exception using errcode = 'P0001', message = 'INVALID_PROFILE_PHOTO_DECISION';
    end if;

    update private.profile_photo_submissions submission
       set status = moderate_profile_photo.decision,
           automation_state = 'completed', automation_error_code = null,
           automation_lease_token = null, automation_leased_until = null
     where submission.user_id = moderate_profile_photo.profile_id
       and submission.candidate_path = moderate_profile_photo.object_path
       and submission.status = 'pending'
       and exists (
           select 1 from storage.objects object
            where object.bucket_id = 'profile-photos'
              and object.name = moderate_profile_photo.object_path
              and coalesce(object.owner_id, object.owner::text) = moderate_profile_photo.profile_id::text
       );
    if not found then
        raise exception using errcode = 'P0001', message = 'PROFILE_PHOTO_NOT_CURRENT';
    end if;

    if moderate_profile_photo.decision = 'approved' then
        update public.profiles profile
           set avatar_path = moderate_profile_photo.object_path
         where profile.id = moderate_profile_photo.profile_id;
        if not found then
            raise exception using errcode = 'P0001', message = 'PROFILE_NOT_FOUND';
        end if;
    end if;
    return query select moderate_profile_photo.object_path, moderate_profile_photo.decision;
end;
$$;

create or replace function public.claim_profile_photo_moderation(batch_size integer default 10)
returns table (
    profile_id uuid,
    lease_token uuid,
    object_path text,
    mime_type text
)
language plpgsql
security definer
set search_path = ''
as $$
begin
    if auth.role() <> 'service_role' then
        raise exception using errcode = '42501', message = 'FORBIDDEN';
    end if;
    if batch_size not between 1 and 25 then
        raise exception using errcode = 'P0001', message = 'INVALID_BATCH_SIZE';
    end if;

    update private.profile_photo_submissions submission
       set automation_state = 'queued', automation_lease_token = null,
           automation_leased_until = null, automation_next_attempt_at = now(),
           automation_error_code = 'VISION_UNAVAILABLE'
     where submission.status = 'pending' and submission.automation_state = 'processing'
       and submission.automation_leased_until < now();

    return query
    with candidates as (
        select submission.user_id
          from private.profile_photo_submissions submission
         where submission.status = 'pending'
           and submission.candidate_path is not null
           and submission.automation_state = 'queued'
           and submission.automation_attempts < 10
           and submission.automation_next_attempt_at <= now()
         order by submission.automation_next_attempt_at, submission.created_at
         for update skip locked
         limit batch_size
    ), leased as (
        update private.profile_photo_submissions submission
           set automation_state = 'processing',
               automation_attempts = submission.automation_attempts + 1,
               automation_lease_token = gen_random_uuid(),
               automation_leased_until = now() + interval '3 minutes'
          from candidates
         where submission.user_id = candidates.user_id
        returning submission.user_id, submission.automation_lease_token,
                  submission.candidate_path
    )
    select leased.user_id, leased.automation_lease_token, leased.candidate_path,
           case
               when lower(leased.candidate_path) like '%.jpg'
                 or lower(leased.candidate_path) like '%.jpeg' then 'image/jpeg'
               when lower(leased.candidate_path) like '%.png' then 'image/png'
               else 'image/webp'
           end
      from leased;
end;
$$;

create or replace function public.complete_profile_photo_moderation(
    target_profile_id uuid,
    target_lease_token uuid,
    outcome text,
    error_code text default null
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare v_photo private.profile_photo_submissions%rowtype;
begin
    if auth.role() <> 'service_role' then
        raise exception using errcode = '42501', message = 'FORBIDDEN';
    end if;
    if outcome not in ('approved', 'adult', 'abusive', 'review', 'retry') then
        raise exception using errcode = 'P0001', message = 'INVALID_MODERATION_OUTCOME';
    end if;
    select * into v_photo from private.profile_photo_submissions submission
     where submission.user_id = target_profile_id for update;
    if v_photo.user_id is null or v_photo.status <> 'pending'
       or v_photo.automation_state <> 'processing'
       or v_photo.automation_lease_token <> target_lease_token
       or v_photo.automation_leased_until < now() then
        return false;
    end if;

    if outcome in ('approved', 'adult', 'abusive') then
        update private.profile_photo_submissions
           set status = case outcome
                   when 'approved' then 'approved'::public.profile_photo_moderation_status
                   when 'adult' then 'blocked_adult'::public.profile_photo_moderation_status
                   else 'blocked_abusive'::public.profile_photo_moderation_status
               end,
               automation_state = 'completed', automation_error_code = null,
               automation_lease_token = null, automation_leased_until = null
         where user_id = target_profile_id;
        if outcome = 'approved' then
            update public.profiles set avatar_path = v_photo.candidate_path
             where id = target_profile_id;
        end if;
    elsif outcome = 'review' then
        update private.profile_photo_submissions
           set automation_state = 'review', automation_error_code = null,
               automation_lease_token = null, automation_leased_until = null
         where user_id = target_profile_id;
    else
        update private.profile_photo_submissions
           set automation_state = case when automation_attempts >= 10 then 'review' else 'queued' end,
               automation_error_code = case
                   when error_code in ('VISION_UNAVAILABLE', 'VISION_INVALID_RESPONSE', 'MEDIA_NOT_FOUND')
                       then error_code else 'VISION_UNAVAILABLE'
               end,
               automation_next_attempt_at = now() + least(
                   interval '6 hours',
                   interval '1 minute' * power(2::double precision, least(automation_attempts, 8))
               ),
               automation_lease_token = null, automation_leased_until = null
         where user_id = target_profile_id;
    end if;
    return true;
end;
$$;

-- Existing pending chat photos become visible to their authorized
-- participants, and new ones skip automated moderation entirely.
update private.chat_media
   set status = 'approved', moderated_at = coalesce(moderated_at, now()),
       automation_state = 'completed', automation_error_code = null,
       automation_lease_token = null, automation_leased_until = null
 where status = 'pending';

alter table private.chat_media alter column status set default 'approved';
alter table private.chat_media alter column automation_state set default 'completed';

create or replace function public.send_photo_message(
    conversation_id uuid,
    client_message_id uuid,
    object_path text,
    media_type text
)
returns table (message_id uuid, moderation_status public.chat_media_status)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_sender uuid := auth.uid();
    v_message_id uuid;
    v_existing private.chat_media%rowtype;
    v_object storage.objects%rowtype;
    v_now timestamptz := clock_timestamp();
begin
    if v_sender is null then raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED'; end if;
    if not private.account_is_active(v_sender) then raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE'; end if;
    if client_message_id is null or media_type not in ('image/jpeg', 'image/png', 'image/webp')
       or not private.chat_media_path_is_valid(v_sender, conversation_id, client_message_id, object_path) then
        raise exception using errcode = 'P0001', message = 'INVALID_CHAT_PHOTO';
    end if;
    perform pg_advisory_xact_lock(hashtextextended(v_sender::text || ':' || client_message_id::text, 0));
    select message.id into v_message_id from public.messages message
     where message.sender_id = v_sender and message.client_message_id = send_photo_message.client_message_id;
    if v_message_id is not null then
        select * into v_existing from private.chat_media media where media.message_id = v_message_id;
        if v_existing.conversation_id <> send_photo_message.conversation_id
           or v_existing.object_path <> send_photo_message.object_path
           or v_existing.mime_type <> send_photo_message.media_type then
            raise exception using errcode = 'P0001', message = 'CLIENT_MESSAGE_CONFLICT';
        end if;
        return query select v_message_id, v_existing.status;
        return;
    end if;
    perform 1 from public.conversations conversation
     where conversation.id = send_photo_message.conversation_id
       and private.can_access_conversation(conversation.id, v_sender) for update;
    if not found then raise exception using errcode = 'P0001', message = 'CHAT_NOT_AVAILABLE'; end if;
    select object.* into v_object from storage.objects object
     where object.bucket_id = 'chat-media' and object.name = send_photo_message.object_path;
    if v_object.id is null or coalesce(v_object.owner_id, v_object.owner::text) <> v_sender::text
       or not private.chat_media_metadata_is_safe(v_object.metadata)
       or coalesce(v_object.metadata ->> 'size', '') !~ '^[0-9]{1,10}$'
       or lower(v_object.metadata ->> 'mimetype') <> media_type then
        raise exception using errcode = 'P0001', message = 'CHAT_PHOTO_NOT_FOUND';
    end if;
    insert into public.messages (conversation_id, sender_id, body, kind, client_message_id, created_at)
    values (conversation_id, v_sender, null, 'photo', client_message_id, v_now)
    returning id into v_message_id;
    insert into private.chat_media (
        message_id, sender_id, conversation_id, object_path, mime_type,
        status, moderated_at, automation_state
    ) values (
        v_message_id, v_sender, conversation_id, object_path, media_type,
        'approved', v_now, 'completed'
    );
    update public.conversations set last_message_at = v_now where id = conversation_id;
    return query select v_message_id, 'approved'::public.chat_media_status;
end;
$$;

revoke execute on function public.claim_chat_media_moderation(integer) from service_role;
revoke execute on function public.complete_chat_media_moderation(uuid, uuid, text, text) from service_role;

revoke all on function public.claim_profile_photo_moderation(integer) from public;
revoke all on function public.complete_profile_photo_moderation(uuid, uuid, text, text) from public;
grant execute on function public.claim_profile_photo_moderation(integer) to service_role;
grant execute on function public.complete_profile_photo_moderation(uuid, uuid, text, text) to service_role;
