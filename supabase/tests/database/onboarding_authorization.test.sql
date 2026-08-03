begin;

set local role postgres;

set local search_path = public, testing, extensions;
select plan(51);

-- Test-only access is transaction-scoped and rolled back at the end.
grant usage on schema testing to anon, authenticated, service_role;

select has_column('public', 'accounts', 'terms_accepted_at', 'terms acceptance timestamp exists');
select has_column('public', 'accounts', 'terms_version', 'terms version exists');
select has_column('public', 'accounts', 'birth_year', 'accounts store only birth year');
select hasnt_column('public', 'accounts', 'birth_date', 'accounts do not store full birth date');
select has_column('public', 'accounts', 'age_verification_status', 'account stores server age gate status');
select has_table('public', 'age_verification_attempts', 'opaque age verification attempts exist');
select has_column('public', 'age_verification_attempts', 'provider_subject_reference', 'attempt stores the stable opaque Didit subject pseudonym');
select has_column('public', 'age_verification_attempts', 'provider_workflow_id', 'attempt is bound to a provider workflow');
select has_column('public', 'age_verification_attempts', 'provider_workflow_version', 'attempt is bound to a positive provider workflow version');
select hasnt_column('public', 'age_verification_attempts', 'selfie', 'attempts never store selfie');
select hasnt_column('public', 'age_verification_attempts', 'document', 'attempts never store document');
select hasnt_column('public', 'age_verification_attempts', 'estimated_age', 'attempts never store estimated age');
select hasnt_column('public', 'age_verification_attempts', 'biometric_template', 'attempts never store biometric templates');
select hasnt_column('public', 'age_verification_attempts', 'raw_payload', 'attempts never store provider payloads');

select ok(
    has_table_privilege('service_role', 'public.accounts', 'SELECT')
    and has_table_privilege('service_role', 'public.accounts', 'UPDATE'),
    'age Edge Functions can read and update account gate state'
);

select ok(
    has_table_privilege('service_role', 'public.profiles', 'SELECT'),
    'age Edge Functions can confirm onboarding without profile write access'
);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000301', 'onboarding@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000302', 'visible-target@matcher.invalid', now(), '{}'::jsonb);

update public.accounts
set status = 'active',
    birth_year = 1994,
    adult_verified_at = now(),
    age_verification_status = 'verified',
    age_verification_method = 'test_fixture',
    age_verification_policy_version = 'test-v1',
    terms_accepted_at = now(),
    terms_version = 'dev-2026-07'
where id = '00000000-0000-0000-0000-000000000302';

insert into public.age_verification_attempts (
    user_id,
    provider_session_id,
    provider_workflow_id,
    provider_workflow_version,
    status,
    method,
    check_type,
    policy_version,
    completed_at
)
values (
    '00000000-0000-0000-0000-000000000302',
    '20000000-0000-4000-8000-000000000302',
    '30000000-0000-4000-8000-000000000001',
    1,
    'verified',
    'DOCUMENT',
    'PASSIVE',
    'test-v1',
    now()
);

select results_eq(
    $$select provider, provider_workflow_id, provider_workflow_version,
              method, minimum_age, challenge_age
        from public.age_verification_attempts
        where provider_session_id = '20000000-0000-4000-8000-000000000302'$$,
    $$values (
        'didit'::text,
        '30000000-0000-4000-8000-000000000001'::uuid,
        1::integer,
        'DOCUMENT'::text,
        18::smallint,
        18::smallint
    )$$,
    'attempt contract stores only Didit document verification at the 18+ threshold'
);

insert into public.profiles (id, display_name, age, bio, intent, region_code)
values (
    '00000000-0000-0000-0000-000000000302',
    'Visible target',
    32,
    '',
    'Conhecer pessoas',
    'br-test'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000301';
set local "request.jwt.claim.role" = 'authenticated';

select throws_ok(
    'select count(*) from public.profiles',
    '42501',
    'permission denied for table profiles',
    'authenticated account has no direct profile table read'
);

select throws_ok(
    $$select * from public.complete_onboarding(
        extract(year from current_date)::integer - 17,
        'Minor fixture',
        'br-test',
        'dev-2026-07',
        true,
        array['prefer_not_to_say']::text[],
        null,
        false,
        array['everyone']::text[],
        '',
        'Conhecer pessoas'
    )$$,
    'P0001',
    'ADULTS_ONLY',
    'server rejects an underage declaration'
);

select throws_ok(
    $$select * from public.complete_onboarding(
        1995,
        'Adult fixture',
        'br-test',
        'dev-2026-07',
        false,
        array['prefer_not_to_say']::text[],
        null,
        false,
        array['everyone']::text[],
        '',
        'Conhecer pessoas'
    )$$,
    'P0001',
    'TERMS_REQUIRED',
    'server requires explicit terms acceptance'
);

select lives_ok(
    $$select * from public.complete_onboarding(
        1995,
        'Adult fixture',
        'br-test',
        'dev-2026-07',
        true,
        array['prefer_not_to_say']::text[],
        null,
        false,
        array['everyone']::text[],
        'Bio sintética',
        'Conhecer pessoas'
    )$$,
    'valid declared-adult onboarding succeeds'
);

select results_eq(
    $$select status::text, age_verification_status::text from public.accounts where id = auth.uid()$$,
    $$values ('active'::text, 'not_started'::text)$$,
    'declared-adult onboarding activates the account before document verification'
);

select results_eq(
    $$select display_name, verified from public.get_my_profile()$$,
    $$values ('Adult fixture'::text, false)$$,
    'owner RPC returns the unverified profile without a broad table grant'
);

set local role postgres;
select results_eq(
    $$select count(*)
        from public.accounts account
        join public.profiles profile on profile.id = account.id
        where account.status = 'pending'
          and account.birth_year <= extract(year from current_date)::integer - 18
          and account.terms_accepted_at is not null
          and account.terms_version is not null$$,
    array[0::bigint],
    'soft-gate migration leaves no recoverable complete adult profile pending'
);
set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000301';
set local "request.jwt.claim.role" = 'authenticated';

select results_eq(
    $$select account_status::text, verification_status::text, onboarding_complete
        from public.get_age_verification_status()$$,
    $$values ('active'::text, 'not_started'::text, true)$$,
    'status contract keeps account access separate from verification state'
);

select results_eq(
    $$select
        (select count(*) from public.get_my_profile())
        +
        (select count(*) from public.get_discovery_profiles(null, 20, null)
          where id = '00000000-0000-0000-0000-000000000302'::uuid)$$,
    array[2::bigint],
    'active unverified account reads self and discovery only through contextual RPCs'
);

select lives_ok(
    $$select * from public.get_chat_quota()$$,
    'active unverified account can access chat quota'
);

set local role postgres;

insert into public.age_verification_attempts (
    user_id,
    provider_reference,
    provider_session_id,
    provider_workflow_id,
    provider_workflow_version,
    status,
    policy_version,
    expires_at
)
values (
    '00000000-0000-0000-0000-000000000301',
    '10000000-0000-0000-0000-000000000301',
    '20000000-0000-4000-8000-000000000301',
    '30000000-0000-4000-8000-000000000001',
    1,
    'pending',
    'age-v1',
    now() + interval '15 minutes'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000301';
set local "request.jwt.claim.role" = 'authenticated';

select throws_ok(
    $$select * from public.finalize_age_verification(
        '20000000-0000-4000-8000-000000000301',
        'COMPLETE',
        'DOCUMENT',
        'PASSIVE'
    )$$,
    '42501',
    'permission denied for function finalize_age_verification',
    'authenticated client cannot forge a provider result'
);

set local role postgres;

set local role service_role;
set local "request.jwt.claim.role" = 'service_role';

select throws_ok(
    $$select * from public.finalize_age_verification(
        '20000000-0000-4000-8000-000000000301',
        'COMPLETE',
        'AGE_ESTIMATION',
        'PASSIVE'
    )$$,
    'P0001',
    'INVALID_PROVIDER_METHOD',
    'legacy apparent-age method cannot finalize a Didit document attempt'
);

select lives_ok(
    $$select * from public.finalize_age_verification(
        '20000000-0000-4000-8000-000000000301',
        'PROCESSING',
        null,
        null
    )$$,
    'provider review can start without disabling the active account'
);

set local role postgres;
select results_eq(
    $$select status::text, age_verification_status::text
        from public.accounts
        where id = '00000000-0000-0000-0000-000000000301'::uuid$$,
    $$values ('active'::text, 'pending'::text)$$,
    'review changes only verification state'
);

select results_eq(
    $$select discovery_visible, verified
        from public.profiles
        where id = '00000000-0000-0000-0000-000000000301'::uuid$$,
    $$values (true, false)$$,
    'review leaves discovery enabled without granting the badge'
);

set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
select lives_ok(
    $$select * from public.finalize_age_verification(
        '20000000-0000-4000-8000-000000000301',
        'FAIL',
        null,
        null
    )$$,
    'provider failure does not disable the active account'
);

set local role postgres;
select results_eq(
    $$select account.status::text, account.age_verification_status::text,
              profile.discovery_visible, profile.verified
        from public.accounts account
        join public.profiles profile on profile.id = account.id
        where account.id = '00000000-0000-0000-0000-000000000301'::uuid$$,
    $$values ('active'::text, 'failed'::text, true, false)$$,
    'failure leaves account and discovery active without a badge'
);

set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
select lives_ok(
    $$select * from public.finalize_age_verification(
        '20000000-0000-4000-8000-000000000301',
        'CANCELLED',
        null,
        null
    )$$,
    'provider cancellation also leaves access unchanged'
);

set local role postgres;
select results_eq(
    $$select account.status::text, account.age_verification_status::text,
              profile.discovery_visible, profile.verified
        from public.accounts account
        join public.profiles profile on profile.id = account.id
        where account.id = '00000000-0000-0000-0000-000000000301'::uuid$$,
    $$values ('active'::text, 'failed'::text, true, false)$$,
    'cancellation never deactivates or hides the unverified profile'
);

set local role service_role;
set local "request.jwt.claim.role" = 'service_role';

select lives_ok(
    $$select * from public.finalize_age_verification(
        '20000000-0000-4000-8000-000000000301',
        'COMPLETE',
        'DOCUMENT',
        'PASSIVE'
    )$$,
    'service callback can finalize a known provider session'
);

select lives_ok(
    $$select * from public.finalize_age_verification(
        '20000000-0000-4000-8000-000000000301',
        'COMPLETE',
        'DOCUMENT',
        'PASSIVE'
    )$$,
    'duplicate provider completion is idempotent'
);

select lives_ok(
    $$select * from public.finalize_age_verification(
        '20000000-0000-4000-8000-000000000301',
        'FAIL',
        'DOCUMENT',
        'PASSIVE'
    )$$,
    'a late failure notification is accepted idempotently'
);

set local role postgres;
set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000301';
set local "request.jwt.claim.role" = 'authenticated';

select results_eq(
    $$select status::text, age_verification_status::text, age_verification_method
        from public.accounts where id = auth.uid()$$,
    $$values ('active'::text, 'verified'::text, 'document'::text)$$,
    'verified provider result grants evidence without changing active access'
);

select results_eq(
    $$select verified from public.get_my_profile()$$,
    $$values (true)$$,
    'owner RPC reflects the documentary badge'
);

select results_eq(
    $$select
        (select count(*) from public.get_my_profile())
        +
        (select count(*) from public.get_discovery_profiles(null, 20, null)
          where id = '00000000-0000-0000-0000-000000000302'::uuid)$$,
    array[2::bigint],
    'verified account still uses contextual owner and discovery RPCs'
);

set local role postgres;

select results_eq(
    $$select count(*) from public.audit_events
        where event_type = 'account.age_verified'
          and subject_user_id = '00000000-0000-0000-0000-000000000301'::uuid$$,
    array[1::bigint],
    'duplicate completion creates one minimal audit event'
);

select results_eq(
    $$select status from public.age_verification_attempts
        where provider_session_id = '20000000-0000-4000-8000-000000000301'$$,
    $$values ('verified'::text)$$,
    'late failure cannot downgrade a verified attempt'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000301';
set local "request.jwt.claim.role" = 'authenticated';

select throws_ok(
    $$select * from public.complete_onboarding(
        1996,
        'Adult fixture',
        'br-test',
        'dev-2026-07',
        true,
        array['prefer_not_to_say']::text[],
        null,
        false,
        array['everyone']::text[],
        '',
        'Conhecer pessoas'
    )$$,
    'P0001',
    'BIRTH_YEAR_LOCKED',
    'birth year cannot be changed through repeated onboarding'
);

set local role postgres;
update public.accounts
   set status = 'active',
       age_verification_status = 'manual_review',
       adult_verified_at = null,
       age_verification_method = null,
       age_verification_policy_version = null
 where id = '00000000-0000-0000-0000-000000000301';
update public.profiles
   set discovery_visible = true,
       verified = false
 where id = '00000000-0000-0000-0000-000000000301';

set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
select lives_ok(
    $$select * from public.finalize_age_verification(
        '20000000-0000-4000-8000-000000000301',
        'COMPLETE',
        'DOCUMENT',
        'PASSIVE'
    )$$,
    'provider completion can resolve documentary manual review'
);

set local role postgres;
select results_eq(
    $$select status::text, age_verification_status::text
        from public.accounts
        where id = '00000000-0000-0000-0000-000000000301'::uuid$$,
    $$values ('active'::text, 'verified'::text)$$,
    'manual review resolution changes evidence without changing account access'
);

select results_eq(
    $$select discovery_visible, verified
        from public.profiles
        where id = '00000000-0000-0000-0000-000000000301'::uuid$$,
    $$values (true, true)$$,
    'manual review resolution grants the badge and keeps discovery visible'
);

update public.accounts
set status = 'suspended'
where id = '00000000-0000-0000-0000-000000000301';

set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
select lives_ok(
    $$select * from public.finalize_age_verification(
        '20000000-0000-4000-8000-000000000301',
        'COMPLETE',
        'DOCUMENT',
        'PASSIVE'
    )$$,
    'replayed completion does not fail for a suspended account'
);

set local role postgres;
set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000301';
set local "request.jwt.claim.role" = 'authenticated';

select results_eq(
    $$select status::text from public.accounts where id = auth.uid()$$,
    $$values ('suspended'::text)$$,
    'provider replay cannot reactivate a suspended account'
);

select throws_ok(
    $$select * from public.complete_onboarding(
        1995,
        'Adult fixture',
        'br-test',
        'dev-2026-07',
        true,
        array['prefer_not_to_say']::text[],
        null,
        false,
        array['everyone']::text[],
        '',
        'Conhecer pessoas'
    )$$,
    'P0001',
    'ACCOUNT_NOT_EDITABLE',
    'onboarding cannot reactivate a suspended account'
);

select * from finish();
rollback;
