begin;

set local search_path = public, extensions;
select plan(30);

select has_table('public', 'accounts', 'accounts table exists');
select has_table('public', 'conversations', 'conversations table exists');
select has_table('public', 'messages', 'messages table exists');
select has_table('public', 'conversation_openings', 'conversation openings table exists');
select hasnt_column('public', 'profiles', 'birth_date', 'public profiles never expose birth date');

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000201', 'sender@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000202', 'recipient@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000203', 'outsider@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000204', 'target-1@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000205', 'target-2@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000206', 'target-3@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000207', 'target-4@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000208', 'target-5@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000209', 'minor@matcher.invalid', now(), '{}'::jsonb);

update public.accounts
set status = 'active',
    birth_year = 1995,
    adult_verified_at = now(),
    age_verification_status = 'verified',
    age_verification_method = 'test_fixture',
    age_verification_policy_version = 'test-v1',
    terms_accepted_at = now(),
    terms_version = 'dev-2026-07'
where id between
    '00000000-0000-0000-0000-000000000201'::uuid and
    '00000000-0000-0000-0000-000000000209'::uuid;

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
select
    id,
    gen_random_uuid()::text,
    '30000000-0000-4000-8000-000000000001'::uuid,
    1,
    'verified',
    'DOCUMENT',
    'PASSIVE',
    'test-v1',
    now()
from public.accounts
where id between
    '00000000-0000-0000-0000-000000000201'::uuid and
    '00000000-0000-0000-0000-000000000209'::uuid;

delete from public.age_verification_attempts
where user_id in (
    '00000000-0000-0000-0000-000000000201'::uuid,
    '00000000-0000-0000-0000-000000000202'::uuid
);

update public.accounts
set adult_verified_at = null,
    age_verification_status = 'not_started',
    age_verification_method = null,
    age_verification_policy_version = null
where id in (
    '00000000-0000-0000-0000-000000000201'::uuid,
    '00000000-0000-0000-0000-000000000202'::uuid
);

update public.accounts
set birth_year = 2010
where id = '00000000-0000-0000-0000-000000000209';

insert into public.profiles (id, display_name, age, bio, intent, region_code)
values
    ('00000000-0000-0000-0000-000000000201', 'Sender', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000202', 'Recipient', 30, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000203', 'Outsider', 29, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000204', 'Target 1', 28, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000205', 'Target 2', 27, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000206', 'Target 3', 26, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000207', 'Target 4', 25, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000208', 'Target 5', 24, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000209', 'Minor fixture', 18, '', 'Conhecer pessoas', 'br-test');

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000201';

select results_eq(
    $$select was_created, remaining_quota from public.start_conversation(
        '00000000-0000-0000-0000-000000000202',
        'Oi, tudo bem?'
    )$$,
    $$values (true, 4)$$,
    'unverified active adults can start a conversation and consume one opening'
);

select results_eq(
    'select count(*) from public.conversations',
    array[1::bigint],
    'sender sees the new conversation immediately'
);

select results_eq(
    'select count(*) from public.messages',
    array[1::bigint],
    'first message is persisted in the same operation'
);

select results_eq(
    $$select was_created, remaining_quota from public.start_conversation(
        '00000000-0000-0000-0000-000000000202',
        'Segunda mensagem'
    )$$,
    $$values (false, 4)$$,
    'existing pair reuses the conversation without consuming quota'
);

select results_eq(
    'select count(*) from public.conversations',
    array[1::bigint],
    'existing pair never creates a duplicate conversation'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000203';
select results_eq(
    'select count(*) from public.conversations',
    array[0::bigint],
    'unrelated user cannot read a conversation'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000202';
select results_eq(
    'select count(*) from public.conversations',
    array[1::bigint],
    'recipient sees the conversation without accepting it'
);

select throws_ok(
    $$insert into public.messages (conversation_id, sender_id, body)
      values (
        (select id from public.conversations limit 1),
        '00000000-0000-0000-0000-000000000202',
        'direct write attempt'
      )$$,
    '42501',
    'permission denied for table messages',
    'client cannot bypass the message RPC'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000201';
select lives_ok(
    $$select * from public.start_conversation(
        '00000000-0000-0000-0000-000000000204', 'Oi 1'
    )$$,
    'second opening succeeds'
);
select lives_ok(
    $$select * from public.start_conversation(
        '00000000-0000-0000-0000-000000000205', 'Oi 2'
    )$$,
    'third opening succeeds'
);
select lives_ok(
    $$select * from public.start_conversation(
        '00000000-0000-0000-0000-000000000206', 'Oi 3'
    )$$,
    'fourth opening succeeds'
);
select lives_ok(
    $$select * from public.start_conversation(
        '00000000-0000-0000-0000-000000000207', 'Oi 4'
    )$$,
    'fifth opening succeeds'
);

select throws_ok(
    $$select * from public.start_conversation(
        '00000000-0000-0000-0000-000000000208', 'Sexta abertura'
    )$$,
    'P0001',
    'CHAT_QUOTA_EXHAUSTED',
    'sixth opening is rejected by the server'
);

select results_eq(
    'select remaining_count from public.get_chat_quota()',
    array[0],
    'server reports zero remaining openings'
);

select throws_ok(
    $$select * from public.start_conversation(
        '00000000-0000-0000-0000-000000000209', 'Contato inválido'
    )$$,
    'P0001',
    'RECIPIENT_NOT_AVAILABLE',
    'an account below 18 cannot participate even if its status was set incorrectly'
);

select lives_ok(
    $$select public.send_message(
        (select id from public.conversations
          where '00000000-0000-0000-0000-000000000202' in (participant_a, participant_b)),
        'Mensagem em conversa existente'
    )$$,
    'active conversation accepts another message with exhausted opening quota'
);

select results_eq(
    'select remaining_count from public.get_chat_quota()',
    array[0],
    'active messages do not consume new openings'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000202';
select lives_ok(
    $$select public.block_user('00000000-0000-0000-0000-000000000201')$$,
    'recipient can block the sender'
);

select results_eq(
    'select count(*) from public.conversations',
    array[0::bigint],
    'blocked conversation is hidden from the blocker'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000201';
select throws_ok(
    $$select public.send_message(
        (select id from public.conversations where participant_b =
            '00000000-0000-0000-0000-000000000202' limit 1),
        'Mensagem bloqueada'
    )$$,
    'P0001',
    'CHAT_NOT_AVAILABLE',
    'blocked sender cannot continue messaging'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000204';
select lives_ok(
    $$select public.report_user(
        '00000000-0000-0000-0000-000000000201',
        'spam',
        'Conteúdo sintético para teste',
        (select id from public.conversations where
            '00000000-0000-0000-0000-000000000201' in (participant_a, participant_b)),
        null
    )$$,
    'participant can report an active conversation'
);

select results_eq(
    'select count(*) from public.reports',
    array[1::bigint],
    'reporter can see the created report'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000201';
select throws_ok(
    $$select public.send_message(
        (select id from public.conversations where participant_b =
            '00000000-0000-0000-0000-000000000204' limit 1),
        'Mensagem após denúncia'
    )$$,
    'P0001',
    'CHAT_NOT_AVAILABLE',
    'reported user cannot continue messaging the reporter'
);

reset role;
update public.accounts
set status = 'suspended',
    age_verification_status = 'not_started',
    age_verification_method = null,
    age_verification_policy_version = null,
    adult_verified_at = null
where id = '00000000-0000-0000-0000-000000000201';

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000201';
set local "request.jwt.claim.role" = 'authenticated';

select results_eq(
    'select count(*) from public.conversations',
    array[0::bigint],
    'suspended account cannot read existing conversations'
);

select throws_ok(
    $$select public.send_message(
        '10000000-0000-0000-0000-000000000099',
        'Mensagem sem verificação'
    )$$,
    'P0001',
    'ACCOUNT_NOT_ACTIVE',
    'suspended account cannot send messages'
);

select * from finish();
rollback;
