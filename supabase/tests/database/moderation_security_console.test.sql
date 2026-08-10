begin;
set local role postgres;
set local search_path = public, testing, extensions;
select plan(12);

select has_table('private', 'account_moderation_sanctions', 'sanctions are private');
select ok(not has_table_privilege('authenticated', 'private.account_moderation_sanctions', 'SELECT'), 'clients cannot inspect sanctions directly');
select has_function('public', 'get_moderation_console_overview', array[]::text[], 'overview RPC exists');
select has_function('public', 'moderation_console_action', array['text','uuid','uuid','uuid','uuid','text','integer'], 'normalized action RPC exists');

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values
 ('00000000-0000-0000-0000-000000000951','console-admin@matcher.invalid',now(),'{}'),
 ('00000000-0000-0000-0000-000000000952','console-reviewer@matcher.invalid',now(),'{}'),
 ('00000000-0000-0000-0000-000000000953','console-target@matcher.invalid',now(),'{}');
update public.accounts set status='active', birth_year=1995, terms_accepted_at=now(), terms_version='test-v1'
where id in ('00000000-0000-0000-0000-000000000951','00000000-0000-0000-0000-000000000952','00000000-0000-0000-0000-000000000953');
insert into public.profiles(id,display_name,age,bio,intent,region_code) values
 ('00000000-0000-0000-0000-000000000951','Console admin',31,'','Conhecer pessoas','br-test'),
 ('00000000-0000-0000-0000-000000000952','Console reviewer',30,'','Conhecer pessoas','br-test'),
 ('00000000-0000-0000-0000-000000000953','Console target',29,'','Conhecer pessoas','br-test');
insert into private.moderation_staff(user_id,staff_role) values
 ('00000000-0000-0000-0000-000000000951','admin'),
 ('00000000-0000-0000-0000-000000000952','reviewer');

set local role authenticated;
set local "request.jwt.claim.role"='authenticated';
set local "request.jwt.claim.sub"='00000000-0000-0000-0000-000000000952';
select is((public.get_moderation_console_overview()->>'role'),'reviewer','reviewer receives reviewer role');
select throws_ok($$select public.list_moderation_staff()$$,'42501','ADMIN_REQUIRED','reviewer cannot list staff');
select throws_ok($$select public.moderation_console_action('suspend_user','00000000-0000-0000-0000-000000000953')$$,'42501','ADMIN_REQUIRED','reviewer cannot sanction accounts');

set local "request.jwt.claim.sub"='00000000-0000-0000-0000-000000000951';
select is(public.moderation_console_action('suspend_user','00000000-0000-0000-0000-000000000953',null,null,null,'safety',24),'suspend_user','admin suspends account');
select is((select status::text from public.accounts where id='00000000-0000-0000-0000-000000000953'),'suspended','suspension changes authoritative account state');
select is(public.moderation_console_action('reactivate_user','00000000-0000-0000-0000-000000000953'),'reactivate_user','admin explicitly reactivates account');
select is((select status::text from public.accounts where id='00000000-0000-0000-0000-000000000953'),'active','reactivation restores active state');
select throws_ok($$select public.manage_moderation_staff('console-admin@matcher.invalid','reviewer',true)$$,'P0001','LAST_ACTIVE_ADMIN','last active admin cannot be demoted');

select * from finish();
rollback;
