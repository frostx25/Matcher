-- Synthetic development data only. Addresses use the reserved .invalid domain.
insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000101', 'pessoa-qa@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000102', 'maya@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000103', 'sam@matcher.invalid', now(), '{}'::jsonb)
on conflict (id) do nothing;

insert into public.accounts (
    id,
    status,
    birth_year,
    adult_verified_at,
    age_verification_status,
    age_verification_method,
    age_verification_policy_version,
    terms_accepted_at,
    terms_version
)
values
    ('00000000-0000-0000-0000-000000000101', 'active', 1995, now(), 'verified', 'test_fixture', 'test-v1', now(), 'dev-2026-07-31'),
    ('00000000-0000-0000-0000-000000000102', 'active', 1998, now(), 'verified', 'test_fixture', 'test-v1', now(), 'dev-2026-07-31'),
    ('00000000-0000-0000-0000-000000000103', 'active', 1992, now(), 'verified', 'test_fixture', 'test-v1', now(), 'dev-2026-07-31')
on conflict (id) do update set
    status = excluded.status,
    birth_year = excluded.birth_year,
    adult_verified_at = excluded.adult_verified_at,
    age_verification_status = excluded.age_verification_status,
    age_verification_method = excluded.age_verification_method,
    age_verification_policy_version = excluded.age_verification_policy_version,
    terms_accepted_at = excluded.terms_accepted_at,
    terms_version = excluded.terms_version;

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
    account.id,
    gen_random_uuid()::text,
    '30000000-0000-4000-8000-000000000001'::uuid,
    1,
    'verified',
    'DOCUMENT',
    'PASSIVE',
    'test-v1',
    now()
from public.accounts account
where account.id in (
    '00000000-0000-0000-0000-000000000101'::uuid,
    '00000000-0000-0000-0000-000000000102'::uuid,
    '00000000-0000-0000-0000-000000000103'::uuid
)
and not exists (
    select 1
    from public.age_verification_attempts attempt
    where attempt.user_id = account.id
      and attempt.status = 'verified'
);

insert into public.profiles (
    id,
    display_name,
    age,
    bio,
    intent,
    region_code,
    discovery_visible,
    verified
)
values
    (
        '00000000-0000-0000-0000-000000000101',
        'Pessoa QA',
        31,
        'Perfil sintético para desenvolvimento.',
        'Conhecer pessoas',
        'br-sp-demo',
        true,
        false
    ),
    (
        '00000000-0000-0000-0000-000000000102',
        'Maya',
        28,
        'Café sem pressa e música ao vivo.',
        'Conhecer pessoas',
        'br-sp-demo',
        true,
        true
    ),
    (
        '00000000-0000-0000-0000-000000000103',
        'Sam',
        34,
        'Bike, cozinha e conversa leve.',
        'Conversa leve',
        'br-sp-demo',
        true,
        false
    )
on conflict (id) do update set
    display_name = excluded.display_name,
    age = excluded.age,
    bio = excluded.bio,
    intent = excluded.intent,
    region_code = excluded.region_code,
    discovery_visible = excluded.discovery_visible,
    verified = excluded.verified;

insert into public.entitlements (user_id, plan, new_conversation_limit)
values
    ('00000000-0000-0000-0000-000000000101', 'free', 5),
    ('00000000-0000-0000-0000-000000000102', 'free', 5),
    ('00000000-0000-0000-0000-000000000103', 'free', 5)
on conflict (user_id) do update set
    plan = excluded.plan,
    new_conversation_limit = excluded.new_conversation_limit;

insert into public.conversations (
    id,
    participant_a,
    participant_b,
    started_by,
    status
)
values (
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000000103',
    '00000000-0000-0000-0000-000000000103',
    'active'
)
on conflict (id) do nothing;

insert into public.conversation_openings (conversation_id, opened_by)
values (
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000103'
)
on conflict (conversation_id) do nothing;

insert into public.messages (id, conversation_id, sender_id, body)
values (
    '20000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000103',
    'Oi! Esta é uma mensagem sintética para validar o chat direto.'
)
on conflict (id) do nothing;
