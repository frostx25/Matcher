begin;

set local role postgres;
set local search_path = public, testing, extensions;
select plan(19);

grant usage on schema testing to anon, authenticated, service_role;

select has_table('private', 'profile_favorites', 'favorites are stored privately');
select has_table('private', 'profile_hides', 'hides are stored privately');
select has_column('public', 'profiles', 'last_active_at', 'profiles have server presence input');
select has_column('public', 'profiles', 'show_activity_status', 'activity visibility is explicit');
select ok(not has_table_privilege('authenticated', 'private.profile_favorites', 'SELECT'), 'favorites are not directly readable');
select ok(not has_table_privilege('authenticated', 'private.profile_hides', 'SELECT'), 'hides are not directly readable');
select ok(has_function_privilege('authenticated', 'public.set_profile_favorite(uuid,boolean)', 'EXECUTE'), 'favorite RPC is available');
select ok(has_function_privilege('authenticated', 'public.hide_profile(uuid)', 'EXECUTE'), 'hide RPC is available');
select ok(has_function_privilege('authenticated', 'public.get_favorite_profiles(integer)', 'EXECUTE'), 'favorite list RPC is available');

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
    ('00000000-0000-4000-8000-000000000801', 'discovery-viewer@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-4000-8000-000000000802', 'discovery-target@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-4000-8000-000000000803', 'discovery-hidden@matcher.invalid', now(), '{}'::jsonb);

update public.accounts
   set status = 'active', birth_year = 1995, terms_accepted_at = now(), terms_version = 'test-v1'
 where id in (
    '00000000-0000-4000-8000-000000000801'::uuid,
    '00000000-0000-4000-8000-000000000802'::uuid,
    '00000000-0000-4000-8000-000000000803'::uuid
 );

insert into public.profiles (id, display_name, age, bio, intent, region_code, last_active_at)
values
    ('00000000-0000-4000-8000-000000000801', 'Viewer', 31, '', 'Conversa', 'br-test', now()),
    ('00000000-0000-4000-8000-000000000802', 'Target', 31, '', 'Conversa', 'br-test', now()),
    ('00000000-0000-4000-8000-000000000803', 'Hidden', 31, '', 'Conversa', 'br-test', now());

set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-4000-8000-000000000801';

select is(public.set_profile_favorite('00000000-0000-4000-8000-000000000802', true), true, 'target can be favorited');
select results_eq(
    $$select id, is_favorite from public.get_favorite_profiles(20)$$,
    $$values ('00000000-0000-4000-8000-000000000802'::uuid, true)$$,
    'favorite list returns only the viewer favorite'
);
select results_eq(
    $$select is_favorite, activity_status from public.get_discovery_profiles(null, 20, null) where id = '00000000-0000-4000-8000-000000000802'::uuid$$,
    $$values (true, 'online'::text)$$,
    'discovery exposes own favorite state and coarse presence'
);

select is(public.set_activity_visibility(false), false, 'viewer can hide own activity');
select is(public.hide_profile('00000000-0000-4000-8000-000000000802'), true, 'viewer can hide target');
select is_empty(
    $$select id from public.get_discovery_profiles(null, 20, null) where id = '00000000-0000-4000-8000-000000000802'::uuid$$,
    'hidden target leaves discovery immediately'
);
select is_empty(
    $$select id from public.get_favorite_profiles(20)$$,
    'hiding also removes the one-way favorite'
);
select is(public.unhide_profile('00000000-0000-4000-8000-000000000802'), true, 'viewer can unhide target');

set local "request.jwt.claim.sub" = '00000000-0000-4000-8000-000000000802';
select is(public.hide_profile('00000000-0000-4000-8000-000000000801'), true, 'target can hide viewer reciprocally');

set local "request.jwt.claim.sub" = '00000000-0000-4000-8000-000000000801';
select is_empty(
    $$select id from public.get_discovery_profiles(null, 20, null) where id = '00000000-0000-4000-8000-000000000802'::uuid$$,
    'a hide from either side removes both profiles from discovery'
);

select * from finish();
rollback;
