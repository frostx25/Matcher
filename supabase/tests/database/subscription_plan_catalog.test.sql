begin;
set local role postgres;
set local search_path = public, testing, extensions;
grant usage on schema testing to anon, authenticated, service_role;
select plan(10);

select has_table('public', 'subscription_plan_catalog', 'plan catalog exists');
select results_eq('select count(*) from public.subscription_plan_catalog', array[4::bigint], 'four plans exist');
select results_eq($$select new_conversation_limit from public.subscription_plan_catalog where plan = 'free'$$, array[5], 'Free limit is 5');
select results_eq($$select new_conversation_limit from public.subscription_plan_catalog where plan = 'extra'$$, array[20], 'Extra limit is 20');
select results_eq($$select new_conversation_limit from public.subscription_plan_catalog where plan = 'pro'$$, array[50], 'Pro limit is 50');
select results_eq($$select new_conversation_limit is null from public.subscription_plan_catalog where plan = 'unlimited'$$, array[true], 'Unlimited has no commercial quota');
select results_eq($$select private_album_count from public.subscription_plan_catalog where plan = 'unlimited'$$, array[3::smallint], 'Unlimited supports three albums');
select results_eq($$select priority_support from public.subscription_plan_catalog where plan = 'unlimited'$$, array[true], 'Unlimited includes priority support');

set local role anon;
select throws_ok('select * from public.subscription_plan_catalog', '42501', 'permission denied for table subscription_plan_catalog', 'anonymous cannot read catalog');

set local role authenticated;
select throws_ok(
    $$update public.subscription_plan_catalog set display_name = 'Alterado'$$,
    '42501',
    'permission denied for table subscription_plan_catalog',
    'authenticated user cannot mutate catalog'
);

select * from finish();
rollback;
