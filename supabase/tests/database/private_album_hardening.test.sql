begin;

set local role postgres;

set local search_path = public, testing, extensions;
select plan(70);

-- 01-11: privilege and API surface.
select has_table(
    'private', 'private_album_report_evidence',
    'reported private media has a restricted evidence hold table'
);
select ok(
    not has_table_privilege(
        'authenticated', 'private.private_album_report_evidence', 'SELECT'
    ),
    'authenticated cannot inspect restricted album evidence'
);
select ok(
    not has_table_privilege(
        'authenticated', 'private.private_album_report_markers', 'SELECT'
    ),
    'authenticated cannot inspect persistent private report markers'
);
select ok(
    not has_table_privilege('authenticated', 'public.profiles', 'SELECT'),
    'authenticated has no broad SELECT privilege on profiles'
);
select ok(
    not has_schema_privilege('anon', 'testing', 'USAGE'),
    'anonymous clients cannot use the hosted pgTAP schema'
);
select ok(
    not has_schema_privilege('authenticated', 'testing', 'USAGE'),
    'authenticated clients cannot use the hosted pgTAP schema'
);
select ok(
    not has_schema_privilege('service_role', 'testing', 'USAGE'),
    'runtime service clients cannot use the hosted pgTAP schema'
);

-- Test-only access is transaction-scoped and rolled back at the end.
grant usage on schema testing to anon, authenticated, service_role;

select results_eq(
    $$select count(*) from pg_policies
      where schemaname = 'public' and tablename = 'profiles'$$,
    array[0::bigint],
    'profiles expose no authenticated row-level table read policy'
);
select ok(
    has_function_privilege('authenticated', 'public.get_my_profile()', 'EXECUTE'),
    'authenticated can read only its own profile through RPC'
);
select ok(
    to_regprocedure(
        'public.complete_onboarding(integer,text,text,text,boolean,text,text)'
    ) is not null,
    'legacy onboarding overload remains available'
);
select ok(
    to_regprocedure('public.begin_private_album_deletion()') is null,
    'unsafe no-argument album deletion entry point is removed'
);
select ok(
    to_regprocedure('public.finalize_private_album_deletion()') is null,
    'unsafe no-argument album finalization entry point is removed'
);
select ok(
    to_regprocedure('public.reserve_private_album_item(text)') is null,
    'unsafe reserve overload without target album is removed'
);
select ok(
    to_regprocedure('public.grant_private_album_access(uuid)') is null,
    'unsafe grant overload without target album is removed'
);
select ok(
    to_regprocedure('public.revoke_private_album_access(uuid)') is null,
    'unsafe revoke overload without target album is removed'
);
select results_eq(
    $$select count(*) from pg_policies
      where schemaname = 'storage' and tablename = 'objects'
        and policyname like 'matcher_private_albums%' and cmd = 'SELECT'$$,
    array[1::bigint],
    'private albums expose one SELECT policy limited to upload INSERT RETURNING'
);
select results_eq(
    $$select count(*) from pg_policies
      where schemaname = 'storage' and tablename = 'objects'
        and policyname like 'matcher_private_albums%' and cmd = 'UPDATE'$$,
    array[0::bigint],
    'private albums keep no authenticated Storage UPDATE policy'
);
select results_eq(
    $$select count(*) from pg_policies
      where schemaname = 'storage' and tablename = 'objects'
        and policyname like 'matcher_private_albums%' and cmd = 'DELETE'$$,
    array[0::bigint],
    'private albums keep no authenticated Storage DELETE policy'
);
select ok(
    not has_function_privilege(
        'authenticated', 'public.get_private_album_cleanup_batch(integer)', 'EXECUTE'
    ),
    'authenticated cannot lease cleanup work'
);
select ok(
    not has_function_privilege(
        'authenticated',
        'public.confirm_private_album_object_deleted(text,uuid)',
        'EXECUTE'
    ),
    'authenticated cannot confirm privileged cleanup'
);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000801', 'hard-owner@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000802', 'hard-reporter@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000803', 'hard-moderated@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000804', 'hard-finalize@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000805', 'hard-cleanup@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000806', 'hard-legacy@matcher.invalid', now(), '{}'::jsonb);

update public.accounts
set status = 'active',
    birth_year = 1995,
    terms_accepted_at = now(),
    terms_version = 'test-v1'
where id between
    '00000000-0000-0000-0000-000000000801'::uuid and
    '00000000-0000-0000-0000-000000000805'::uuid;

insert into public.profiles (id, display_name, age, bio, intent, region_code)
values
    ('00000000-0000-0000-0000-000000000801', 'Hard owner', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000802', 'Hard reporter', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000803', 'Hard moderated', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000804', 'Hard finalize', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000805', 'Hard cleanup', 31, '', 'Conhecer pessoas', 'br-test');

-- 12-14: safe compatibility wrapper.
set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000806';

select lives_ok(
    $$select * from public.complete_onboarding(
        1995, 'Legacy safe', 'br-test', 'test-v1', true, '', 'Conhecer pessoas'
    )$$,
    'legacy onboarding delegates to the authoritative implementation'
);
select results_eq(
    $$select gender_identity_ids, gender_visible, looking_for_gender_ids
      from public.get_my_gender_settings()$$,
    $$values (
        array['prefer_not_to_say']::text[],
        false,
        array['everyone']::text[]
    )$$,
    'legacy onboarding applies hidden identity and everyone defaults'
);
select results_eq(
    $$select id, display_name, verified from public.get_my_profile()$$,
    $$values (
        '00000000-0000-0000-0000-000000000806'::uuid,
        'Legacy safe'::text,
        false
    )$$,
    'owner profile RPC returns exactly the authenticated owner row'
);

-- 15-23: report revocation, restricted evidence and unilateral regrant guard.
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000801';
select lives_ok(
    $$select public.create_private_album('content-v1', true)$$,
    'owner creates an active album'
);

create temporary table hard_album_fixture as
select album_id from public.get_my_private_album();
grant select on hard_album_fixture to authenticated, service_role;

create temporary table hard_reservation as
select * from public.reserve_private_album_item(
    (select album_id from hard_album_fixture), 'image/jpeg'
);
grant select on hard_reservation to authenticated, service_role;

set local storage.operation = 'storage.object.upload';
select lives_ok(
    $$insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
      select 'private-albums', object_path, auth.uid(), auth.uid()::text,
             '{"contentLength":2048,"mimetype":"image/jpeg"}'::jsonb
      from hard_reservation
      returning id$$,
    'Storage precheck returns the exact reserved object before any tombstone'
);

set local role postgres;
update storage.objects object
   set metadata = '{"size":2048,"mimetype":"image/jpeg"}'::jsonb
 where object.bucket_id = 'private-albums'
   and object.name = (select object_path from hard_reservation);

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000801';
set local "request.jwt.claim.role" = 'authenticated';
set local storage.operation = '';
select lives_ok(
    $$select * from public.finalize_private_album_item(
        (select item_id from hard_reservation)
    )$$,
    'uploaded object becomes available'
);
select lives_ok(
    $$select public.grant_private_album_access(
        (select album_id from hard_album_fixture),
        '00000000-0000-0000-0000-000000000802'
    )$$,
    'owner grants the future reporter'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000802';
select lives_ok(
    $$select public.report_private_album(
        (select album_id from hard_album_fixture),
        'inappropriate_photo',
        'Synthetic report',
        (select item_id from hard_reservation)
    )$$,
    'authorized recipient reports one private item'
);

set local role postgres;
select results_eq(
    $$select count(*)
      from private.private_album_report_evidence evidence
      where evidence.object_path = (select object_path from hard_reservation)
        and evidence.hold_until >= evidence.created_at + interval '30 days'$$,
    array[1::bigint],
    'report creates a minimum 30-day restricted object hold'
);

set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000802';
select results_eq(
    $$select count(*) from public.authorize_private_album_item(
        (select item_id from hard_reservation)
    )$$,
    array[0::bigint],
    'reporter access is revoked immediately despite physical retention'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000801';
select throws_ok(
    $$select public.grant_private_album_access(
        (select album_id from hard_album_fixture),
        '00000000-0000-0000-0000-000000000802'
    )$$,
    'P0001',
    'ALBUM_ACCESS_REPORTED',
    'owner cannot unilaterally reactivate reported access'
);
set local role postgres;
select results_eq(
    $$select revoke_reason from private.private_album_grants$$,
    $$values ('reported'::text)$$,
    'failed regrant preserves the reported revocation reason'
);

-- 24-32: logical deletion succeeds under hold and permits a new active album.
set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000801';
create temporary table held_delete_result as
select * from public.begin_private_album_deletion(
    (select album_id from hard_album_fixture)
);
grant select on held_delete_result to authenticated, service_role;

select results_eq(
    $$select delete_now, hold_until is not null from held_delete_result$$,
    $$values (false, true)$$,
    'delete contract explicitly defers physical removal under evidence hold'
);
select results_eq(
    $$select public.finalize_private_album_deletion(
        (select album_id from hard_album_fixture)
    )$$,
    array[true],
    'held album deletion completes logically without deleting evidence'
);
select results_eq(
    $$select count(*) from public.get_my_private_album()$$,
    array[0::bigint],
    'deleting evidence container is absent from owner album reads'
);
select lives_ok(
    $$select public.create_private_album('content-v2', true)$$,
    'owner can create a new active album during old evidence retention'
);
create temporary table replacement_album_fixture as
select album_id from public.get_my_private_album();
grant select on replacement_album_fixture to authenticated, service_role;

set local role postgres;
select results_eq(
    $$select status, count(*) from private.private_albums
      where owner_id = '00000000-0000-0000-0000-000000000801'::uuid
      group by status order by status$$,
    $$values ('active'::text, 1::bigint), ('deleting'::text, 1::bigint)$$,
    'one new active album coexists with one inaccessible deleting evidence container'
);

set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000801';
select throws_ok(
    $$select * from public.reserve_private_album_item(
        (select album_id from hard_album_fixture), 'image/jpeg'
    )$$,
    'P0001',
    'PRIVATE_ALBUM_NOT_FOUND',
    'stale reserve bound to old album cannot add an item to replacement'
);
select throws_ok(
    $$select public.grant_private_album_access(
        (select album_id from hard_album_fixture),
        '00000000-0000-0000-0000-000000000803'
    )$$,
    'P0001',
    'PRIVATE_ALBUM_NOT_FOUND',
    'stale grant bound to old album cannot grant replacement'
);
select lives_ok(
    $$select public.grant_private_album_access(
        (select album_id from replacement_album_fixture),
        '00000000-0000-0000-0000-000000000803'
    )$$,
    'owner grants replacement only with its explicit album id'
);
select results_eq(
    $$select public.revoke_private_album_access(
        (select album_id from hard_album_fixture),
        '00000000-0000-0000-0000-000000000803'
    )$$,
    array[false],
    'stale revoke for old album does not revoke replacement grant'
);
select results_eq(
    $$select count(*) from public.list_private_album_grants()
      where recipient_id = '00000000-0000-0000-0000-000000000803'::uuid$$,
    array[1::bigint],
    'replacement grant remains active after stale revoke'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000803';
select results_eq(
    $$select count(*) from public.get_private_album(
        (select album_id from hard_album_fixture)
    )$$,
    array[0::bigint],
    'stale read target never switches to replacement album'
);
select throws_ok(
    $$select public.report_private_album(
        (select album_id from hard_album_fixture),
        'inappropriate_photo', 'Stale synthetic report', null
    )$$,
    'P0001',
    'INVALID_REPORT_TARGET',
    'stale report bound to deleting album cannot report replacement'
);
select throws_ok(
    $$select public.report_private_album(
        (select album_id from replacement_album_fixture),
        'inappropriate_photo', 'Empty synthetic report', null
    )$$,
    'P0001',
    'PRIVATE_ALBUM_REPORT_RETRY',
    'album report cannot succeed with zero evidence paths'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000801';
select results_eq(
    $$select count(*) from public.list_private_album_grants()
      where recipient_id = '00000000-0000-0000-0000-000000000803'::uuid$$,
    array[1::bigint],
    'failed stale and zero-evidence reports do not mutate replacement grant'
);
select results_eq(
    $$select count(*) from public.begin_private_album_deletion(
        (select album_id from hard_album_fixture)
      ) where delete_now is false$$,
    array[1::bigint],
    'retry bound to the old deleting id cannot target the replacement album'
);
select results_eq(
    $$select album_status from public.get_my_private_album()$$,
    $$values ('active'::text)$$,
    'owner RPC exposes only the replacement active album'
);
select throws_ok(
    $$select public.grant_private_album_access(
        (select album_id from public.get_my_private_album()),
        '00000000-0000-0000-0000-000000000802'
    )$$,
    'P0001',
    'ALBUM_ACCESS_REPORTED',
    'replacement album still cannot bypass the active report case'
);

set local role postgres;
set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
set local storage.allow_delete_query = 'true';
select throws_ok(
    $$delete from storage.objects
      where bucket_id = 'private-albums'
        and name = (select object_path from hard_reservation)$$,
    'P0001',
    'PRIVATE_ALBUM_EVIDENCE_HOLD',
    'even privileged physical deletion is blocked before hold_until'
);
select results_eq(
    $$select count(*) from public.get_private_album_cleanup_batch(100)
      where object_path = (select object_path from hard_reservation)$$,
    array[0::bigint],
    'cleanup leasing omits paths under evidence hold'
);

-- 33-37: moderation cannot be bypassed and finalize rejects active albums.
set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000803';
select lives_ok(
    $$select public.create_private_album('content-v1', true)$$,
    'moderation fixture creates an active album'
);

set local role postgres;
create temporary table moderated_album as
select id from private.private_albums
where owner_id = '00000000-0000-0000-0000-000000000803'::uuid
  and status = 'active';
grant select on moderated_album to service_role;
set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
select lives_ok(
    $$select public.moderate_private_album(
        (select id from moderated_album), 'removed_by_moderation'
    )$$,
    'service moderation removes the album'
);

set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000803';
select throws_ok(
    $$select public.create_private_album('content-v2', true)$$,
    'P0001',
    'PRIVATE_ALBUM_NOT_AVAILABLE',
    'owner cannot evade an album moderation decision by recreating it'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000804';
select lives_ok(
    $$select public.create_private_album('content-v1', true)$$,
    'finalize fixture creates an active album'
);
select throws_ok(
    $$select public.finalize_private_album_deletion(
        (select album_id from public.get_my_private_album())
    )$$,
    'P0001',
    'PRIVATE_ALBUM_NOT_DELETING',
    'finalize errors when only a non-deleting album exists'
);

-- 38-49: lease, explicit failure, exponential backoff and stale-token guard.
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000805';
select lives_ok(
    $$select public.create_private_album('content-v1', true)$$,
    'cleanup fixture creates an active album'
);
create temporary table cleanup_reservation as
select * from public.reserve_private_album_item(
    (select album_id from public.get_my_private_album()), 'image/jpeg'
);
grant select on cleanup_reservation to authenticated, service_role;

select results_eq(
    $$select delete_now, hold_until
      from public.mark_private_album_item_for_deletion(
          (select item_id from cleanup_reservation)
      )$$,
    $$values (true, null::timestamptz)$$,
    'unreported tombstone is immediately eligible for physical cleanup'
);

set local role postgres;
set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
create temporary table first_cleanup_lease as
select * from public.get_private_album_cleanup_batch(1);
grant select on first_cleanup_lease to service_role;

select results_eq(
    $$select count(*) from first_cleanup_lease
      where lease_token is not null and leased_until > now()$$,
    array[1::bigint],
    'cleanup worker receives one bounded lease token'
);
select results_eq(
    $$select count(*) from public.get_private_album_cleanup_batch(100)
      where object_path = (select object_path from cleanup_reservation)$$,
    array[0::bigint],
    'active lease prevents duplicate delivery to another worker'
);
select lives_ok(
    $$select public.fail_private_album_object_cleanup(
        (select object_path from first_cleanup_lease),
        (select lease_token from first_cleanup_lease),
        'STORAGE_UNAVAILABLE'
    )$$,
    'worker records a sanitized cleanup failure'
);

set local role postgres;
select results_eq(
    $$select lease_token is null, leased_until is null,
              next_attempt_at > now(), last_error_code
      from private.private_album_cleanup_queue
      where object_path = (select object_path from cleanup_reservation)$$,
    $$values (true, true, true, 'STORAGE_UNAVAILABLE'::text)$$,
    'failure releases the lease and schedules backoff'
);

set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
select results_eq(
    $$select count(*) from public.get_private_album_cleanup_batch(100)
      where object_path = (select object_path from cleanup_reservation)$$,
    array[0::bigint],
    'failed path does not monopolize the immediately ready queue'
);

set local role postgres;
update private.private_album_cleanup_queue
set next_attempt_at = now() - interval '1 second'
where object_path = (select object_path from cleanup_reservation);

set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
create temporary table second_cleanup_lease as
select * from public.get_private_album_cleanup_batch(1);
grant select on second_cleanup_lease to service_role;

select results_eq(
    $$select count(*) from second_cleanup_lease second
      join first_cleanup_lease first using (object_path)
      where second.lease_token <> first.lease_token$$,
    array[1::bigint],
    'retry receives a fresh lease token'
);
select throws_ok(
    $$select public.confirm_private_album_object_deleted(
        (select object_path from first_cleanup_lease),
        (select lease_token from first_cleanup_lease)
    )$$,
    'P0001',
    'CLEANUP_LEASE_NOT_OWNED',
    'stale worker cannot confirm a newer lease'
);
select results_eq(
    $$select public.confirm_private_album_object_deleted(
        (select object_path from second_cleanup_lease),
        (select lease_token from second_cleanup_lease)
    )$$,
    array[true],
    'current lease confirms idempotent absence from Storage'
);

set local role postgres;
select results_eq(
    $$select count(*) from private.private_album_cleanup_queue
      where object_path = (select object_path from cleanup_reservation)$$,
    array[0::bigint],
    'successful confirmation consumes the cleanup tombstone'
);
select results_eq(
    $$select count(*) from private.private_album_items
      where id = (select item_id from cleanup_reservation)$$,
    array[0::bigint],
    'successful confirmation removes deleting item metadata'
);
select ok(
    has_function_privilege(
        'service_role',
        'public.fail_private_album_object_cleanup(text,uuid,text)',
        'EXECUTE'
    ),
    'service worker has the explicit cleanup failure API'
);

-- A pending case remains authoritative after album/evidence FKs are gone.
set local role postgres;
update public.reports
set private_album_id = null,
    private_album_item_id = null
where reporter_id = '00000000-0000-0000-0000-000000000802'::uuid
  and reported_user_id = '00000000-0000-0000-0000-000000000801'::uuid;
delete from private.private_album_report_evidence evidence
where evidence.report_id in (
    select marker.report_id
    from private.private_album_report_markers marker
    where marker.owner_id = '00000000-0000-0000-0000-000000000801'::uuid
      and marker.reporter_id = '00000000-0000-0000-0000-000000000802'::uuid
);
set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000801';
select throws_ok(
    $$select public.grant_private_album_access(
        (select album_id from public.get_my_private_album()),
        '00000000-0000-0000-0000-000000000802'
    )$$,
    'P0001',
    'ALBUM_ACCESS_REPORTED',
    'pending marker blocks regrant after nullable album FK and evidence cleanup'
);

select * from finish();
rollback;
