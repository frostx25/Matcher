begin;
set local role postgres;
set local search_path = public, testing, extensions;
select plan(11);
grant usage on schema testing to anon, authenticated, service_role;

select has_table('private', 'account_deletion_requests', 'deletion requests are private');
select has_function('public', 'request_account_deletion', array[]::text[], 'authenticated deletion RPC exists');

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000701', 'delete-owner@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000702', 'delete-peer@matcher.invalid', now(), '{}'::jsonb);
update public.accounts
set status = 'active', birth_year = 1995, terms_accepted_at = now(), terms_version = 'dev-2026-07'
where id in ('00000000-0000-0000-0000-000000000701', '00000000-0000-0000-0000-000000000702');
insert into public.profiles (id, display_name, age, bio, intent, region_code)
values
    ('00000000-0000-0000-0000-000000000701', 'Delete owner', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000702', 'Delete peer', 30, '', 'Conhecer pessoas', 'br-test');

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000701';
select lives_ok(
    $$select * from public.start_conversation(
        '00000000-0000-0000-0000-000000000702', 'Conversa sintética'
    )$$,
    'fixture starts an active conversation'
);
select is(public.request_account_deletion(), true, 'owner can request deletion');

set local role postgres;
select results_eq(
    $$select status::text from public.accounts where id = '00000000-0000-0000-0000-000000000701'$$,
    array['deleted'::text],
    'account becomes deleted immediately'
);
select results_eq(
    $$select discovery_visible from public.profiles where id = '00000000-0000-0000-0000-000000000701'$$,
    array[false],
    'deleted profile leaves discovery immediately'
);
select results_eq(
    $$select status::text from public.conversations where
      '00000000-0000-0000-0000-000000000701' in (participant_a, participant_b)$$,
    array['closed'::text],
    'active conversations close immediately'
);
select results_eq(
    $$select state from private.account_deletion_requests where user_id = '00000000-0000-0000-0000-000000000701'$$,
    array['pending'::text],
    'physical erasure is queued for the worker'
);
select results_eq(
    $$select metadata from public.audit_events where event_type = 'account_deletion_requested'
      and subject_user_id = '00000000-0000-0000-0000-000000000701'$$,
    array['{}'::jsonb],
    'deletion audit contains no sensitive payload'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000701';
select throws_ok(
    $$select public.send_message(gen_random_uuid(), 'Tentativa após exclusão')$$,
    'P0001', 'ACCOUNT_NOT_ACTIVE',
    'deleted account cannot send messages'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000702';
select results_eq(
    'select count(*) from public.conversations',
    array[0::bigint],
    'peer cannot read the closed conversation'
);

select * from finish();
rollback;
