begin;

set local role postgres;
set local search_path = public, testing, extensions;
select plan(16);

grant usage on schema testing to anon, authenticated, service_role;

select has_table('private', 'moderation_staff', 'moderator allowlist is private');
select ok(
    not has_table_privilege('authenticated', 'private.moderation_staff', 'SELECT'),
    'application users cannot inspect staff membership'
);
select has_function(
    'public',
    'list_profile_photo_review_queue',
    array['integer', 'timestamp with time zone', 'uuid'],
    'staff review queue RPC exists'
);
select has_function(
    'public',
    'decide_profile_photo_review',
    array['uuid', 'text', 'public.profile_photo_moderation_status'],
    'staff decision RPC exists'
);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000901', 'review-owner@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000902', 'review-staff@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000903', 'review-user@matcher.invalid', now(), '{}'::jsonb);

update public.accounts
set status = 'active', birth_year = 1995, terms_accepted_at = now(), terms_version = 'test-v1'
where id in (
    '00000000-0000-0000-0000-000000000901'::uuid,
    '00000000-0000-0000-0000-000000000902'::uuid,
    '00000000-0000-0000-0000-000000000903'::uuid
);

insert into public.profiles (id, display_name, age, bio, intent, region_code, avatar_path)
values
    (
        '00000000-0000-0000-0000-000000000901',
        'Review owner', 31, '', 'Conhecer pessoas', 'br-test',
        '00000000-0000-0000-0000-000000000901/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1.jpg'
    ),
    ('00000000-0000-0000-0000-000000000902', 'Review staff', 30, '', 'Conhecer pessoas', 'br-test', null),
    ('00000000-0000-0000-0000-000000000903', 'Review user', 29, '', 'Conhecer pessoas', 'br-test', null);

insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
values (
    'profile-photos',
    '00000000-0000-0000-0000-000000000901/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.jpg',
    '00000000-0000-0000-0000-000000000901',
    '00000000-0000-0000-0000-000000000901',
    '{"size":1024,"mimetype":"image/jpeg"}'::jsonb
);

insert into private.profile_photo_submissions (
    user_id, candidate_path, status, automation_state
) values (
    '00000000-0000-0000-0000-000000000901',
    '00000000-0000-0000-0000-000000000901/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.jpg',
    'pending',
    'review'
);

insert into private.moderation_staff (user_id, staff_role)
values ('00000000-0000-0000-0000-000000000902', 'reviewer');

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000903';
set local "request.jwt.claim.role" = 'authenticated';

select throws_ok(
    $$select * from public.list_profile_photo_review_queue()$$,
    '42501',
    'MODERATOR_REQUIRED',
    'ordinary account cannot list private review candidates'
);
select throws_ok(
    $$select public.decide_profile_photo_review(
        '00000000-0000-0000-0000-000000000901',
        '00000000-0000-0000-0000-000000000901/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.jpg',
        'approved'
    )$$,
    '42501',
    'MODERATOR_REQUIRED',
    'ordinary account cannot decide private review candidates'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000902';

select results_eq(
    $$select profile_id, display_name, candidate_path, has_approved_photo
      from public.list_profile_photo_review_queue()$$,
    $$values (
        '00000000-0000-0000-0000-000000000901'::uuid,
        'Review owner'::text,
        '00000000-0000-0000-0000-000000000901/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.jpg'::text,
        true
    )$$,
    'active moderator sees only the current review candidate'
);

select throws_ok(
    $$select public.decide_profile_photo_review(
        '00000000-0000-0000-0000-000000000901',
        '00000000-0000-0000-0000-000000000901/cccccccc-cccc-4ccc-8ccc-ccccccccccc1.jpg',
        'approved'
    )$$,
    'P0001',
    'PROFILE_PHOTO_REVIEW_STALE',
    'stale decision cannot affect another candidate'
);

set local role postgres;
select is(
    (select status::text from private.profile_photo_submissions
      where user_id = '00000000-0000-0000-0000-000000000901'),
    'pending',
    'stale decision leaves current candidate pending'
);
select is(
    (select count(*) from public.audit_events
      where event_type = 'profile_photo.review_decided'),
    0::bigint,
    'stale decision creates no audit event'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000902';
set local "request.jwt.claim.role" = 'authenticated';
select is(
    public.decide_profile_photo_review(
        '00000000-0000-0000-0000-000000000901',
        '00000000-0000-0000-0000-000000000901/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.jpg',
        'blocked_adult'
    )::text,
    'blocked_adult',
    'moderator can apply a normalized human decision'
);

set local role postgres;
select results_eq(
    $$select status::text, automation_state
      from private.profile_photo_submissions
      where user_id = '00000000-0000-0000-0000-000000000901'::uuid$$,
    $$values ('blocked_adult'::text, 'completed'::text)$$,
    'human decision completes automation and keeps candidate private'
);
select is(
    (select avatar_path from public.profiles
      where id = '00000000-0000-0000-0000-000000000901'),
    '00000000-0000-0000-0000-000000000901/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1.jpg',
    'blocked replacement preserves the previously approved photo'
);
select results_eq(
    $$select actor_id, subject_user_id, metadata
      from public.audit_events
      where event_type = 'profile_photo.review_decided'$$,
    $$values (
        '00000000-0000-0000-0000-000000000902'::uuid,
        '00000000-0000-0000-0000-000000000901'::uuid,
        '{"decision":"blocked_adult"}'::jsonb
    )$$,
    'audit records actor and normalized decision without media data'
);
select is(
    (select count(*) from public.list_profile_photo_review_queue()),
    0::bigint,
    'decided candidate leaves the review queue'
);

update private.moderation_staff
set active = false
where user_id = '00000000-0000-0000-0000-000000000902';
set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000902';
select throws_ok(
    $$select * from public.list_profile_photo_review_queue()$$,
    '42501',
    'MODERATOR_REQUIRED',
    'deactivated moderator loses queue access immediately'
);

select * from finish();
rollback;
