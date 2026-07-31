create type public.age_verification_status as enum (
    'not_started',
    'pending',
    'verified',
    'failed',
    'manual_review'
);

alter table public.accounts
    add column age_verification_status public.age_verification_status not null default 'not_started',
    add column age_verification_method text,
    add column age_verification_policy_version text;

alter table public.accounts
    drop constraint accounts_active_state;

update public.accounts
   set status = 'pending',
       adult_verified_at = null,
       age_verification_status = 'not_started',
       age_verification_method = null,
       age_verification_policy_version = null
 where status = 'active';

update public.profiles
   set discovery_visible = false;

alter table public.accounts
    add constraint accounts_age_verification_method_length check (
        age_verification_method is null
        or char_length(age_verification_method) between 1 and 40
    ),
    add constraint accounts_age_policy_version_length check (
        age_verification_policy_version is null
        or char_length(age_verification_policy_version) between 1 and 40
    ),
    add constraint accounts_active_state check (
        status <> 'active'
        or (
            birth_year is not null
            and adult_verified_at is not null
            and terms_accepted_at is not null
            and terms_version is not null
            and char_length(btrim(terms_version)) between 1 and 40
            and age_verification_status = 'verified'
            and age_verification_method is not null
            and age_verification_policy_version is not null
        )
    );

create table public.age_verification_attempts (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.accounts (id) on delete cascade,
    provider text not null default 'didit' check (provider = 'didit'),
    provider_subject_reference uuid not null default gen_random_uuid(),
    provider_reference uuid not null default gen_random_uuid() unique,
    provider_session_id text unique,
    provider_workflow_id uuid not null,
    provider_workflow_version integer not null check (provider_workflow_version > 0),
    status text not null default 'creating' check (
        status in ('creating', 'pending', 'processing', 'verified', 'failed', 'error', 'cancelled', 'expired')
    ),
    method text,
    check_type text,
    minimum_age smallint not null default 18 check (minimum_age = 18),
    challenge_age smallint not null default 18 check (challenge_age = 18),
    policy_version text not null check (char_length(policy_version) between 1 and 40),
    expires_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint age_verification_provider_session_length check (
        provider_session_id is null
        or char_length(provider_session_id) between 1 and 100
    ),
    constraint age_verification_method_allowed check (
        method is null or method = 'DOCUMENT'
    ),
    constraint age_verification_check_type_length check (
        check_type is null or char_length(check_type) between 1 and 20
    )
);

comment on table public.age_verification_attempts is
    'Opaque age-assurance state only. Selfies, documents, exact birth dates, estimated ages and biometric templates are forbidden.';

comment on column public.age_verification_attempts.challenge_age is
    'Legacy-compatible policy threshold fixed at 18 for Didit document verification.';

comment on column public.age_verification_attempts.provider_subject_reference is
    'Stable opaque per-user pseudonym sent as Didit vendor_data; never a Supabase user id or other PII.';

comment on column public.age_verification_attempts.provider_reference is
    'Unique opaque reference for one local verification attempt; it is not sent as Didit vendor_data.';

comment on column public.profiles.verified is
    'Profile/photo authenticity marker. It is not proof of age.';

create index age_verification_attempts_user_recent_idx
    on public.age_verification_attempts (user_id, created_at desc);

create unique index age_verification_attempts_one_open_idx
    on public.age_verification_attempts (user_id)
    where status in ('creating', 'pending', 'processing');

create trigger age_verification_attempts_touch_updated_at
before update on public.age_verification_attempts
for each row execute function private.touch_updated_at();

alter table public.age_verification_attempts enable row level security;
revoke all on table public.age_verification_attempts from anon, authenticated;
grant select, insert, update on table public.age_verification_attempts to service_role;

create or replace function private.account_is_active(user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.accounts a
        where a.id = user_id
          and a.status = 'active'
          and a.age_verification_status = 'verified'
          and a.adult_verified_at is not null
          and a.birth_year <= extract(year from current_date)::integer - 18
          and exists (
              select 1
              from public.age_verification_attempts attempt
              where attempt.user_id = a.id
                and attempt.status = 'verified'
          )
    );
$$;

create or replace function private.can_access_conversation(conversation_id uuid, user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.conversations c
        where c.id = can_access_conversation.conversation_id
          and c.status = 'active'
          and can_access_conversation.user_id in (c.participant_a, c.participant_b)
          and private.account_is_active(can_access_conversation.user_id)
          and private.account_is_active(c.participant_a)
          and private.account_is_active(c.participant_b)
          and not private.is_blocked_pair(c.participant_a, c.participant_b)
    );
$$;

create or replace function public.get_age_verification_status()
returns table (
    account_status public.account_status,
    verification_status public.age_verification_status,
    onboarding_complete boolean,
    verification_method text,
    verified_at timestamptz
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
        a.status,
        a.age_verification_status,
        exists (select 1 from public.profiles p where p.id = a.id),
        a.age_verification_method,
        a.adult_verified_at
    from public.accounts a
    where a.id = v_user;
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
       and v_account.status = 'pending'
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

    update public.age_verification_attempts
       set status = case
               when v_attempt.status = 'verified' then 'verified'
               else v_attempt_status
           end,
           method = case
               when v_attempt.status = 'verified' then method
               else coalesce(provider_method, method)
           end,
           check_type = case
               when v_attempt.status = 'verified' then check_type
               else coalesce(provider_check_type, check_type)
           end,
           completed_at = case
               when provider_state in ('COMPLETE', 'FAIL', 'ERROR', 'CANCELLED', 'EXPIRED') then coalesce(completed_at, v_now)
               else completed_at
           end
     where id = v_attempt.id;

    if provider_state = 'COMPLETE' then
        if provider_method is null then
            raise exception using errcode = 'P0001', message = 'PROVIDER_METHOD_REQUIRED';
        end if;

        -- A delayed/replayed provider result is evidence, never authority to
        -- undo a moderation or deletion decision.
        if v_account.status = 'pending'
           and v_account.age_verification_status <> 'manual_review' then
            update public.accounts
               set status = 'active',
                   age_verification_status = 'verified',
                   age_verification_method = lower(provider_method),
                   age_verification_policy_version = v_attempt.policy_version,
                   adult_verified_at = coalesce(adult_verified_at, v_now)
             where id = v_attempt.user_id;

            update public.profiles
               set discovery_visible = true
             where id = v_attempt.user_id;

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
    elsif not exists (
        select 1
        from public.accounts account
        where account.id = v_attempt.user_id
          and account.age_verification_status = 'verified'
    ) then
        update public.accounts
           set status = 'pending',
               age_verification_status = case
                   when provider_state in ('PENDING', 'IN_PROGRESS', 'PROCESSING') then 'pending'::public.age_verification_status
                   else 'failed'::public.age_verification_status
               end,
               adult_verified_at = null,
               age_verification_method = null,
               age_verification_policy_version = null
         where id = v_attempt.user_id
           and status = 'pending';

        update public.profiles
           set discovery_visible = false
         where id = v_attempt.user_id
           and exists (
               select 1
               from public.accounts account
               where account.id = v_attempt.user_id
                 and account.status = 'pending'
           );
    end if;

    return query
    select a.status, a.age_verification_status
    from public.accounts a
    where a.id = v_attempt.user_id;
end;
$$;

create or replace function public.get_chat_quota()
returns table (
    limit_count integer,
    used_count integer,
    remaining_count integer,
    next_reset_at timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_limit integer;
    v_used integer;
    v_oldest timestamptz;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if not private.account_is_active(v_user) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE';
    end if;

    v_limit := private.opening_limit(v_user);
    select count(*)::integer, min(o.opened_at)
      into v_used, v_oldest
      from public.conversation_openings o
     where o.opened_by = v_user
       and o.opened_at > now() - interval '24 hours';

    return query select
        v_limit,
        v_used,
        greatest(v_limit - v_used, 0),
        case when v_oldest is null then null else v_oldest + interval '24 hours' end;
end;
$$;

create or replace function public.send_message(
    conversation_id uuid,
    message_body text
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_sender uuid := auth.uid();
    v_message_id uuid;
    v_now timestamptz := clock_timestamp();
begin
    if v_sender is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if not private.account_is_active(v_sender) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE';
    end if;
    if message_body is null
       or char_length(btrim(message_body)) = 0
       or char_length(btrim(message_body)) > 2000 then
        raise exception using errcode = 'P0001', message = 'INVALID_MESSAGE';
    end if;

    perform 1
      from public.conversations c
     where c.id = conversation_id
       and private.can_access_conversation(c.id, v_sender)
     for update;

    if not found then
        raise exception using errcode = 'P0001', message = 'CHAT_NOT_AVAILABLE';
    end if;

    insert into public.messages (conversation_id, sender_id, body, created_at)
    values (conversation_id, v_sender, btrim(message_body), v_now)
    returning id into v_message_id;

    update public.conversations
       set last_message_at = v_now
     where id = conversation_id;

    return v_message_id;
end;
$$;

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
    v_verification_status public.age_verification_status;
    v_existing_status public.account_status;
    v_result_status public.account_status;
    v_age integer;
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

    select a.birth_year, a.age_verification_status, a.status
      into v_existing_birth_year, v_verification_status, v_existing_status
      from public.accounts a
     where a.id = v_user
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

    v_result_status := case
        when v_verification_status = 'verified' then 'active'::public.account_status
        else 'pending'::public.account_status
    end;

    update public.accounts
       set status = v_result_status,
           birth_year = complete_onboarding.birth_year,
           terms_accepted_at = now(),
           terms_version = btrim(complete_onboarding.terms_version)
     where id = v_user;

    insert into public.profiles (
        id,
        display_name,
        age,
        bio,
        intent,
        region_code,
        discovery_visible
    )
    values (
        v_user,
        btrim(display_name),
        v_age::smallint,
        btrim(bio),
        btrim(intent),
        btrim(region_code),
        v_verification_status = 'verified'
    )
    on conflict (id) do update
       set display_name = excluded.display_name,
           age = excluded.age,
           bio = excluded.bio,
           intent = excluded.intent,
           region_code = excluded.region_code,
           discovery_visible = excluded.discovery_visible;

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

    return query select v_user, v_result_status, v_age::smallint;
end;
$$;

revoke all on function public.get_age_verification_status() from public;
revoke all on function public.finalize_age_verification(text, text, text, text) from public;
grant execute on function public.get_age_verification_status() to authenticated;
grant execute on function public.finalize_age_verification(text, text, text, text) to service_role;
