begin;

set local search_path = public, extensions;
select plan(36);

select has_table('public', 'gender_options', 'versioned gender catalog exists');
select has_table('private', 'profile_identities', 'identity data is private');
select has_table('private', 'profile_preferences', 'discovery preference is private');
select hasnt_column('public', 'profiles', 'looking_for_gender_ids', 'preference never lives on public profiles');
select ok(
    has_table_privilege('authenticated', 'public.gender_options', 'SELECT'),
    'authenticated onboarding can read the catalog'
);
select ok(
    not has_table_privilege('authenticated', 'private.profile_identities', 'SELECT'),
    'authenticated users cannot select private identities directly'
);
select ok(
    not has_table_privilege('authenticated', 'private.profile_preferences', 'SELECT'),
    'authenticated users cannot select private preferences directly'
);
select ok(
    has_function_privilege(
        'authenticated',
        'public.update_gender_settings(text[],text,boolean,text[])',
        'EXECUTE'
    ),
    'authenticated users can update only their own settings through RPC'
);
select ok(
    has_function_privilege(
        'authenticated',
        'public.get_discovery_profiles(uuid,integer,bigint)',
        'EXECUTE'
    ),
    'authenticated users can request authoritative discovery pages'
);
select results_eq(
    $$select id from public.gender_options order by sort_order$$,
    $$values
        ('woman'::text),
        ('man'::text),
        ('trans_woman'::text),
        ('trans_man'::text),
        ('non_binary'::text),
        ('genderqueer'::text),
        ('self_described'::text),
        ('prefer_not_to_say'::text),
        ('everyone'::text)$$,
    'catalog v1 contains the stable inclusive ids in display order'
);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000501', 'gender-viewer@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000502', 'gender-woman@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000503', 'gender-man@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000504', 'gender-hidden@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000505', 'gender-private@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000506', 'gender-nonbinary@matcher.invalid', now(), '{}'::jsonb);

update public.accounts
set status = 'active',
    birth_year = 1995,
    terms_accepted_at = now(),
    terms_version = 'test-v1'
where id between
    '00000000-0000-0000-0000-000000000501'::uuid and
    '00000000-0000-0000-0000-000000000506'::uuid;

insert into public.profiles (id, display_name, age, bio, intent, region_code)
values
    ('00000000-0000-0000-0000-000000000501', 'Viewer', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000502', 'Woman', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000503', 'Man', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000504', 'Hidden', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000505', 'Private', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000506', 'Nonbinary', 31, '', 'Conhecer pessoas', 'br-test');

select results_eq(
    $$select identity.gender_identity_ids, identity.gender_visible,
             preference.looking_for_gender_ids
        from private.profile_identities identity
        join private.profile_preferences preference using (user_id)
        where identity.user_id = '00000000-0000-0000-0000-000000000501'::uuid$$,
    $$values (
        array['prefer_not_to_say']::text[],
        false,
        array['everyone']::text[]
    )$$,
    'new and legacy profiles receive safe non-inferred defaults'
);

update private.profile_identities
set gender_identity_ids = array['woman'], gender_visible = true
where user_id = '00000000-0000-0000-0000-000000000502';
update private.profile_identities
set gender_identity_ids = array['man'], gender_visible = true
where user_id = '00000000-0000-0000-0000-000000000503';
update private.profile_identities
set gender_identity_ids = array['woman'], gender_visible = false
where user_id = '00000000-0000-0000-0000-000000000504';
update private.profile_identities
set gender_identity_ids = array['prefer_not_to_say'], gender_visible = true
where user_id = '00000000-0000-0000-0000-000000000505';
update private.profile_identities
set gender_identity_ids = array['non_binary'], gender_visible = true
where user_id = '00000000-0000-0000-0000-000000000506';

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000501';
set local "request.jwt.claim.role" = 'authenticated';

select throws_ok(
    $$select * from public.update_gender_settings(
        array[]::text[], null, true, array['woman']::text[]
    )$$,
    'P0001',
    'INVALID_GENDER_IDENTITY',
    'empty identity selection is rejected by the server'
);

select throws_ok(
    $$select * from public.update_gender_settings(
        array['woman']::text[], null, true,
        array['everyone','woman']::text[]
    )$$,
    'P0001',
    'INVALID_DISCOVERY_PREFERENCE',
    'everyone cannot be combined with another discovery preference'
);

select throws_ok(
    $$select * from public.update_gender_settings(
        array['self_described']::text[], null, true,
        array['woman']::text[]
    )$$,
    'P0001',
    'INVALID_GENDER_SELF_DESCRIPTION',
    'self-described identity requires explicit text'
);

select lives_ok(
    $$select * from public.update_gender_settings(
        array['man','self_described']::text[], 'Pessoa teste', true,
        array['woman','non_binary']::text[]
    )$$,
    'valid multi-select identity and private preference are saved atomically'
);

select results_eq(
    $$select gender_identity_ids, gender_self_description, gender_visible,
             looking_for_gender_ids, preference_cursor_version
        from public.get_my_gender_settings()$$,
    $$values (
        array['man','self_described']::text[],
        'Pessoa teste'::text,
        true,
        array['woman','non_binary']::text[],
        2::bigint
    )$$,
    'owner RPC returns all five settings fields without exposing another user'
);

select throws_ok(
    $$select * from private.profile_preferences$$,
    '42501',
    'permission denied for table profile_preferences',
    'authenticated SQL cannot bypass the owner RPC to read preferences'
);

select results_eq(
    $$select id::text from public.get_discovery_profiles(null, 2, null) order by id$$,
    $$values
        ('00000000-0000-0000-0000-000000000502'::text),
        ('00000000-0000-0000-0000-000000000506'::text)$$,
    'specific multi-select preference is applied before pagination'
);

select results_eq(
    $$select distinct has_more from public.get_discovery_profiles(null, 2, null)$$,
    $$values (false)$$,
    'incompatible and hidden rows do not create a false next page'
);

select results_eq(
    $$select count(*) from public.get_discovery_profiles(null, 50, null)
        where id = '00000000-0000-0000-0000-000000000504'::uuid$$,
    array[0::bigint],
    'a compatible but hidden identity cannot be inferred by a specific filter'
);

select lives_ok(
    $$select * from public.update_gender_settings(
        array['man','self_described']::text[], 'Pessoa teste', true,
        array['everyone']::text[]
    )$$,
    'owner can switch discovery to everyone'
);

select results_eq(
    $$select count(*) from public.get_discovery_profiles(null, 50, null)
        where id between
            '00000000-0000-0000-0000-000000000502'::uuid and
            '00000000-0000-0000-0000-000000000506'::uuid$$,
    array[5::bigint],
    'everyone includes every otherwise eligible profile'
);

select results_eq(
    $$select cardinality(gender_identity_ids), gender_self_description
        from public.get_discovery_profiles(null, 50, null)
        where id = '00000000-0000-0000-0000-000000000504'::uuid$$,
    $$values (0, null::text)$$,
    'everyone includes hidden identity without returning its value'
);

select results_eq(
    $$select cardinality(gender_identity_ids), gender_self_description
        from public.get_discovery_profiles(null, 50, null)
        where id = '00000000-0000-0000-0000-000000000505'::uuid$$,
    $$values (0, null::text)$$,
    'prefer-not-to-say remains undisclosed in discovery'
);

select throws_ok(
    $$select * from public.get_discovery_profiles(
        '00000000-0000-0000-0000-000000000502'::uuid,
        20,
        2
    )$$,
    'P0001',
    'DISCOVERY_CURSOR_STALE',
    'changing preference invalidates the previous cursor version'
);

select lives_ok(
    $$select public.block_user('00000000-0000-0000-0000-000000000503')$$,
    'viewer can block one discovery candidate'
);

select results_eq(
    $$select count(*) from public.get_discovery_profiles(null, 50, null)
        where id = '00000000-0000-0000-0000-000000000503'::uuid$$,
    array[0::bigint],
    'blocked candidate is excluded by the authoritative query'
);

select results_eq(
    $$select count(*) from public.profiles
        where id = '00000000-0000-0000-0000-000000000502'::uuid$$,
    array[1::bigint],
    'public profile access contains no private preference column or payload'
);

select lives_ok(
    $$select * from public.update_gender_settings(
        array['prefer_not_to_say']::text[], 'ignored', true,
        array['everyone']::text[]
    )$$,
    'prefer-not-to-say is normalized to a hidden identity'
);

select results_eq(
    $$select gender_identity_ids, gender_self_description, gender_visible
        from public.get_my_gender_settings()$$,
    $$values (array['prefer_not_to_say']::text[], null::text, false)$$,
    'prefer-not-to-say is exclusive, hidden and has no custom description'
);

reset role;
select results_eq(
    $$select count(*) from public.audit_events
        where metadata::text ~ '(woman|non_binary|Pessoa teste|everyone)'$$,
    array[0::bigint],
    'audit metadata never stores identity, custom text or discovery preference'
);

update public.accounts
set status = 'suspended'
where id = '00000000-0000-0000-0000-000000000506';

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000501';
set local "request.jwt.claim.role" = 'authenticated';

select results_eq(
    $$select count(*) from public.get_discovery_profiles(null, 50, null)
        where id = '00000000-0000-0000-0000-000000000506'::uuid$$,
    array[0::bigint],
    'suspended candidate is excluded independently of gender preference'
);

reset role;
update public.accounts
set status = 'suspended'
where id = '00000000-0000-0000-0000-000000000501';

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000501';
set local "request.jwt.claim.role" = 'authenticated';

select throws_ok(
    $$select * from public.get_discovery_profiles(null, 20, null)$$,
    'P0001',
    'ACCOUNT_NOT_ACTIVE',
    'suspended viewer cannot query discovery'
);

select ok(
    not has_function_privilege(
        'anon',
        'public.get_discovery_profiles(uuid,integer,bigint)',
        'EXECUTE'
    ),
    'anonymous role cannot call discovery'
);

select ok(
    to_regprocedure(
        'public.complete_onboarding(integer,text,text,text,boolean,text,text)'
    ) is null,
    'legacy onboarding signature cannot bypass required gender choices'
);

select results_eq(
    $$select count(*) from public.gender_options
        where id in ('trans_woman','trans_man','genderqueer')
          and identity_selectable and preference_selectable$$,
    array[3::bigint],
    'inclusive catalog additions are selectable for identity and preference'
);

select * from finish();
rollback;
