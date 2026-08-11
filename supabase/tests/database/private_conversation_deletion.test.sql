begin;
set local role postgres;
set local search_path = public, testing, extensions;
select plan(8);

select has_column('public','conversation_user_states','deleted_at','deletion is private per participant');
select has_function('public','set_conversation_deleted',array['uuid','boolean'],'delete RPC exists');
select has_function('public','list_deleted_conversation_ids',array[]::text[],'deleted list RPC exists');
select function_privs_are('public','set_conversation_deleted',array['uuid','boolean'],'anon',array[]::text[],'anonymous cannot delete a conversation');
select function_privs_are('public','set_conversation_deleted',array['uuid','boolean'],'authenticated',array['EXECUTE'],'participant can request deletion');
select trigger_is('public','messages','messages_restore_deleted_conversation','private','restore_conversation_for_new_message','new message restores recipient conversation');
select col_is_fk('public','conversation_user_states','conversation_id','deleted state stays attached to a conversation');
select col_is_fk('public','conversation_user_states','user_id','deleted state stays attached to a participant');

select * from finish();
rollback;

