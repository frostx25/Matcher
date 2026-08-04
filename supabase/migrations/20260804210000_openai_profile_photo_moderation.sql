-- Replace provider-specific Vision errors with provider-neutral moderation
-- errors and give pending candidates a fresh retry budget for OpenAI.

alter table private.profile_photo_submissions
    drop constraint if exists profile_photo_submissions_automation_error_code_check;

update private.profile_photo_submissions
   set automation_attempts = 0,
       automation_next_attempt_at = now(),
       automation_error_code = null
 where status = 'pending'
   and automation_state = 'queued'
   and automation_error_code in ('VISION_UNAVAILABLE', 'VISION_INVALID_RESPONSE');

update private.profile_photo_submissions
   set automation_error_code = case automation_error_code
       when 'VISION_UNAVAILABLE' then 'MODERATION_UNAVAILABLE'
       when 'VISION_INVALID_RESPONSE' then 'MODERATION_INVALID_RESPONSE'
       else automation_error_code
   end
 where automation_error_code in ('VISION_UNAVAILABLE', 'VISION_INVALID_RESPONSE');

alter table private.profile_photo_submissions
    add constraint profile_photo_submissions_automation_error_code_check check (
        automation_error_code is null or automation_error_code in (
            'MODERATION_UNAVAILABLE', 'MODERATION_INVALID_RESPONSE', 'MEDIA_NOT_FOUND'
        )
    );

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
           automation_error_code = 'MODERATION_UNAVAILABLE'
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
                   when error_code in (
                       'MODERATION_UNAVAILABLE', 'MODERATION_INVALID_RESPONSE', 'MEDIA_NOT_FOUND'
                   ) then error_code else 'MODERATION_UNAVAILABLE'
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

revoke all on function public.claim_profile_photo_moderation(integer) from public, anon, authenticated;
revoke all on function public.complete_profile_photo_moderation(uuid, uuid, text, text) from public, anon, authenticated;
grant execute on function public.claim_profile_photo_moderation(integer) to service_role;
grant execute on function public.complete_profile_photo_moderation(uuid, uuid, text, text) to service_role;

comment on function public.claim_profile_photo_moderation(integer) is
    'Leases only the current private profile-photo candidate for provider-neutral moderation.';
comment on function public.complete_profile_photo_moderation(uuid, uuid, text, text) is
    'Applies a normalized profile-photo decision under the current lease without storing provider output.';
