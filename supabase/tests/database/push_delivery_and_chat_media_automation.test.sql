begin;
set local role postgres;
set local search_path = public, testing, extensions;
select plan(35);
grant usage on schema testing to anon, authenticated, service_role;

select has_table('private', 'push_devices', 'push tokens live in private schema');
select has_table('private', 'notification_deliveries', 'per-device delivery state is private');
select has_function('public', 'register_push_device', array['uuid', 'text'], 'authenticated registration RPC exists');
select has_function('public', 'claim_notification_deliveries', array['integer'], 'service delivery claim exists');
select has_function('public', 'claim_profile_photo_moderation', array['integer'], 'profile-photo moderation claim exists');
select ok(not has_table_privilege('authenticated', 'private.push_devices', 'SELECT'), 'users cannot read tokens');
select ok(not has_table_privilege('authenticated', 'private.notification_deliveries', 'SELECT'), 'users cannot read delivery leases');
select ok(not has_function_privilege('anon', 'public.register_push_device(uuid,text)', 'EXECUTE'), 'anonymous cannot register push');
select ok(has_function_privilege('authenticated', 'public.register_push_device(uuid,text)', 'EXECUTE'), 'authenticated can register own installation');
select ok(not has_function_privilege('service_role', 'public.claim_chat_media_moderation(integer)', 'EXECUTE'), 'chat photos cannot enter automated moderation');

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
        'profile-photos',
        '00000000-0000-0000-0000-000000000801/83000000-0000-4000-8000-000000000001.jpg',
        auth.uid(), auth.uid()::text,
        '{"size":2048,"mimetype":"image/jpeg"}'::jsonb
      )$$,
    'owner uploads a synthetic canonical profile photo'
);
set local role postgres;
update storage.objects
set metadata = '{"size":2048,"mimetype":"image/jpeg"}'::jsonb
where bucket_id = 'profile-photos'
  and name = '00000000-0000-0000-0000-000000000801/83000000-0000-4000-8000-000000000001.jpg';
set local role authenticated;
set local "request.jwt.claim.sub" = '00000000-0000-0000-0000-000000000801';
select lives_ok(
    $$select * from public.submit_profile_photo(
        '00000000-0000-0000-0000-000000000801/83000000-0000-4000-8000-000000000001.jpg'
    )$$,
    'single profile photo enters the automated moderation queue'
);
set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
create temporary table claimed_media as
select * from public.claim_profile_photo_moderation(10);
select results_eq('select count(*) from claimed_media', array[1::bigint], 'worker claims one pending profile photo');
set local role postgres;
select results_eq(
    $$select automation_state from private.profile_photo_submissions where user_id = (select profile_id from claimed_media)$$,
    array['processing'::text],
    'profile-photo claim creates a processing lease'
);
set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
select is(
    public.complete_profile_photo_moderation(
        (select profile_id from claimed_media),
        '89999999-9999-4999-8999-999999999999', 'approved', null
    ),
    false,
    'wrong moderation lease cannot decide the profile photo'
);
select is(
    public.complete_profile_photo_moderation(
        (select profile_id from claimed_media),
        (select lease_token from claimed_media), 'review', null
    ),
    true,
    'inconclusive result moves only the profile photo to human review'
);
set local role postgres;
select results_eq(
    $$select status::text, automation_state from private.profile_photo_submissions where user_id = (select profile_id from claimed_media)$$,
    $$values ('pending'::text, 'review'::text)$$,
    'review keeps the profile candidate private and pending'
);
update private.profile_photo_submissions
set automation_state = 'queued', automation_next_attempt_at = now()
where user_id = (select profile_id from claimed_media);
set local role service_role;
set local "request.jwt.claim.role" = 'service_role';
create temporary table reclaimed_media as
select * from public.claim_profile_photo_moderation(10);
select results_eq('select count(*) from reclaimed_media', array[1::bigint], 'explicitly requeued profile review can be claimed once');
select is(
    public.complete_profile_photo_moderation(
        (select profile_id from reclaimed_media),
        (select lease_token from reclaimed_media), 'approved', null
    ),
    true,
    'clearly safe profile result approves under its current lease'
);
set local role postgres;
select results_eq(
    $$select submission.status::text, submission.automation_state, profile.avatar_path
        from private.profile_photo_submissions submission
        join public.profiles profile on profile.id = submission.user_id
       where submission.user_id = (select profile_id from reclaimed_media)$$,
    $$values (
        'approved'::text,
        'completed'::text,
        '00000000-0000-0000-0000-000000000801/83000000-0000-4000-8000-000000000001.jpg'::text
    )$$,
    'approval completes profile automation and promotes the single public photo'
);

select * from finish();
rollback;
