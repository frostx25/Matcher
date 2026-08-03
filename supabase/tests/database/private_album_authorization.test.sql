begin;

set local role postgres;

set local search_path = public, testing, extensions;
select plan(69);

-- Test-only access is transaction-scoped and rolled back at the end.
grant usage on schema testing to anon, authenticated, service_role;

select has_table('private', 'private_albums', 'private album metadata is private');
select has_table('private', 'private_album_items', 'private album items are private');
select has_table('private', 'private_album_grants', 'individual grants are private');
select has_table('private', 'private_album_cleanup_queue', 'failed physical cleanup has a private queue');
select results_eq(
    $$select public, file_size_limit, allowed_mime_types
        from storage.buckets where id = 'private-albums'$$,
    $$values (
        false,
        5242880::bigint,
        array['image/jpeg','image/png','image/webp']::text[]
    )$$,
    'private album bucket is non-public, images-only and limited to five MiB'
);
select results_eq(
    $$select count(*) from pg_policies
        where schemaname = 'storage' and tablename = 'objects'
          and policyname like 'matcher_private_albums%'
          and cmd = 'SELECT'$$,
    array[0::bigint],
    'bucket has no authenticated SELECT path that could mint signed URLs'
);
select results_eq(
    $$select count(*) from pg_policies
        where schemaname = 'storage' and tablename = 'objects'
          and policyname like 'matcher_private_albums%'
          and cmd = 'DELETE'$$,
    array[0::bigint],
    'bucket has no authenticated DELETE path that could bypass metadata-first cleanup'
);
select ok(
    not has_table_privilege('authenticated', 'private.private_albums', 'SELECT'),
    'authenticated users cannot select album metadata directly'
);
select ok(
    not has_table_privilege('authenticated', 'private.private_album_grants', 'SELECT'),
    'authenticated users cannot inspect grants directly'
);
select ok(
    has_function_privilege(
        'authenticated', 'public.authorize_private_album_item(uuid)', 'EXECUTE'
    ),
    'authenticated media proxy can reauthorize an item by id'
);
select ok(
    has_function_privilege(
        'authenticated', 'public.list_private_albums_shared_with_me()', 'EXECUTE'
    ),
    'recipient can list only albums currently shared with them'
);
select ok(
    not has_function_privilege(
        'authenticated', 'public.moderate_private_album_item(uuid,text)', 'EXECUTE'
    ),
    'authenticated users cannot forge moderation'
);
select has_column('public', 'reports', 'private_album_id', 'reports can reference an album privately');
select has_column('public', 'reports', 'private_album_item_id', 'reports can reference one album item');
select results_eq(
    $$select count(*) from pg_catalog.pg_enum enum
        join pg_catalog.pg_type type on type.oid = enum.enumtypid
        join pg_catalog.pg_namespace namespace on namespace.oid = type.typnamespace
        where namespace.nspname = 'public'
          and type.typname = 'report_reason'
          and enum.enumlabel = 'inappropriate_photo'$$,
    array[1::bigint],
    'album photo has a normalized report reason'
);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000601', 'album-owner@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000602', 'album-recipient-a@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000603', 'album-recipient-b@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000604', 'album-outsider@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000605', 'album-other-owner@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000606', 'album-suspended@matcher.invalid', now(), '{}'::jsonb);

update public.accounts
set status = 'active',
    birth_year = 1995,
    terms_accepted_at = now(),
    terms_version = 'test-v1'
where id between
    '00000000-0000-0000-0000-000000000601'::uuid and
    '00000000-0000-0000-0000-000000000606'::uuid;

insert into public.profiles (id, display_name, age, bio, intent, region_code)
values
    ('00000000-0000-0000-0000-000000000601', 'Album owner', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000602', 'Recipient A', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000603', 'Recipient B', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000604', 'Outsider', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000605', 'Other owner', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000606', 'Suspended', 31, '', 'Conhecer pessoas', 'br-test');

update public.accounts
set status = 'suspended'
where id = '00000000-0000-0000-0000-000000000606';

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000601';
set local "request.jwt.claim.role" = 'authenticated';
set local storage.allow_delete_query = 'true';

select throws_ok(
    $$select public.create_private_album('content-v1', false)$$,
    'P0001',
    'CONTENT_POLICY_REQUIRED',
    'owner must accept the content policy before creating an album'
);

select lives_ok(
    $$select public.create_private_album('content-v1', true)$$,
    'active owner can create a private album'
);

select lives_ok(
    $$select public.create_private_album('content-v1', true)$$,
    'creating the same owner album is idempotent'
);

select results_eq(
    $$select count(*) from public.get_my_private_album()$$,
    array[1::bigint],
    'one owner has at most one album'
);

select throws_ok(
    $$select * from public.reserve_private_album_item(
        (select album_id from public.get_my_private_album()), 'video/mp4'
    )$$,
    'P0001',
    'INVALID_PRIVATE_ALBUM_MEDIA_TYPE',
    'video is outside the private album MVP'
);

select throws_ok(
    $$insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
      select
          'private-albums',
          auth.uid()::text || '/' || album_id::text ||
              '/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1.jpg',
          auth.uid(),
          auth.uid()::text,
          '{"size":1024,"mimetype":"image/jpeg"}'::jsonb
      from public.get_my_private_album()$$,
    '42501',
    'new row violates row-level security policy for table "objects"',
    'storage rejects an object path that was not reserved by the server'
);

create temporary table album_upload_reservation as
select * from public.reserve_private_album_item(
    (select album_id from public.get_my_private_album()), 'image/jpeg'
);

select lives_ok(
    $$insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
      select
          'private-albums', reserved.object_path, auth.uid(), auth.uid()::text,
          '{"size":2048,"mimetype":"image/jpeg"}'::jsonb
      from album_upload_reservation reserved$$,
    'owner uploads bytes only to the exact reserved immutable path'
);

select results_eq(
    $$select count(*) from public.authorize_private_album_item(
        (select item_id from public.list_my_private_album_items() limit 1)
    )$$,
    array[0::bigint],
    'uploading transport state is not readable before finalization'
);

select lives_ok(
    $$select * from public.finalize_private_album_item(
        (select item_id from public.list_my_private_album_items() limit 1)
    )$$,
    'valid uploaded image finalizes directly without preapproval'
);

select results_eq(
    $$select item_status from public.list_my_private_album_items()$$,
    $$values ('available'::text)$$,
    'finalized item becomes available rather than pending'
);

select results_eq(
    $$select count(*) from public.authorize_private_album_item(
        (select item_id from public.list_my_private_album_items() limit 1)
    )$$,
    array[1::bigint],
    'owner media request is authorized by item id'
);

select results_eq(
    $$select count(*) from storage.objects where bucket_id = 'private-albums'$$,
    array[0::bigint],
    'even owner cannot directly select bucket rows or mint signed URLs'
);

select results_eq(
    $$select avatar_path from public.get_my_profile()$$,
    $$values (null::text)$$,
    'private album image never becomes the public profile photo'
);

set local role postgres;
create temporary table album_test_fixture as
select album.id as album_id, item.id as item_id, item.object_path
from private.private_albums album
join private.private_album_items item on item.album_id = album.id
where album.owner_id = '00000000-0000-0000-0000-000000000601'::uuid;
grant select on album_test_fixture to authenticated, service_role;

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000604';
set local "request.jwt.claim.role" = 'authenticated';

select results_eq(
    $$select count(*) from public.list_private_albums_shared_with_me()$$,
    array[0::bigint],
    'unrelated user cannot list album metadata'
);

select results_eq(
    $$select count(*) from public.authorize_private_album_item(
        (select item_id from album_test_fixture limit 1)
    )$$,
    array[0::bigint],
    'knowing a synthetic item id does not authorize unrelated media access'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000601';

select lives_ok(
    $$select public.grant_private_album_access(
        (select album_id from album_test_fixture limit 1),
        '00000000-0000-0000-0000-000000000602'
    )$$,
    'owner grants recipient A explicitly'
);

select lives_ok(
    $$select public.grant_private_album_access(
        (select album_id from album_test_fixture limit 1),
        '00000000-0000-0000-0000-000000000603'
    )$$,
    'owner grants recipient B independently'
);

select throws_ok(
    $$select public.grant_private_album_access(
        (select album_id from album_test_fixture limit 1), auth.uid()
    )$$,
    'P0001',
    'INVALID_ALBUM_RECIPIENT',
    'owner cannot grant the album to self'
);

select throws_ok(
    $$select public.grant_private_album_access(
        (select album_id from album_test_fixture limit 1),
        '00000000-0000-0000-0000-000000000606'
    )$$,
    'P0001',
    'RECIPIENT_NOT_AVAILABLE',
    'owner cannot grant access to a suspended account'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000602';

select results_eq(
    $$select owner_id::text, item_count
        from public.list_private_albums_shared_with_me()$$,
    $$values ('00000000-0000-0000-0000-000000000601'::text, 1)$$,
    'recipient A lists only the album explicitly shared with them'
);

select results_eq(
    $$select count(*) from public.authorize_private_album_item(
        (select item_id from album_test_fixture limit 1)
    )$$,
    array[1::bigint],
    'active grant authorizes recipient A media request'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000601';
select lives_ok(
    $$select public.revoke_private_album_access(
        (select album_id from album_test_fixture limit 1),
        '00000000-0000-0000-0000-000000000602'
    )$$,
    'owner revokes recipient A'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000602';
select results_eq(
    $$select count(*) from public.authorize_private_album_item(
        (select item_id from album_test_fixture limit 1)
    )$$,
    array[0::bigint],
    'revocation immediately denies the next media authorization'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000603';
select results_eq(
    $$select count(*) from public.authorize_private_album_item(
        (select item_id from album_test_fixture limit 1)
    )$$,
    array[1::bigint],
    'revoking A does not change recipient B grant'
);

-- Recipient A creates a reverse album and grant so block revocation is tested
-- in both directions.
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000602';
select lives_ok(
    $$select public.create_private_album('content-v1', true)$$,
    'recipient A can own a separate private album'
);
select lives_ok(
    $$select public.grant_private_album_access(
        (select album_id from public.get_my_private_album()),
        '00000000-0000-0000-0000-000000000601'
    )$$,
    'recipient A grants their album back to the original owner'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000601';
select lives_ok(
    $$select public.grant_private_album_access(
        (select album_id from album_test_fixture limit 1),
        '00000000-0000-0000-0000-000000000602'
    )$$,
    'original owner explicitly regrants recipient A before block test'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000602';
select lives_ok(
    $$select public.block_user('00000000-0000-0000-0000-000000000601')$$,
    'blocking inserts one relation that triggers album revocation'
);

set local role postgres;
select results_eq(
    $$select count(*) from private.private_album_grants album_grant
        join private.private_albums album on album.id = album_grant.album_id
        where album_grant.revoked_at is not null
          and album_grant.revoke_reason = 'blocked'
          and (
              (album.owner_id = '00000000-0000-0000-0000-000000000601'::uuid
               and album_grant.recipient_id = '00000000-0000-0000-0000-000000000602'::uuid)
              or
              (album.owner_id = '00000000-0000-0000-0000-000000000602'::uuid
               and album_grant.recipient_id = '00000000-0000-0000-0000-000000000601'::uuid)
          )$$,
    array[2::bigint],
    'block revokes active grants permanently in both directions'
);

delete from public.blocks
where blocker_id = '00000000-0000-0000-0000-000000000602'::uuid
  and blocked_id = '00000000-0000-0000-0000-000000000601'::uuid;

select results_eq(
    $$select count(*) from private.private_album_grants
        where revoked_at is null and (
            recipient_id = '00000000-0000-0000-0000-000000000601'::uuid
            or recipient_id = '00000000-0000-0000-0000-000000000602'::uuid
        )$$,
    array[0::bigint],
    'removing a block does not restore either grant'
);

set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000601';
select lives_ok(
    $$select public.grant_private_album_access(
        (select album_id from album_test_fixture limit 1),
        '00000000-0000-0000-0000-000000000602'
    )$$,
    'new explicit grant is required after unblock'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000602';
select lives_ok(
    $$select public.report_private_album(
        (select album_id from album_test_fixture limit 1),
        'inappropriate_photo',
        'Descrição sintética',
        (select item_id from album_test_fixture limit 1)
    )$$,
    'authorized recipient can report the shared private image'
);

select results_eq(
    $$select count(*) from public.reports where private_album_id is not null$$,
    array[1::bigint],
    'album report creates one reporter-visible report and moderation case context'
);

select results_eq(
    $$select count(*) from public.authorize_private_album_item(
        (select item_id from album_test_fixture limit 1)
    )$$,
    array[0::bigint],
    'reporting revokes the reporter grant and hides the album immediately'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000603';
select results_eq(
    $$select count(*) from public.authorize_private_album_item(
        (select item_id from album_test_fixture limit 1)
    )$$,
    array[1::bigint],
    'report does not revoke unrelated grants before moderation'
);

select throws_ok(
    $$select public.moderate_private_album_item(
        (select item_id from album_test_fixture limit 1),
        'removed_by_moderation'
    )$$,
    '42501',
    'permission denied for function moderate_private_album_item',
    'recipient cannot remove content as a moderator'
);

set local role postgres;
set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
select lives_ok(
    $$select public.moderate_private_album_item(
        (select item_id from album_test_fixture limit 1),
        'removed_by_moderation'
    )$$,
    'service role can remove a reported private item'
);

set local role postgres;
select results_eq(
    $$select status from private.private_album_items
        where id = (select item_id from album_test_fixture limit 1)$$,
    $$values ('removed_by_moderation'::text)$$,
    'moderation state is retained while physical cleanup is queued'
);

select results_eq(
    $$select count(*) from public.audit_events
        where metadata::text ~ '(private-albums|Descrição sintética|/00000000-)'$$,
    array[0::bigint],
    'audit metadata contains no object path, URL or free-text report details'
);

set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000603';
select results_eq(
    $$select count(*) from public.authorize_private_album_item(
        (select item_id from album_test_fixture limit 1)
    )$$,
    array[0::bigint],
    'moderation removal overrides every remaining grant'
);

-- A separate owner exercises the atomic ten-item reservation limit.
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000604';
select lives_ok(
    $$select public.create_private_album('content-v1', true)$$,
    'limit fixture owner creates one album'
);
select lives_ok(
    $test$do $block$
      begin
        for i in 1..10 loop
          perform public.reserve_private_album_item(
            (select album_id from public.get_my_private_album()), 'image/jpeg'
          );
        end loop;
      end
      $block$;$test$,
    'ten server-serialized item reservations succeed'
);
select throws_ok(
    $$select * from public.reserve_private_album_item(
        (select album_id from public.get_my_private_album()), 'image/jpeg'
    )$$,
    'P0001',
    'PRIVATE_ALBUM_LIMIT_REACHED',
    'eleventh reservation is rejected by the server'
);

select results_eq(
    $$select count(*) from pg_policies
        where schemaname = 'storage' and tablename = 'objects'
          and policyname = 'matcher_private_albums_update'$$,
    array[0::bigint],
    'absence of update policy prevents move and upsert replacement'
);

set local role postgres;
select results_eq(
    $$select count(*) from private.private_album_cleanup_queue$$,
    array[1::bigint],
    'moderation removal enqueues physical object cleanup exactly once'
);

set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000601';
set local storage.allow_delete_query = 'true';

create temporary table album_delete_paths as
select object_path from public.begin_private_album_deletion(
    (select album_id from album_test_fixture limit 1)
);
grant select on album_delete_paths to service_role;

select results_eq(
    $$select count(*) from public.authorize_private_album_item(
        (select item_id from album_test_fixture limit 1)
    )$$,
    array[0::bigint],
    'begin deletion makes all album bytes inaccessible before cleanup'
);

set local role postgres;
set local role service_role;
set local "request.jwt.claim.role" = 'service_role';

select throws_ok(
    $$delete from storage.objects
        where bucket_id = 'private-albums'
          and name in (select object_path from album_delete_paths)$$,
    'P0001',
    'PRIVATE_ALBUM_EVIDENCE_HOLD',
    'physical cleanup cannot delete reported evidence before hold expiry'
);

set local role postgres;
set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000601';

select results_eq(
    $$select public.finalize_private_album_deletion(
        (select album_id from album_test_fixture limit 1)
    )$$,
    array[true],
    'album deletion finalizes after physical objects are gone'
);

select results_eq(
    $$select public.finalize_private_album_deletion(
        (select album_id from album_test_fixture limit 1)
    )$$,
    array[true],
    'repeating album deletion finalization is idempotent'
);

set local role postgres;
select results_eq(
    $$select count(*) from private.private_albums
        where owner_id = '00000000-0000-0000-0000-000000000601'::uuid
          and status = 'deleting'$$,
    array[1::bigint],
    'logical deletion retains only restricted deleting metadata during evidence hold'
);

select results_eq(
    $$select count(*) from private.private_album_grants album_grant
        join private.private_albums album on album.id = album_grant.album_id
        where album.owner_id = '00000000-0000-0000-0000-000000000601'::uuid
          and album_grant.revoked_at is null$$,
    array[0::bigint],
    'logical deletion leaves no active grant while evidence is held'
);

select results_eq(
    $$select count(*) from storage.objects object
        where object.bucket_id = 'private-albums'
          and object.name in (select object_path from album_test_fixture)$$,
    array[1::bigint],
    'reported object remains physically held but inaccessible until retention ends'
);

select ok(
    not has_function_privilege(
        'anon', 'public.authorize_private_album_item(uuid)', 'EXECUTE'
    ),
    'anonymous role cannot access the media authorization RPC'
);

select results_eq(
    $$select count(*) from public.profiles where avatar_path like '%private-albums%'$$,
    array[0::bigint],
    'private album storage is absent from public profile payloads'
);

select * from finish();
rollback;
