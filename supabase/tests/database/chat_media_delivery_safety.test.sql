begin;

set local role postgres;
set local search_path = public, testing, extensions;
select plan(25);

grant usage on schema testing to anon, authenticated, service_role;

select has_table('public', 'conversation_user_states', 'per-user chat state exists');
select has_table('private', 'chat_media', 'chat media metadata is private');
select has_table('private', 'notification_outbox', 'notification outbox is private');
select has_column('public', 'messages', 'client_message_id', 'messages carry an idempotency key');
select has_column('public', 'messages', 'read_at', 'messages carry authoritative read state');

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000601', 'chat-sender@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000602', 'chat-recipient@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000603', 'chat-outsider@matcher.invalid', now(), '{}'::jsonb);

update public.accounts
set status = 'active', birth_year = 1995, terms_accepted_at = now(), terms_version = 'dev-2026-07'
where id in (
    '00000000-0000-0000-0000-000000000601',
    '00000000-0000-0000-0000-000000000602',
    '00000000-0000-0000-0000-000000000603'
);

insert into public.profiles (id, display_name, age, bio, intent, region_code)
values
    ('00000000-0000-0000-0000-000000000601', 'Chat sender', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000602', 'Chat recipient', 30, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000603', 'Chat outsider', 29, '', 'Conhecer pessoas', 'br-test');

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000601';
set local "request.jwt.claim.role" = 'authenticated';

select lives_ok(
    $$select * from public.start_conversation(
        '00000000-0000-0000-0000-000000000602', 'Primeira mensagem sintética'
    )$$,
    'sender starts the active conversation'
);

set local role postgres;
create temporary table chat_context as
select id from public.conversations
where participant_a = '00000000-0000-0000-0000-000000000601'
  and participant_b = '00000000-0000-0000-0000-000000000602';
grant select on table chat_context to authenticated, service_role;
set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000601';

select results_eq(
    'select count(*) from public.conversation_user_states where conversation_id = (select id from chat_context)',
    array[1::bigint],
    'RLS exposes only the sender state'
);

set local role postgres;
select results_eq(
    'select count(*) from public.conversation_user_states where conversation_id = (select id from chat_context)',
    array[2::bigint],
    'conversation trigger creates both participant states'
);

select results_eq(
    $$select payload ->> 'body' from private.notification_outbox where recipient_id = '00000000-0000-0000-0000-000000000602'$$,
    array['Nova mensagem'::text],
    'outbox contains only the neutral preview'
);

select is(
    (select payload ? 'message_body' or payload ? 'media_path' from private.notification_outbox where recipient_id = '00000000-0000-0000-0000-000000000602'),
    false,
    'outbox never stores message text or media path'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000602';
set local "request.jwt.claim.role" = 'authenticated';

select is(public.mark_chat_delivered(), 1, 'recipient delivery acknowledgement updates one message');
select results_eq(
    'select unread_count from public.get_chat_user_states()',
    array[1],
    'server calculates one unread message'
);
select is(
    public.set_conversation_muted((select id from chat_context), true),
    true,
    'recipient can mute their conversation'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000601';
select lives_ok(
    $$select public.send_message(
        (select id from chat_context),
        'Mensagem sem push',
        '70000000-0000-4000-8000-000000000002'
    )$$,
    'muting does not block message delivery'
);

set local role postgres;
select results_eq(
    $$select count(*) from private.notification_outbox where recipient_id = '00000000-0000-0000-0000-000000000602'$$,
    array[1::bigint],
    'muted recipient receives no additional outbox item'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000602';
select is(
    public.mark_conversation_read((select id from chat_context)),
    2,
    'opening the conversation marks both received messages read'
);
select results_eq(
    'select unread_count from public.get_chat_user_states()',
    array[0],
    'read acknowledgement clears only this conversation counter'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000601';
set local storage.operation = 'storage.object.upload';
select lives_ok(
    $$insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
      values (
        'chat-media',
        '00000000-0000-0000-0000-000000000601/' ||
          (select id::text from chat_context) ||
          '/70000000-0000-4000-8000-000000000001.jpg',
        auth.uid(), auth.uid()::text,
        '{"contentLength":2048,"mimetype":"image/jpeg"}'::jsonb
      )$$,
    'authorized sender uploads only to the canonical conversation path'
);

set local role postgres;
update storage.objects
set metadata = '{"size":2048,"mimetype":"image/jpeg"}'::jsonb
where bucket_id = 'chat-media'
  and name like '00000000-0000-0000-0000-000000000601/%';

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000601';
select lives_ok(
    $$select * from public.send_photo_message(
        (select id from chat_context),
        '70000000-0000-4000-8000-000000000001',
        '00000000-0000-0000-0000-000000000601/' ||
          (select id::text from chat_context) ||
          '/70000000-0000-4000-8000-000000000001.jpg',
        'image/jpeg'
    )$$,
    'sender finalizes the private photo message'
);

select lives_ok(
    $$select * from public.send_photo_message(
        (select id from chat_context),
        '70000000-0000-4000-8000-000000000001',
        '00000000-0000-0000-0000-000000000601/' ||
          (select id::text from chat_context) ||
          '/70000000-0000-4000-8000-000000000001.jpg',
        'image/jpeg'
    )$$,
    'same photo key is safely repeatable'
);

select results_eq(
    $$select count(*) from public.messages where kind = 'photo' and sender_id = '00000000-0000-0000-0000-000000000601'$$,
    array[1::bigint],
    'idempotent retry creates one photo message'
);

set local role postgres;
create temporary table photo_context as
select id from public.messages
where kind = 'photo' and sender_id = '00000000-0000-0000-0000-000000000601';
grant select on table photo_context to authenticated, service_role;

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000602';
select results_eq(
    $$select count(*) from public.authorize_chat_media(
        (select id from photo_context)
      )$$,
    array[1::bigint],
    'recipient can authorize an immediately available private chat photo'
);

set local role service_role;
select throws_ok(
    $$select * from public.claim_chat_media_moderation(10)$$,
    '42501',
    'permission denied for function claim_chat_media_moderation',
    'automated provider cannot claim private chat photos'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000602';
select results_eq(
    $$select count(*) from public.authorize_chat_media(
        (select id from photo_context)
      )$$,
    array[1::bigint],
    'recipient can authorize only the approved photo'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000603';
select results_eq(
    $$select count(*) from public.list_chat_messages(
        (select id from chat_context)
      )$$,
    array[0::bigint],
    'outsider cannot list chat messages or media state'
);

select * from finish();
rollback;
