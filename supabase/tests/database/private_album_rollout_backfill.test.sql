begin;

set local role postgres;

set local search_path = public, testing, extensions;
select plan(19);

-- Test-only access is transaction-scoped and rolled back at the end.
grant usage on schema testing to anon, authenticated, service_role;

select ok(
    to_regprocedure(
        'private.backfill_private_album_report_retention(timestamp with time zone)'
    ) is not null,
    'restricted rollout backfill exists'
);
select ok(
    not has_function_privilege(
        'authenticated',
        'private.backfill_private_album_report_retention(timestamp with time zone)',
        'EXECUTE'
    ),
    'authenticated cannot invoke the rollout backfill'
);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000901', 'rollout-owner@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000902', 'rollout-reporter@matcher.invalid', now(), '{}'::jsonb);

update public.accounts
set status = 'active',
    birth_year = 1995,
    terms_accepted_at = now(),
    terms_version = 'test-v1'
where id in (
    '00000000-0000-0000-0000-000000000901'::uuid,
    '00000000-0000-0000-0000-000000000902'::uuid
);

insert into public.profiles (id, display_name, age, bio, intent, region_code)
values
    ('00000000-0000-0000-0000-000000000901', 'Rollout owner', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000902', 'Rollout reporter', 31, '', 'Conhecer pessoas', 'br-test');

set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000901';

select lives_ok(
    $$select public.create_private_album('content-v1', true)$$,
    'legacy-state owner creates an album fixture'
);
create temporary table rollout_album as
select album_id from public.get_my_private_album();
grant select on rollout_album to postgres, authenticated, service_role;

create temporary table rollout_item as
select * from public.reserve_private_album_item(
    (select album_id from rollout_album), 'image/jpeg'
);
grant select on rollout_item to postgres, authenticated, service_role;

select lives_ok(
    $$insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
      select 'private-albums', object_path, auth.uid(), auth.uid()::text,
             '{"size":2048,"mimetype":"image/jpeg"}'::jsonb
      from rollout_item$$,
    'legacy-state object exists in Storage'
);
select lives_ok(
    $$select * from public.finalize_private_album_item(
        (select item_id from rollout_item)
    )$$,
    'legacy-state item is available'
);

-- This second object will be timestamped after the legacy full-album report.
-- It must remain outside the rollout evidence hold.
create temporary table rollout_late_item as
select * from public.reserve_private_album_item(
    (select album_id from rollout_album), 'image/jpeg'
);
grant select on rollout_late_item to postgres, authenticated, service_role;

select lives_ok(
    $$insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
      select 'private-albums', object_path, auth.uid(), auth.uid()::text,
             '{"size":2048,"mimetype":"image/jpeg"}'::jsonb
      from rollout_late_item$$,
    'post-report object fixture exists in Storage'
);
select lives_ok(
    $$select * from public.finalize_private_album_item(
        (select item_id from rollout_late_item)
    )$$,
    'post-report item fixture is available'
);
select lives_ok(
    $$select public.grant_private_album_access(
        (select album_id from rollout_album),
        '00000000-0000-0000-0000-000000000902'
    )$$,
    'legacy-state reporter has an album grant'
);

set local role postgres;

-- Simulate rows produced by the pre-hardening report implementation: report,
-- case, audit and revoked grant exist, but no marker/evidence row does.
insert into public.reports (
    id,
    reporter_id,
    reported_user_id,
    reason,
    details,
    private_album_id
)
values (
    '20000000-0000-4000-8000-000000000901',
    '00000000-0000-0000-0000-000000000902',
    '00000000-0000-0000-0000-000000000901',
    'inappropriate_photo',
    'Synthetic rollout fixture',
    (select album_id from rollout_album)
);

update private.private_album_items
set created_at = (
    select report.created_at + interval '1 second'
    from public.reports report
    where report.id = '20000000-0000-4000-8000-000000000901'::uuid
)
where id = (select item_id from rollout_late_item);
insert into public.moderation_cases (id, report_id)
values (
    '30000000-0000-4000-8000-000000000901',
    '20000000-0000-4000-8000-000000000901'
);
insert into public.audit_events (
    event_type,
    actor_id,
    subject_user_id,
    moderation_case_id
)
values (
    'private_album.reported',
    '00000000-0000-0000-0000-000000000902',
    '00000000-0000-0000-0000-000000000901',
    '30000000-0000-4000-8000-000000000901'
);
update private.private_album_grants
set revoked_at = now(),
    revoked_by = '00000000-0000-0000-0000-000000000902',
    revoke_reason = 'reported'
where album_id = (select album_id from rollout_album)
  and recipient_id = '00000000-0000-0000-0000-000000000902'::uuid;

select results_eq(
    $$select
        (select count(*) from private.private_album_report_markers),
        (select count(*) from private.private_album_report_evidence)$$,
    $$values (0::bigint, 0::bigint)$$,
    'pre-migration fixture starts without marker or evidence'
);

create temporary table rollout_clock as
select clock_timestamp() as started_at;

select results_eq(
    $$select marker_rows, evidence_rows
      from private.backfill_private_album_report_retention(
          (select started_at from rollout_clock)
      )$$,
    $$values (1, 1)$$,
    'backfill creates one proven marker and one existing-path evidence row'
);
select results_eq(
    $$select count(*)
      from private.private_album_report_evidence evidence
      where evidence.report_id = '20000000-0000-4000-8000-000000000901'::uuid
        and evidence.created_at >= (select started_at from rollout_clock)
        and evidence.hold_until >=
            (select started_at from rollout_clock) + interval '30 days'$$,
    array[1::bigint],
    'backfilled evidence receives a fresh minimum 30-day rollout hold'
);
select results_eq(
    $$select count(*)
      from private.private_album_report_evidence evidence
      where evidence.report_id = '20000000-0000-4000-8000-000000000901'::uuid
        and evidence.object_path = (
            select object_path from rollout_late_item
        )$$,
    array[0::bigint],
    'full-album backfill excludes items uploaded after the report'
);
select results_eq(
    $$select owner_id, reporter_id, moderation_case_id
      from private.private_album_report_markers
      where report_id = '20000000-0000-4000-8000-000000000901'::uuid$$,
    $$values (
        '00000000-0000-0000-0000-000000000901'::uuid,
        '00000000-0000-0000-0000-000000000902'::uuid,
        '30000000-0000-4000-8000-000000000901'::uuid
    )$$,
    'live report FKs establish the exact marker association'
);
select results_eq(
    $$select marker_rows, evidence_rows
      from private.backfill_private_album_report_retention(
          (select started_at from rollout_clock)
      )$$,
    $$values (0, 0)$$,
    'repeating the same rollout backfill is idempotent'
);

-- A nulled album FK can only be recovered through an exact case/actor/subject
-- audit correlation; no object path is guessed.
insert into public.reports (
    id, reporter_id, reported_user_id, reason, details
)
values (
    '20000000-0000-4000-8000-000000000902',
    '00000000-0000-0000-0000-000000000902',
    '00000000-0000-0000-0000-000000000901',
    'inappropriate_photo',
    'Synthetic nulled-FK fixture'
);
insert into public.moderation_cases (id, report_id)
values (
    '30000000-0000-4000-8000-000000000902',
    '20000000-0000-4000-8000-000000000902'
);
insert into public.audit_events (
    event_type, actor_id, subject_user_id, moderation_case_id
)
values (
    'private_album.reported',
    '00000000-0000-0000-0000-000000000902',
    '00000000-0000-0000-0000-000000000901',
    '30000000-0000-4000-8000-000000000902'
);

select results_eq(
    $$select marker_rows, evidence_rows
      from private.backfill_private_album_report_retention(
          (select started_at from rollout_clock)
      )$$,
    $$values (1, 0)$$,
    'exact audit correlation safely recovers a marker after FK nulling'
);
select results_eq(
    $$select count(*) from private.private_album_report_markers
      where report_id = '20000000-0000-4000-8000-000000000902'::uuid$$,
    array[1::bigint],
    'nulled-FK report receives one persistent marker'
);
select results_eq(
    $$select count(*) from private.private_album_report_evidence
      where report_id = '20000000-0000-4000-8000-000000000902'::uuid$$,
    array[0::bigint],
    'nulled-FK fallback never invents an evidence object path'
);

-- A lookalike audit event with a mismatched actor is deliberately ignored.
insert into public.reports (
    id, reporter_id, reported_user_id, reason, details
)
values (
    '20000000-0000-4000-8000-000000000903',
    '00000000-0000-0000-0000-000000000902',
    '00000000-0000-0000-0000-000000000901',
    'inappropriate_photo',
    'Synthetic ambiguous fixture'
);
insert into public.moderation_cases (id, report_id)
values (
    '30000000-0000-4000-8000-000000000903',
    '20000000-0000-4000-8000-000000000903'
);
insert into public.audit_events (
    event_type, actor_id, subject_user_id, moderation_case_id
)
values (
    'private_album.reported',
    '00000000-0000-0000-0000-000000000901',
    '00000000-0000-0000-0000-000000000901',
    '30000000-0000-4000-8000-000000000903'
);

select results_eq(
    $$select marker_rows, evidence_rows
      from private.backfill_private_album_report_retention(
          (select started_at from rollout_clock)
      )$$,
    $$values (0, 0)$$,
    'ambiguous audit event creates no backfill association'
);
select results_eq(
    $$select count(*) from private.private_album_report_markers
      where report_id = '20000000-0000-4000-8000-000000000903'::uuid$$,
    array[0::bigint],
    'mismatched audit actor remains unassociated'
);

select * from finish();
rollback;
