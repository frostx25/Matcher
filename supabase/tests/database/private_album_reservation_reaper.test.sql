begin;

set local role postgres;
set local search_path = public, testing, extensions;
select plan(47);

-- Test-only access is transaction-scoped and rolled back at the end.
grant usage on schema testing to anon, authenticated, service_role;

-- 01-15: schema, API and authorization surface.
select has_column(
    'private', 'private_album_items', 'reservation_key',
    'private album reservations have an opaque idempotency key'
);
select has_column(
    'private', 'private_album_items', 'upload_expires_at',
    'private album upload reservations have a server expiry'
);
select results_eq(
    $$select count(*) from pg_indexes
      where schemaname = 'private'
        and tablename = 'private_album_items'
        and indexname = 'private_album_items_reservation_key_idx'
        and indexdef like 'CREATE UNIQUE INDEX%'$$,
    array[1::bigint],
    'idempotency keys are unique within an album'
);
select results_eq(
    $$select count(*) from pg_indexes
      where schemaname = 'private'
        and tablename = 'private_album_items'
        and indexname = 'private_album_items_expired_upload_idx'$$,
    array[1::bigint],
    'expired uploading reservations have a bounded reaper index'
);
select ok(
    not has_table_privilege(
        'authenticated', 'private.private_album_items', 'SELECT'
    ),
    'authenticated cannot inspect reservation keys or expiries directly'
);
select ok(
    to_regprocedure(
        'public.reserve_private_album_item(uuid,text,uuid)'
    ) is not null,
    'idempotent reserve overload is installed'
);
select ok(
    has_function_privilege(
        'authenticated',
        'public.reserve_private_album_item(uuid,text,uuid)',
        'EXECUTE'
    ),
    'authenticated owner may call the idempotent reserve RPC'
);
select ok(
    not has_function_privilege(
        'anon',
        'public.reserve_private_album_item(uuid,text,uuid)',
        'EXECUTE'
    ),
    'anonymous callers cannot reserve private media'
);
select ok(
    to_regprocedure(
        'public.reserve_private_album_item(uuid,text)'
    ) is not null,
    'temporary two-argument compatibility overload remains installed'
);
select ok(
    has_function_privilege(
        'authenticated',
        'public.reserve_private_album_item(uuid,text)',
        'EXECUTE'
    ),
    'installed clients retain the TTL-bounded reserve RPC'
);
select ok(
    has_function_privilege(
        'service_role',
        'public.reap_expired_private_album_uploads(integer)',
        'EXECUTE'
    ),
    'only the service worker receives the explicit reaper RPC'
);
select ok(
    not has_function_privilege(
        'authenticated',
        'public.reap_expired_private_album_uploads(integer)',
        'EXECUTE'
    ),
    'authenticated callers cannot execute the reaper RPC'
);
select ok(
    not has_function_privilege(
        'authenticated',
        'private.reap_expired_private_album_uploads(integer,uuid)',
        'EXECUTE'
    ),
    'authenticated callers cannot invoke the internal scoped reaper'
);
select results_eq(
    $$select pg_get_function_result(
        'public.reap_expired_private_album_uploads(integer)'::regprocedure
      )$$,
    $$values ('integer'::text)$$,
    'reaper exposes only a count and never an object path'
);
select ok(
    not has_function_privilege(
        'authenticated',
        'public.get_private_album_cleanup_batch(integer)',
        'EXECUTE'
    ),
    'authenticated callers cannot lease paths reaped for cleanup'
);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values (
    '00000000-0000-0000-0000-000000000901',
    'reservation-owner@matcher.invalid',
    now(),
    '{}'::jsonb
);

update public.accounts
set status = 'active',
    birth_year = 1995,
    terms_accepted_at = now(),
    terms_version = 'test-v1'
where id = '00000000-0000-0000-0000-000000000901'::uuid;

insert into public.profiles (id, display_name, age, bio, intent, region_code)
values (
    '00000000-0000-0000-0000-000000000901',
    'Reservation owner',
    31,
    '',
    'Conhecer pessoas',
    'br-test'
);

set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000901';

-- 16-27: idempotent retry and expiry are server-authoritative.
select lives_ok(
    $$select public.create_private_album('content-v1', true)$$,
    'owner creates an active private album'
);

create temporary table reservation_album as
select album_id from public.get_my_private_album();

create temporary table first_reservation as
select *
from public.reserve_private_album_item(
    (select album_id from reservation_album),
    'image/jpeg',
    '10000000-0000-4000-8000-000000000001'::uuid
);

select results_eq(
    $$select count(*) from first_reservation
      where object_path is not null
        and position = 0
        and reservation_status = 'uploading'
        and upload_expires_at > clock_timestamp()$$,
    array[1::bigint],
    'first request receives one live server-timed reservation'
);

create temporary table repeated_reservation as
select *
from public.reserve_private_album_item(
    (select album_id from reservation_album),
    'image/jpeg',
    '10000000-0000-4000-8000-000000000001'::uuid
);

select results_eq(
    $$select count(*)
      from first_reservation first
      join repeated_reservation repeated
        on repeated.item_id = first.item_id
       and repeated.object_path = first.object_path
       and repeated.position = first.position
       and repeated.reservation_status = first.reservation_status
       and repeated.upload_expires_at = first.upload_expires_at$$,
    array[1::bigint],
    'same key and payload returns the exact same reservation'
);
select results_eq(
    $$select item_count from public.get_my_private_album()$$,
    $$values (1::integer)$$,
    'idempotent retry consumes only one album slot'
);
select throws_ok(
    $$select * from public.reserve_private_album_item(
        (select album_id from reservation_album),
        'image/png',
        '10000000-0000-4000-8000-000000000001'::uuid
    )$$,
    'P0001',
    'PRIVATE_ALBUM_IDEMPOTENCY_CONFLICT',
    'same key cannot be reused with a different MIME type'
);
select ok(
    private.can_insert_private_album_object(
        (select object_path from first_reservation),
        auth.uid(),
        '{"contentLength":2048,"mimetype":"image/jpeg"}'::jsonb
    ),
    'live reservation authorizes only its canonical Storage upload'
);

set local role postgres;
update private.private_album_items item
set upload_expires_at = clock_timestamp() - interval '1 second'
where item.id = (select item_id from first_reservation);

set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000901';
select ok(
    not private.can_insert_private_album_object(
        (select object_path from first_reservation),
        auth.uid(),
        '{"contentLength":2048,"mimetype":"image/jpeg"}'::jsonb
    ),
    'Storage rejects a late upload before the reaper needs to run'
);

create temporary table expired_retry as
select *
from public.reserve_private_album_item(
    (select album_id from reservation_album),
    'image/jpeg',
    '10000000-0000-4000-8000-000000000001'::uuid
);

select results_eq(
    $$select reservation_status, object_path is null, position is null
      from expired_retry$$,
    $$values ('expired'::text, true, true)$$,
    'expired retry returns a terminal state without exposing its path'
);

set local role postgres;
select results_eq(
    $$select status, position is null
      from private.private_album_items
      where id = (select item_id from first_reservation)$$,
    $$values ('deleting'::text, true)$$,
    'reaper frees the slot before physical cleanup'
);
select results_eq(
    $$select count(*) from private.private_album_cleanup_queue
      where object_path = (select object_path from first_reservation)$$,
    array[1::bigint],
    'expired reservation creates exactly one private cleanup tombstone'
);

set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000901';
select results_eq(
    $$select reservation_status, object_path is null, position is null
      from public.reserve_private_album_item(
          (select album_id from reservation_album),
          'image/jpeg',
          '10000000-0000-4000-8000-000000000001'::uuid
      )$$,
    $$values ('expired'::text, true, true)$$,
    'repeated expired retry remains terminal and idempotent'
);

set local role postgres;
select results_eq(
    $$select count(*) from private.private_album_cleanup_queue
      where object_path = (select object_path from first_reservation)$$,
    array[1::bigint],
    'repeated reaping does not duplicate the cleanup tombstone'
);

-- 28-32: a completed retry is idempotent and cannot overwrite Storage.
set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000901';
create temporary table available_reservation as
select *
from public.reserve_private_album_item(
    (select album_id from reservation_album),
    'image/jpeg',
    '10000000-0000-4000-8000-000000000002'::uuid
);

select results_eq(
    $$select position, reservation_status, object_path is not null
      from available_reservation$$,
    $$values (0::smallint, 'uploading'::text, true)$$,
    'new intended upload safely reuses the slot with a new immutable path'
);

set local storage.operation = 'storage.object.upload';
select lives_ok(
    $$insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
      select
          'private-albums',
          object_path,
          auth.uid(),
          auth.uid()::text,
          '{"contentLength":2048,"mimetype":"image/jpeg"}'::jsonb
      from available_reservation
      returning id$$,
    'live reservation passes the Storage upload precheck'
);

set local role postgres;
update storage.objects object
set metadata = '{"size":2048,"mimetype":"image/jpeg"}'::jsonb
where object.bucket_id = 'private-albums'
  and object.name = (select object_path from available_reservation);

set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000901';
set local storage.operation = '';
select lives_ok(
    $$select * from public.finalize_private_album_item(
        (select item_id from available_reservation)
    )$$,
    'owner finalizes the uploaded object'
);
select results_eq(
    $$select count(*)
      from public.reserve_private_album_item(
          (select album_id from reservation_album),
          'image/jpeg',
          '10000000-0000-4000-8000-000000000002'::uuid
      ) retry
      join available_reservation original on original.item_id = retry.item_id
      where retry.position = original.position
        and retry.reservation_status = 'available'
        and retry.object_path is null$$,
    array[1::bigint],
    'retry after finalize returns the existing item without another upload path'
);
select ok(
    not private.can_insert_private_album_object(
        (select object_path from available_reservation),
        auth.uid(),
        '{"contentLength":2048,"mimetype":"image/jpeg"}'::jsonb
    ),
    'available item cannot be overwritten through the upload policy'
);

-- 33-36: an object inserted before TTL cannot be finalized after TTL.
create temporary table late_finalize_reservation as
select *
from public.reserve_private_album_item(
    (select album_id from reservation_album),
    'image/jpeg',
    '10000000-0000-4000-8000-000000000003'::uuid
);

set local storage.operation = 'storage.object.upload';
select lives_ok(
    $$insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
      select
          'private-albums',
          object_path,
          auth.uid(),
          auth.uid()::text,
          '{"contentLength":2048,"mimetype":"image/jpeg"}'::jsonb
      from late_finalize_reservation
      returning id$$,
    'object may finish Storage insertion while its reservation is live'
);

set local role postgres;
update storage.objects object
set metadata = '{"size":2048,"mimetype":"image/jpeg"}'::jsonb
where object.bucket_id = 'private-albums'
  and object.name = (select object_path from late_finalize_reservation);
update private.private_album_items item
set upload_expires_at = clock_timestamp() - interval '1 second'
where item.id = (select item_id from late_finalize_reservation);

set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000901';
set local storage.operation = '';
select results_eq(
    $$select item_status, position is null
      from public.finalize_private_album_item(
          (select item_id from late_finalize_reservation)
      )$$,
    $$values ('deleting'::text, true)$$,
    'late finalization is denied and atomically tombstones the item'
);
select results_eq(
    $$select count(*) from public.authorize_private_album_item(
        (select item_id from late_finalize_reservation)
      )$$,
    array[0::bigint],
    'late-finalized bytes are never authorized for reading'
);

set local role postgres;
select results_eq(
    $$select count(*) from private.private_album_cleanup_queue
      where object_path = (select object_path from late_finalize_reservation)$$,
    array[1::bigint],
    'late finalization queues the already-inserted object for cleanup'
);

-- 37-47: compatibility reserve is bounded; worker poll reaps and leases it.
set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000901';
create temporary table legacy_reservation as
select *
from public.reserve_private_album_item(
    (select album_id from reservation_album),
    'image/jpeg'
);

set local role postgres;
select results_eq(
    $$select count(*)
      from legacy_reservation legacy
      join private.private_album_items item on item.id = legacy.item_id
      where item.status = 'uploading'
        and item.upload_expires_at > clock_timestamp()
        and legacy.position = 1$$,
    array[1::bigint],
    'legacy overload remains compatible but receives a bounded server lease'
);

set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000901';
select throws_ok(
    $$select public.reap_expired_private_album_uploads(1)$$,
    '42501',
    'permission denied for function reap_expired_private_album_uploads',
    'authenticated caller cannot trigger the global reaper'
);

set local role postgres;
update private.private_album_items item
set upload_expires_at = clock_timestamp() - interval '1 second'
where item.id = (select item_id from legacy_reservation);

set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
create temporary table cleanup_leases as
select * from public.get_private_album_cleanup_batch(100);

set local role postgres;
select results_eq(
    $$select count(*)
      from cleanup_leases lease
      where lease.lease_token is not null
        and lease.leased_until > clock_timestamp()
        and lease.object_path in (
            (select object_path from first_reservation),
            (select object_path from legacy_reservation),
            (select object_path from late_finalize_reservation)
        )$$,
    array[3::bigint],
    'normal cleanup poll reaps and leases every eligible expired reservation'
);

select results_eq(
    $$select status, position is null
      from private.private_album_items
      where id = (select item_id from legacy_reservation)$$,
    $$values ('deleting'::text, true)$$,
    'poll-triggered reaper frees the legacy slot before deletion'
);

set local storage.allow_delete_query = 'true';
delete from storage.objects object
where object.bucket_id = 'private-albums'
  and object.name = (select object_path from late_finalize_reservation);

grant select on
    first_reservation,
    legacy_reservation,
    late_finalize_reservation,
    cleanup_leases
to service_role;

set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
select results_eq(
    $$select public.reap_expired_private_album_uploads(100)$$,
    array[0],
    'repeated explicit reaper is idempotent'
);
select results_eq(
    $$select public.confirm_private_album_object_deleted(
        lease.object_path,
        lease.lease_token
      )
      from cleanup_leases lease
      where lease.object_path = (select object_path from first_reservation)$$,
    array[true],
    'current lease confirms absent object for the lost-response reservation'
);
select results_eq(
    $$select public.confirm_private_album_object_deleted(
        lease.object_path,
        lease.lease_token
      )
      from cleanup_leases lease
      where lease.object_path = (select object_path from legacy_reservation)$$,
    array[true],
    'current lease confirms absent object for the legacy reservation'
);
select results_eq(
    $$select public.confirm_private_album_object_deleted(
        lease.object_path,
        lease.lease_token
      )
      from cleanup_leases lease
      where lease.object_path = (select object_path from late_finalize_reservation)$$,
    array[true],
    'current lease confirms removal of the late-finalized object'
);

set local role postgres;
select results_eq(
    $$select count(*) from private.private_album_items
      where id in (
          (select item_id from first_reservation),
          (select item_id from legacy_reservation),
          (select item_id from late_finalize_reservation)
      )$$,
    array[0::bigint],
    'lease confirmation removes expired reservation metadata'
);
select results_eq(
    $$select status from private.private_album_items
      where id = (select item_id from available_reservation)$$,
    $$values ('available'::text)$$,
    'reaper never removes the completed idempotent upload'
);
select results_eq(
    $$select count(*) from private.private_album_cleanup_queue
      where object_path in (
          (select object_path from first_reservation),
          (select object_path from legacy_reservation),
          (select object_path from late_finalize_reservation)
      )$$,
    array[0::bigint],
    'successful cleanup consumes every reservation tombstone'
);

select * from finish();
rollback;
