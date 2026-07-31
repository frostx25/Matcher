begin;

set local search_path = public, extensions;
select plan(39);

select has_type(
    'public',
    'profile_photo_moderation_status',
    'profile photo moderation enum exists'
);
select has_column('public', 'profiles', 'avatar_path', 'profile stores current immutable object path');
select hasnt_column(
    'public',
    'profiles',
    'avatar_moderation_status',
    'profiles never expose candidate moderation state'
);
select has_table(
    'private',
    'profile_photo_submissions',
    'candidate path and moderation state live in a private table'
);

select results_eq(
    $$select enum.enumlabel
        from pg_catalog.pg_enum enum
        join pg_catalog.pg_type type on type.oid = enum.enumtypid
        join pg_catalog.pg_namespace namespace on namespace.oid = type.typnamespace
        where namespace.nspname = 'public'
          and type.typname = 'profile_photo_moderation_status'
        order by enum.enumsortorder$$,
    $$values
        ('none'::name),
        ('pending'::name),
        ('approved'::name),
        ('blocked_adult'::name),
        ('blocked_abusive'::name)$$,
    'moderation enum exposes only the approved workflow states'
);

select results_eq(
    $$select public, file_size_limit, allowed_mime_types
        from storage.buckets
        where id = 'profile-photos'$$,
    $$values (
        false,
        5242880::bigint,
        array['image/jpeg', 'image/png', 'image/webp']::text[]
    )$$,
    'profile photo bucket is private with a five-megabyte image allowlist'
);

select ok(
    has_function_privilege(
        'authenticated',
        'public.submit_profile_photo(text)',
        'EXECUTE'
    ),
    'authenticated users can submit an owned object for moderation'
);

select ok(
    not has_function_privilege(
        'authenticated',
        'public.moderate_profile_photo(uuid,text,public.profile_photo_moderation_status)',
        'EXECUTE'
    ),
    'authenticated users cannot moderate profile photos'
);

select ok(
    has_function_privilege(
        'authenticated',
        'public.get_my_profile_photo_state()',
        'EXECUTE'
    ),
    'authenticated users can read only their own candidate state through RPC'
);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000401', 'photo-owner@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000402', 'photo-viewer@matcher.invalid', now(), '{}'::jsonb);

update public.accounts
set status = 'active',
    birth_year = 1995,
    terms_accepted_at = now(),
    terms_version = 'test-v1'
where id in (
    '00000000-0000-0000-0000-000000000401'::uuid,
    '00000000-0000-0000-0000-000000000402'::uuid
);

insert into public.profiles (id, display_name, age, bio, intent, region_code)
values
    ('00000000-0000-0000-0000-000000000401', 'Photo owner', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000402', 'Photo viewer', 30, '', 'Conhecer pessoas', 'br-test');

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000401';
set local "request.jwt.claim.role" = 'authenticated';
set local storage.allow_delete_query = 'true';

select throws_ok(
    $$insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
      values (
          'profile-photos',
          '00000000-0000-0000-0000-000000000402/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1.jpg',
          auth.uid(),
          auth.uid()::text,
          '{"size":1024,"mimetype":"image/jpeg"}'::jsonb
      )$$,
    '42501',
    'new row violates row-level security policy for table "objects"',
    'owner cannot upload into another user folder'
);

select throws_ok(
    $$insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
      values (
          'profile-photos',
          '00000000-0000-0000-0000-000000000401/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2.jpg',
          auth.uid(),
          auth.uid()::text,
          '{"size":5242881,"mimetype":"image/jpeg"}'::jsonb
      )$$,
    '42501',
    'new row violates row-level security policy for table "objects"',
    'insert policy rejects files above five megabytes'
);

select throws_ok(
    $$insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
      values (
          'profile-photos',
          '00000000-0000-0000-0000-000000000401/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3.jpg',
          auth.uid(),
          auth.uid()::text,
          '{"size":1024,"mimetype":"image/gif"}'::jsonb
      )$$,
    '42501',
    'new row violates row-level security policy for table "objects"',
    'insert policy rejects MIME types outside jpeg, png and webp'
);

select lives_ok(
    $$insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
      values (
          'profile-photos',
          '00000000-0000-0000-0000-000000000401/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1.jpg',
          auth.uid(),
          auth.uid()::text,
          '{"size":1024,"mimetype":"image/jpeg"}'::jsonb
      )$$,
    'owner can insert one safe immutable object path'
);

select results_eq(
    $$with changed as (
          update storage.objects
             set name = '00000000-0000-0000-0000-000000000401/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.jpg'
           where bucket_id = 'profile-photos'
           returning 1
      ) select count(*) from changed$$,
    array[0::bigint],
    'absence of an update policy prevents move and upsert replacement'
);

select throws_ok(
    $$select * from public.submit_profile_photo(
        '00000000-0000-0000-0000-000000000401/cccccccc-cccc-4ccc-8ccc-ccccccccccc1.jpg'
    )$$,
    'P0001',
    'PROFILE_PHOTO_NOT_FOUND',
    'submission rejects a path without an owned object'
);

select lives_ok(
    $$select * from public.submit_profile_photo(
        '00000000-0000-0000-0000-000000000401/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1.jpg'
    )$$,
    'owner can submit the uploaded object for moderation'
);

select results_eq(
    $$select candidate_path, moderation_status::text, approved_path
        from public.get_my_profile_photo_state()$$,
    $$values (
        '00000000-0000-0000-0000-000000000401/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1.jpg'::text,
        'pending'::text,
        null::text
    )$$,
    'owner RPC exposes pending candidate while approved path remains empty'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000402';

select results_eq(
    $$select count(*) from storage.objects
        where bucket_id = 'profile-photos'$$,
    array[0::bigint],
    'third party cannot read a pending current photo'
);

select throws_ok(
    $$select candidate_path, status
        from private.profile_photo_submissions
        where user_id = '00000000-0000-0000-0000-000000000401'::uuid$$,
    '42501',
    'permission denied for table profile_photo_submissions',
    'candidate path and moderation state are not selectable outside the owner RPC'
);

select throws_ok(
    $$select * from public.moderate_profile_photo(
        '00000000-0000-0000-0000-000000000401',
        '00000000-0000-0000-0000-000000000401/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1.jpg',
        'approved'
    )$$,
    '42501',
    'permission denied for function moderate_profile_photo',
    'authenticated viewer cannot forge approval'
);

reset role;
set local role service_role;
set local "request.jwt.claim.role" = 'service_role';

select throws_ok(
    $$select * from public.moderate_profile_photo(
        '00000000-0000-0000-0000-000000000401',
        '00000000-0000-0000-0000-000000000401/dddddddd-dddd-4ddd-8ddd-ddddddddddd1.jpg',
        'approved'
    )$$,
    'P0001',
    'PROFILE_PHOTO_NOT_CURRENT',
    'moderator cannot decide a stale or different path'
);

select lives_ok(
    $$select * from public.moderate_profile_photo(
        '00000000-0000-0000-0000-000000000401',
        '00000000-0000-0000-0000-000000000401/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1.jpg',
        'approved'
    )$$,
    'service role can approve exactly the current pending photo'
);

reset role;
select results_eq(
    $$select submission.candidate_path, submission.status::text, profile.avatar_path
        from private.profile_photo_submissions submission
        join public.profiles profile on profile.id = submission.user_id
        where submission.user_id = '00000000-0000-0000-0000-000000000401'::uuid$$,
    $$values (
        '00000000-0000-0000-0000-000000000401/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1.jpg'::text,
        'approved'::text,
        '00000000-0000-0000-0000-000000000401/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1.jpg'::text
    )$$,
    'first approval atomically promotes candidate A to approved A'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000402';
set local "request.jwt.claim.role" = 'authenticated';

select results_eq(
    $$select avatar_path from public.profiles
        where id = '00000000-0000-0000-0000-000000000401'::uuid$$,
    $$values (
        '00000000-0000-0000-0000-000000000401/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1.jpg'::text
    )$$,
    'profile readers see only approved path A'
);

select results_eq(
    $$select name from storage.objects
        where bucket_id = 'profile-photos' order by name$$,
    $$values (
        '00000000-0000-0000-0000-000000000401/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1.jpg'::text
    )$$,
    'third party can fetch approved A'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000401';
select lives_ok(
    $$insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
      values (
          'profile-photos',
          '00000000-0000-0000-0000-000000000401/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.png',
          auth.uid(),
          auth.uid()::text,
          '{"size":2048,"mimetype":"image/png"}'::jsonb
      )$$,
    'owner can upload candidate B at a new immutable path'
);

select lives_ok(
    $$select * from public.submit_profile_photo(
        '00000000-0000-0000-0000-000000000401/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.png'
    )$$,
    'owner submits candidate B without replacing approved A'
);

select results_eq(
    $$select candidate_path, moderation_status::text, approved_path
        from public.get_my_profile_photo_state()$$,
    $$values (
        '00000000-0000-0000-0000-000000000401/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.png'::text,
        'pending'::text,
        '00000000-0000-0000-0000-000000000401/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1.jpg'::text
    )$$,
    'candidate B is pending while approved A remains selected'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000402';
select results_eq(
    $$select name from storage.objects
        where bucket_id = 'profile-photos' order by name$$,
    $$values (
        '00000000-0000-0000-0000-000000000401/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1.jpg'::text
    )$$,
    'pending B stays private while A remains readable'
);

reset role;
set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
select lives_ok(
    $$select * from public.moderate_profile_photo(
        '00000000-0000-0000-0000-000000000401',
        '00000000-0000-0000-0000-000000000401/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.png',
        'blocked_adult'
    )$$,
    'moderator can block adult content on candidate B only'
);

reset role;
set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000401';
set local "request.jwt.claim.role" = 'authenticated';
select results_eq(
    $$select candidate_path, moderation_status::text, approved_path
        from public.get_my_profile_photo_state()$$,
    $$values (
        '00000000-0000-0000-0000-000000000401/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.png'::text,
        'blocked_adult'::text,
        '00000000-0000-0000-0000-000000000401/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1.jpg'::text
    )$$,
    'blocked B remains private candidate state and does not remove A'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000402';
select results_eq(
    $$select name from storage.objects
        where bucket_id = 'profile-photos' order by name$$,
    $$values (
        '00000000-0000-0000-0000-000000000401/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1.jpg'::text
    )$$,
    'blocked B is hidden and approved A remains readable'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000401';
select lives_ok(
    $$select * from public.submit_profile_photo(
        '00000000-0000-0000-0000-000000000401/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.png'
    )$$,
    'blocked B must return to pending before a new decision'
);

reset role;
set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
select lives_ok(
    $$select * from public.moderate_profile_photo(
        '00000000-0000-0000-0000-000000000401',
        '00000000-0000-0000-0000-000000000401/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.png',
        'approved'
    )$$,
    'approving current pending B promotes B without touching object A'
);

reset role;
select results_eq(
    $$select submission.candidate_path, submission.status::text, profile.avatar_path
        from private.profile_photo_submissions submission
        join public.profiles profile on profile.id = submission.user_id
        where submission.user_id = '00000000-0000-0000-0000-000000000401'::uuid$$,
    $$values (
        '00000000-0000-0000-0000-000000000401/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.png'::text,
        'approved'::text,
        '00000000-0000-0000-0000-000000000401/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.png'::text
    )$$,
    'approval atomically switches public pointer from A to B'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000402';
set local "request.jwt.claim.role" = 'authenticated';
select results_eq(
    $$select name from storage.objects
        where bucket_id = 'profile-photos' order by name$$,
    $$values (
        '00000000-0000-0000-0000-000000000401/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1.png'::text
    )$$,
    'after B approval third parties read B and no longer read A'
);

set local storage.allow_delete_query = 'true';
select results_eq(
    $$with removed as (
          delete from storage.objects
           where bucket_id = 'profile-photos'
           returning 1
      ) select count(*) from removed$$,
    array[0::bigint],
    'non-owner cannot delete owner objects'
);

reset role;
select lives_ok(
    $$update public.accounts
         set status = 'suspended'
       where id = '00000000-0000-0000-0000-000000000401'::uuid$$,
    'moderation can suspend the owner independently of approved photo B'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000402';
set local "request.jwt.claim.role" = 'authenticated';
select results_eq(
    $$select count(*) from storage.objects
        where bucket_id = 'profile-photos'$$,
    array[0::bigint],
    'approved B is hidden while owner account is suspended'
);

select * from finish();
rollback;
