-- Age verification is an optional trust signal after onboarding. A declared
-- adult with accepted terms may use Matcher while the document decision is
-- not started, pending, under review or failed.
alter table public.accounts
    drop constraint accounts_active_state;

alter table public.accounts
    add constraint accounts_active_state check (
        status <> 'active'
        or (
            birth_year is not null
            and terms_accepted_at is not null
            and terms_version is not null
            and char_length(btrim(terms_version)) between 1 and 40
        )
    ),
    add constraint accounts_verified_evidence check (
        age_verification_status <> 'verified'
        or (
            adult_verified_at is not null
            and age_verification_method is not null
            and age_verification_policy_version is not null
        )
    );

create or replace function private.account_is_active(user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.accounts account
        where account.id = account_is_active.user_id
          and account.status = 'active'
          and account.birth_year <= extract(year from current_date)::integer - 18
          and account.terms_accepted_at is not null
          and account.terms_version is not null
          and char_length(btrim(account.terms_version)) between 1 and 40
    );
$$;

-- The hard-gate migration made every formerly active profile private and put
-- its account in pending. There is no durable pre-migration visibility flag,
-- so a complete declared-adult profile is the narrowest safe recovery key.
update public.profiles profile
   set discovery_visible = true
 where exists (
    select 1
    from public.accounts account
    where account.id = profile.id
      and account.status = 'pending'
      and account.birth_year <= extract(year from current_date)::integer - 18
      and account.terms_accepted_at is not null
      and account.terms_version is not null
      and char_length(btrim(account.terms_version)) between 1 and 40
 );

update public.accounts account
   set status = 'active'
 where account.status = 'pending'
   and account.birth_year <= extract(year from current_date)::integer - 18
   and account.terms_accepted_at is not null
   and account.terms_version is not null
   and char_length(btrim(account.terms_version)) between 1 and 40
   and exists (
       select 1
       from public.profiles profile
       where profile.id = account.id
   );

create or replace function private.account_has_document_verification(user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.accounts account
        where account.id = account_has_document_verification.user_id
          and account.age_verification_status = 'verified'
          and account.age_verification_method = 'document'
          and account.adult_verified_at is not null
          and account.age_verification_policy_version is not null
          and exists (
              select 1
              from public.age_verification_attempts attempt
              where attempt.user_id = account.id
                and attempt.status = 'verified'
                and attempt.method = 'DOCUMENT'
          )
    );
$$;

comment on column public.profiles.verified is
    'Server-managed badge set only after an approved documentary age-verification decision.';

update public.profiles profile
   set verified = private.account_has_document_verification(profile.id);

create or replace function private.enforce_profile_document_badge()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    new.verified := private.account_has_document_verification(new.id);
    return new;
end;
$$;

create trigger profiles_enforce_document_badge
before insert or update of verified on public.profiles
for each row execute function private.enforce_profile_document_badge();

create or replace function private.sync_profile_document_badge()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    update public.profiles profile
       set verified = private.account_has_document_verification(new.id)
     where profile.id = new.id;
    return new;
end;
$$;

create trigger accounts_sync_profile_document_badge
after update of age_verification_status, age_verification_method,
    age_verification_policy_version, adult_verified_at on public.accounts
for each row execute function private.sync_profile_document_badge();

create or replace function public.complete_onboarding(
    birth_year integer,
    display_name text,
    region_code text,
    terms_version text,
    terms_accepted boolean,
    bio text default '',
    intent text default 'Conhecer pessoas'
)
returns table (
    profile_id uuid,
    account_status public.account_status,
    calculated_age smallint
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_existing_birth_year smallint;
    v_existing_status public.account_status;
    v_age integer;
    v_verified boolean;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if terms_accepted is distinct from true
       or terms_version is null
       or char_length(btrim(terms_version)) not between 1 and 40 then
        raise exception using errcode = 'P0001', message = 'TERMS_REQUIRED';
    end if;
    if birth_year is null then
        raise exception using errcode = 'P0001', message = 'INVALID_BIRTH_YEAR';
    end if;

    v_age := extract(year from current_date)::integer - birth_year;
    if v_age < 18 then
        raise exception using errcode = 'P0001', message = 'ADULTS_ONLY';
    end if;
    if v_age > 120 then
        raise exception using errcode = 'P0001', message = 'INVALID_BIRTH_YEAR';
    end if;
    if display_name is null or char_length(btrim(display_name)) not between 1 and 60 then
        raise exception using errcode = 'P0001', message = 'INVALID_DISPLAY_NAME';
    end if;
    if bio is null or char_length(bio) > 500 then
        raise exception using errcode = 'P0001', message = 'INVALID_BIO';
    end if;
    if intent is null or char_length(btrim(intent)) not between 1 and 80 then
        raise exception using errcode = 'P0001', message = 'INVALID_INTENT';
    end if;
    if region_code is null or char_length(btrim(region_code)) not between 2 and 40 then
        raise exception using errcode = 'P0001', message = 'INVALID_REGION';
    end if;

    select account.birth_year, account.status
      into v_existing_birth_year, v_existing_status
      from public.accounts account
     where account.id = v_user
     for update;

    if not found then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_FOUND';
    end if;
    if v_existing_status in ('suspended', 'deleted') then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_EDITABLE';
    end if;
    if v_existing_birth_year is not null and v_existing_birth_year <> birth_year then
        raise exception using errcode = 'P0001', message = 'BIRTH_YEAR_LOCKED';
    end if;

    update public.accounts account
       set status = 'active',
           birth_year = complete_onboarding.birth_year,
           terms_accepted_at = now(),
           terms_version = btrim(complete_onboarding.terms_version)
     where account.id = v_user;

    v_verified := private.account_has_document_verification(v_user);

    insert into public.profiles (
        id,
        display_name,
        age,
        bio,
        intent,
        region_code,
        discovery_visible,
        verified
    )
    values (
        v_user,
        btrim(display_name),
        v_age::smallint,
        btrim(bio),
        btrim(intent),
        btrim(region_code),
        true,
        v_verified
    )
    on conflict (id) do update
       set display_name = excluded.display_name,
           age = excluded.age,
           bio = excluded.bio,
           intent = excluded.intent,
           region_code = excluded.region_code,
           discovery_visible = true,
           verified = excluded.verified;

    insert into public.audit_events (
        event_type,
        actor_id,
        subject_user_id,
        metadata
    )
    values (
        'account.onboarding_completed',
        v_user,
        v_user,
        jsonb_build_object('terms_version', btrim(terms_version))
    );

    return query select v_user, 'active'::public.account_status, v_age::smallint;
end;
$$;

create or replace function public.finalize_age_verification(
    provider_session_id text,
    provider_state text,
    provider_method text default null,
    provider_check_type text default null
)
returns table (
    account_status public.account_status,
    verification_status public.age_verification_status
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_attempt public.age_verification_attempts%rowtype;
    v_account public.accounts%rowtype;
    v_attempt_status text;
    v_now timestamptz := clock_timestamp();
begin
    if auth.role() <> 'service_role' then
        raise exception using errcode = '42501', message = 'SERVICE_ROLE_REQUIRED';
    end if;
    if provider_session_id is null or char_length(provider_session_id) not between 1 and 100 then
        raise exception using errcode = 'P0001', message = 'INVALID_AGE_SESSION';
    end if;
    if provider_state not in ('PENDING', 'IN_PROGRESS', 'PROCESSING', 'COMPLETE', 'FAIL', 'ERROR', 'CANCELLED', 'EXPIRED') then
        raise exception using errcode = 'P0001', message = 'INVALID_PROVIDER_STATE';
    end if;
    if provider_method is not null and provider_method <> 'DOCUMENT' then
        raise exception using errcode = 'P0001', message = 'INVALID_PROVIDER_METHOD';
    end if;
    if provider_check_type is not null and provider_check_type not in ('ACTIVE', 'PASSIVE', 'NONE') then
        raise exception using errcode = 'P0001', message = 'INVALID_PROVIDER_CHECK_TYPE';
    end if;

    select attempt.*
      into v_attempt
      from public.age_verification_attempts attempt
     where attempt.provider_session_id = finalize_age_verification.provider_session_id
     for update;

    if not found then
        raise exception using errcode = 'P0001', message = 'AGE_SESSION_NOT_FOUND';
    end if;

    select account.*
      into v_account
      from public.accounts account
     where account.id = v_attempt.user_id
     for update;

    if not found then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_FOUND';
    end if;

    if provider_state = 'COMPLETE'
       and v_account.status not in ('suspended', 'deleted')
       and (
           v_account.birth_year is null
           or v_account.terms_accepted_at is null
           or v_account.birth_year > extract(year from current_date)::integer - 18
           or not exists (
               select 1 from public.profiles profile where profile.id = v_attempt.user_id
           )
       ) then
        raise exception using errcode = 'P0001', message = 'ONBOARDING_REQUIRED';
    end if;

    v_attempt_status := case provider_state
        when 'PENDING' then 'pending'
        when 'IN_PROGRESS' then 'processing'
        when 'PROCESSING' then 'processing'
        when 'COMPLETE' then 'verified'
        when 'FAIL' then 'failed'
        when 'ERROR' then 'error'
        when 'CANCELLED' then 'cancelled'
        when 'EXPIRED' then 'expired'
    end;

    update public.age_verification_attempts attempt
       set status = case
               when attempt.status = 'verified' then 'verified'
               else v_attempt_status
           end,
           method = case
               when attempt.status = 'verified' then attempt.method
               else coalesce(provider_method, attempt.method)
           end,
           check_type = case
               when attempt.status = 'verified' then attempt.check_type
               else coalesce(provider_check_type, attempt.check_type)
           end,
           completed_at = case
               when provider_state in ('COMPLETE', 'FAIL', 'ERROR', 'CANCELLED', 'EXPIRED')
                   then coalesce(attempt.completed_at, v_now)
               else attempt.completed_at
           end
     where attempt.id = v_attempt.id;

    if provider_state = 'COMPLETE' then
        if provider_method is null then
            raise exception using errcode = 'P0001', message = 'PROVIDER_METHOD_REQUIRED';
        end if;

        -- Verification is a badge transition only. Moderation state remains
        -- authoritative and a callback never changes account visibility.
        if v_account.status not in ('suspended', 'deleted') then
            update public.accounts account
               set age_verification_status = 'verified',
                   age_verification_method = lower(provider_method),
                   age_verification_policy_version = v_attempt.policy_version,
                   adult_verified_at = coalesce(account.adult_verified_at, v_now)
             where account.id = v_attempt.user_id;

            update public.profiles profile
               set verified = true
             where profile.id = v_attempt.user_id;

            if not exists (
                select 1
                from public.audit_events event
                where event.event_type = 'account.age_verified'
                  and event.subject_user_id = v_attempt.user_id
            ) then
                insert into public.audit_events (
                    event_type,
                    actor_id,
                    subject_user_id,
                    metadata
                )
                values (
                    'account.age_verified',
                    null,
                    v_attempt.user_id,
                    jsonb_build_object(
                        'method', lower(provider_method),
                        'policy_version', v_attempt.policy_version
                    )
                );
            end if;
        end if;
    elsif v_account.status not in ('suspended', 'deleted')
      and v_account.age_verification_status <> 'verified' then
        update public.accounts account
           set age_verification_status = case
                   when provider_state in ('PENDING', 'IN_PROGRESS', 'PROCESSING')
                       then 'pending'::public.age_verification_status
                   else 'failed'::public.age_verification_status
               end,
               adult_verified_at = null,
               age_verification_method = null,
               age_verification_policy_version = null
         where account.id = v_attempt.user_id;

        update public.profiles profile
           set verified = false
         where profile.id = v_attempt.user_id;
    end if;

    return query
    select account.status, account.age_verification_status
    from public.accounts account
    where account.id = v_attempt.user_id;
end;
$$;

-- Private profile photos are immutable objects. A new UUID path is moderated
-- before it becomes visible; deleting the current object clears its approval.
create type public.profile_photo_moderation_status as enum (
    'none',
    'pending',
    'approved',
    'blocked_adult',
    'blocked_abusive'
);

alter table public.profiles
    add column avatar_path text,
    add constraint profiles_avatar_path_length check (
        avatar_path is null or char_length(avatar_path) between 40 and 100
    ),
    add constraint profiles_avatar_path_owned check (
        avatar_path is null
        or avatar_path ~ (
            '^' || id::text ||
            '/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\.(jpg|jpeg|png|webp)$'
        )
    );

comment on column public.profiles.avatar_path is
    'Last approved immutable profile-photo object name. Candidate paths are private.';

create table private.profile_photo_submissions (
    user_id uuid primary key references public.accounts (id) on delete cascade,
    candidate_path text,
    status public.profile_photo_moderation_status not null default 'none',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint profile_photo_submission_path_length check (
        candidate_path is null or char_length(candidate_path) between 40 and 100
    ),
    constraint profile_photo_submission_path_owned check (
        candidate_path is null
        or candidate_path ~ (
            '^' || user_id::text ||
            '/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\.(jpg|jpeg|png|webp)$'
        )
    ),
    constraint profile_photo_submission_state check (
        (candidate_path is null and status = 'none')
        or (candidate_path is not null and status <> 'none')
    )
);

comment on table private.profile_photo_submissions is
    'Owner-private current candidate path and moderation state; never exposed through profiles.';

create trigger profile_photo_submissions_touch_updated_at
before update on private.profile_photo_submissions
for each row execute function private.touch_updated_at();

alter table private.profile_photo_submissions enable row level security;
revoke all on table private.profile_photo_submissions from anon, authenticated;

insert into storage.buckets (
    id,
    name,
    public,
    file_size_limit,
    allowed_mime_types
)
values (
    'profile-photos',
    'profile-photos',
    false,
    5242880,
    array['image/jpeg', 'image/png', 'image/webp']::text[]
)
on conflict (id) do update
   set public = excluded.public,
       file_size_limit = excluded.file_size_limit,
       allowed_mime_types = excluded.allowed_mime_types;

create or replace function private.profile_photo_path_is_valid(
    user_id uuid,
    object_path text
)
returns boolean
language sql
immutable
set search_path = ''
as $$
    select object_path ~ (
        '^' || user_id::text ||
        '/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\.(jpg|jpeg|png|webp)$'
    );
$$;

create or replace function private.profile_photo_metadata_is_safe(metadata jsonb)
returns boolean
language sql
immutable
set search_path = ''
as $$
    select metadata is not null
       and lower(coalesce(metadata ->> 'mimetype', '')) in (
           'image/jpeg', 'image/png', 'image/webp'
       )
       and case
           when coalesce(metadata ->> 'size', '') ~ '^[0-9]{1,10}$'
               then (metadata ->> 'size')::bigint between 1 and 5242880
           else false
       end;
$$;

create or replace function private.can_read_profile_photo(
    object_path text,
    viewer_id uuid
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select viewer_id is not null and exists (
        select 1
        from public.profiles profile
        where profile.avatar_path = can_read_profile_photo.object_path
          and profile.discovery_visible
          and private.account_is_active(profile.id)
          and private.account_is_active(can_read_profile_photo.viewer_id)
          and not private.is_blocked_pair(
              can_read_profile_photo.viewer_id,
              profile.id
          )
    );
$$;

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
               status = 'none'
         where submission.candidate_path = old.name;
    end if;
    return old;
end;
$$;

create trigger storage_clear_deleted_current_profile_photo
after delete on storage.objects
for each row execute function private.clear_deleted_current_profile_photo();

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
    if object_path is null
       or not private.profile_photo_path_is_valid(v_user, object_path) then
        raise exception using errcode = 'P0001', message = 'INVALID_PROFILE_PHOTO_PATH';
    end if;

    select object.*
      into v_object
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

    if not exists (
        select 1 from public.profiles profile where profile.id = v_user
    ) then
        raise exception using errcode = 'P0001', message = 'PROFILE_NOT_FOUND';
    end if;

    return query
    insert into private.profile_photo_submissions (
        user_id,
        candidate_path,
        status
    )
    values (
        v_user,
        submit_profile_photo.object_path,
        'pending'
    )
    on conflict (user_id) do update
       set candidate_path = excluded.candidate_path,
           status = 'pending'
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
    if decision is null
       or decision not in ('approved', 'blocked_adult', 'blocked_abusive') then
        raise exception using errcode = 'P0001', message = 'INVALID_PROFILE_PHOTO_DECISION';
    end if;

    perform 1
      from private.profile_photo_submissions submission
     where submission.user_id = moderate_profile_photo.profile_id
       and submission.candidate_path = moderate_profile_photo.object_path
       and submission.status = 'pending'
     for update;

    if not found or not exists (
        select 1
        from storage.objects object
        where object.bucket_id = 'profile-photos'
          and object.name = moderate_profile_photo.object_path
          and coalesce(object.owner_id, object.owner::text) =
              moderate_profile_photo.profile_id::text
    ) then
        raise exception using errcode = 'P0001', message = 'PROFILE_PHOTO_NOT_CURRENT';
    end if;

    update private.profile_photo_submissions submission
       set status = moderate_profile_photo.decision
     where submission.user_id = moderate_profile_photo.profile_id;

    if moderate_profile_photo.decision = 'approved' then
        update public.profiles profile
           set avatar_path = moderate_profile_photo.object_path
         where profile.id = moderate_profile_photo.profile_id;

        if not found then
            raise exception using errcode = 'P0001', message = 'PROFILE_NOT_FOUND';
        end if;
    end if;

    return query
    select moderate_profile_photo.object_path, moderate_profile_photo.decision;
end;
$$;

create or replace function public.get_my_profile_photo_state()
returns table (
    candidate_path text,
    moderation_status public.profile_photo_moderation_status,
    approved_path text
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;

    return query
    select
        submission.candidate_path,
        coalesce(
            submission.status,
            'none'::public.profile_photo_moderation_status
        ),
        profile.avatar_path
    from public.profiles profile
    left join private.profile_photo_submissions submission
      on submission.user_id = profile.id
    where profile.id = v_user;
end;
$$;

create policy matcher_profile_photos_select
on storage.objects for select
to authenticated
using (
    bucket_id = 'profile-photos'
    and (
        coalesce(owner_id, owner::text) = (select auth.uid())::text
        or private.can_read_profile_photo(name, (select auth.uid()))
    )
);

create policy matcher_profile_photos_insert
on storage.objects for insert
to authenticated
with check (
    bucket_id = 'profile-photos'
    and coalesce(owner_id, owner::text) = (select auth.uid())::text
    and private.profile_photo_path_is_valid((select auth.uid()), name)
    and private.profile_photo_metadata_is_safe(metadata)
);

create policy matcher_profile_photos_delete
on storage.objects for delete
to authenticated
using (
    bucket_id = 'profile-photos'
    and coalesce(owner_id, owner::text) = (select auth.uid())::text
    and private.profile_photo_path_is_valid((select auth.uid()), name)
);

revoke all on function public.complete_onboarding(integer, text, text, text, boolean, text, text) from public;
grant execute on function public.complete_onboarding(integer, text, text, text, boolean, text, text) to authenticated;

revoke all on function public.finalize_age_verification(text, text, text, text) from public;
grant execute on function public.finalize_age_verification(text, text, text, text) to service_role;

revoke all on function public.submit_profile_photo(text) from public;
grant execute on function public.submit_profile_photo(text) to authenticated;

revoke all on function public.moderate_profile_photo(uuid, text, public.profile_photo_moderation_status) from public;
grant execute on function public.moderate_profile_photo(uuid, text, public.profile_photo_moderation_status) to service_role;

revoke all on function public.get_my_profile_photo_state() from public;
grant execute on function public.get_my_profile_photo_state() to authenticated;

revoke all on function private.account_has_document_verification(uuid) from public;
revoke all on function private.profile_photo_path_is_valid(uuid, text) from public;
revoke all on function private.profile_photo_metadata_is_safe(jsonb) from public;
revoke all on function private.can_read_profile_photo(text, uuid) from public;

grant execute on function private.profile_photo_path_is_valid(uuid, text) to authenticated;
grant execute on function private.profile_photo_metadata_is_safe(jsonb) to authenticated;
grant execute on function private.can_read_profile_photo(text, uuid) to authenticated;
