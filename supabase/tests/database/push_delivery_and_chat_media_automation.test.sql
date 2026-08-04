begin;
set local role postgres;
set local search_path = public, testing, extensions;
select plan(34);
grant usage on schema testing to anon, authenticated, service_role;

select has_table('private', 'push_devices', 'push tokens live in private schema');
select has_table('private', 'notification_deliveries', 'per-device delivery state is private');
select has_function('public', 'register_push_device', array['uuid', 'text'], 'authenticated registration RPC exists');
select has_function('public', 'claim_notification_deliveries', array['integer'], 'service delivery claim exists');
select has_function('public', 'claim_chat_media_moderation', array['integer'], 'service moderation claim exists');
select ok(not has_table_privilege('authenticated', 'private.push_devices', 'SELECT'), 'users cannot read tokens');
select ok(not has_table_privilege('authenticated', 'private.notification_deliveries', 'SELECT'), 'users cannot read delivery leases');
select ok(not has_function_privilege('anon', 'public.register_push_device(uuid,text)', 'EXECUTE'), 'anonymous cannot register push');
select ok(has_function_privilege('authenticated', 'public.register_push_device(uuid,text)', 'EXECUTE'), 'authenticated can register own installation');

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000801', 'push-sender@matcher.invalid', now(), '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000802', 'push-recipient@matcher.invalid', now(), '{}'::jsonb);
update public.accounts
set status = 'active', birth_year = 1995, terms_accepted_at = now(), terms_version = 'dev-2026-07'
where id in ('00000000-0000-0000-0000-000000000801', '00000000-0000-0000-0000-000000000802');
insert into public.profiles (id, display_name, age, bio, intent, region_code)
values
    ('00000000-0000-0000-0000-000000000801', 'Push sender', 31, '', 'Conhecer pessoas', 'br-test'),
    ('00000000-0000-0000-0000-000000000802', 'Push recipient', 30, '', 'Conhecer pessoas', 'br-test');

set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000802';
set local "request.jwt.claim.role" = 'authenticated';
select lives_ok(
    $$select public.register_push_device(
        '81000000-0000-4000-8000-000000000001',
        'synthetic_fid_recipient_1234'
    )$$,
    'recipient registers a synthetic Firebase installation ID'
);
set local role postgres;
select results_eq(
    $$select count(*) from private.push_devices where user_id = '00000000-0000-0000-0000-000000000802' and active$$,
    array[1::bigint],
    'one active private Firebase installation ID is stored'
);
set local role authenticated;
select throws_ok(
    $$select public.register_push_device('81000000-0000-4000-8000-000000000002', 'invalid fid!')$$,
    'P0001', 'INVALID_FIREBASE_INSTALLATION',
    'malformed Firebase installation ID is rejected'
);

set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000801';
select lives_ok(
    $$select * from public.start_conversation(
        '00000000-0000-0000-0000-000000000802', 'Primeira mensagem push'
    )$$,
    'sender creates a conversation and neutral outbox'
);
set local role postgres;
create temporary table push_chat as
select id from public.conversations
where participant_a = '00000000-0000-0000-0000-000000000801'
  and participant_b = '00000000-0000-0000-0000-000000000802';
grant select on push_chat to authenticated, service_role;
select results_eq(
    'select count(*) from private.notification_deliveries',
    array[1::bigint],
    'outbox snapshots the active recipient device once'
);

set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
create temporary table claimed_push as
select * from public.claim_notification_deliveries(10);
select results_eq('select count(*) from claimed_push', array[1::bigint], 'worker claims one device delivery');
select results_eq(
    $$select payload ->> 'body' from claimed_push$$,
    array['Nova mensagem'::text],
    'claimed payload remains neutral'
);
set local role postgres;
select results_eq(
    $$select state from private.notification_deliveries where id = (select delivery_id from claimed_push)$$,
    array['sending'::text],
    'claim creates a sending lease'
);
set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
select is(
    public.complete_notification_delivery(
        (select delivery_id from claimed_push),
        '89999999-9999-4999-8999-999999999999', 'sent', null
    ),
    false,
    'wrong delivery lease cannot confirm push'
);
select is(
    public.complete_notification_delivery(
        (select delivery_id from claimed_push),
        (select lease_token from claimed_push), 'sent', null
    ),
    true,
    'matching lease confirms push'
);
set local role postgres;
select results_eq(
    'select state from private.notification_outbox',
    array['sent'::text],
    'completed device delivery completes its outbox'
);

set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000801';
select lives_ok(
    $$select public.send_message(
        (select id from push_chat), 'Segunda mensagem push',
        '82000000-0000-4000-8000-000000000001'
    )$$,
    'a later message creates a fresh device delivery'
);
set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
create temporary table claimed_invalid_push as
select * from public.claim_notification_deliveries(10);
select results_eq('select count(*) from claimed_invalid_push', array[1::bigint], 'worker claims the later delivery');
select is(
    public.complete_notification_delivery(
        (select delivery_id from claimed_invalid_push),
        (select lease_token from claimed_invalid_push), 'invalid', 'FCM_INVALID_INSTALLATION'
    ),
    true,
    'permanently invalid provider installation is finalized'
);
set local role postgres;
select results_eq(
    $$select active from private.push_devices where user_id = '00000000-0000-0000-0000-000000000802'$$,
    array[false],
    'permanently invalid installation is disabled'
);

set local role authenticated;
set local "request.jwt.claim.role" = 'authenticated';
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000801';
set local storage.operation = 'storage.object.upload';
select lives_ok(
    $$insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
      values (
        'chat-media',
        '00000000-0000-0000-0000-000000000801/' ||
          (select id::text from push_chat) ||
          '/83000000-0000-4000-8000-000000000001.jpg',
        auth.uid(), auth.uid()::text,
        '{"contentLength":2048,"mimetype":"image/jpeg"}'::jsonb
      )$$,
    'sender uploads a synthetic canonical chat object'
);
set local role postgres;
update storage.objects
set metadata = '{"size":2048,"mimetype":"image/jpeg"}'::jsonb
where bucket_id = 'chat-media'
  and name like '00000000-0000-0000-0000-000000000801/%';
set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000801';
select lives_ok(
    $$select * from public.send_photo_message(
        (select id from push_chat),
        '83000000-0000-4000-8000-000000000001',
        '00000000-0000-0000-0000-000000000801/' ||
          (select id::text from push_chat) ||
          '/83000000-0000-4000-8000-000000000001.jpg',
        'image/jpeg'
    )$$,
    'photo enters the automated moderation queue'
);
set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
create temporary table claimed_media as
select * from public.claim_chat_media_moderation(10);
select results_eq('select count(*) from claimed_media', array[1::bigint], 'worker claims one pending photo');
set local role postgres;
select results_eq(
    $$select automation_state from private.chat_media where message_id = (select message_id from claimed_media)$$,
    array['processing'::text],
    'moderation claim creates a processing lease'
);
set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
select is(
    public.complete_chat_media_moderation(
        (select message_id from claimed_media),
        '89999999-9999-4999-8999-999999999999', 'approved', null
    ),
    false,
    'wrong moderation lease cannot decide photo'
);
select is(
    public.complete_chat_media_moderation(
        (select message_id from claimed_media),
        (select lease_token from claimed_media), 'review', null
    ),
    true,
    'inconclusive result moves the photo to human review'
);
set local role postgres;
select results_eq(
    $$select status::text, automation_state from private.chat_media where message_id = (select message_id from claimed_media)$$,
    $$values ('pending'::text, 'review'::text)$$,
    'review keeps media private and pending'
);
set local role postgres;
update private.chat_media
set automation_state = 'queued', automation_next_attempt_at = now()
where message_id = (select message_id from claimed_media);
set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
create temporary table reclaimed_media as
select * from public.claim_chat_media_moderation(10);
select results_eq('select count(*) from reclaimed_media', array[1::bigint], 'explicitly requeued review can be claimed once');
select is(
    public.complete_chat_media_moderation(
        (select message_id from reclaimed_media),
        (select lease_token from reclaimed_media), 'approved', null
    ),
    true,
    'clearly safe result approves under its current lease'
);
set local role postgres;
select results_eq(
    $$select status::text, automation_state from private.chat_media where message_id = (select message_id from reclaimed_media)$$,
    $$values ('approved'::text, 'completed'::text)$$,
    'approval completes automation and releases recipient authorization'
);

select * from finish();
rollback;
