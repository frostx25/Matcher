begin;
set local role postgres;
set local search_path = public, testing, extensions;
select plan(13);

select has_column('public', 'messages', 'reply_to_message_id', 'messages support replies');
select has_table('private', 'message_reactions', 'private reactions table exists');
select has_table('private', 'chat_album_events', 'private album event table exists');
select has_function('public', 'send_message', array['uuid','text','uuid','uuid'], 'reply-aware send RPC exists');
select has_function('public', 'toggle_message_reaction', array['uuid'], 'reaction RPC exists');
select has_function('public', 'list_chat_messages', array['uuid'], 'message list RPC exists');
select function_privs_are(
    'public', 'send_message', array['uuid','text','uuid','uuid'], 'authenticated', array['EXECUTE'],
    'authenticated can send replies'
);
select function_privs_are(
    'public', 'toggle_message_reaction', array['uuid'], 'authenticated', array['EXECUTE'],
    'authenticated can toggle reactions'
);
select function_privs_are(
    'public', 'toggle_message_reaction', array['uuid'], 'anon', array[]::text[],
    'anonymous cannot react'
);
select table_privs_are('private', 'message_reactions', 'authenticated', array[]::text[], 'reaction rows stay private');
select table_privs_are('private', 'chat_album_events', 'authenticated', array[]::text[], 'album events stay private');
select col_is_fk('public', 'messages', 'reply_to_message_id', 'reply reference is a foreign key');
select trigger_is(
    'private', 'private_album_grants', 'private_album_grants_emit_chat_event',
    'private', 'emit_album_chat_event', 'album grants emit conversation events'
);

select * from finish();
rollback;
