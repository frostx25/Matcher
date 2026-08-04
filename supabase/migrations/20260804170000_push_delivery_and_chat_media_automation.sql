-- FCM device registration, per-device delivery leases and cautious automated
-- triage for pending chat photos. Provider credentials remain outside Postgres.

create table private.push_devices (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.accounts (id) on delete cascade,
    installation_id uuid not null,
    firebase_installation_id text not null unique,
    platform text not null default 'android' check (platform = 'android'),
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    unique (user_id, installation_id),
    constraint push_device_fid_shape check (
        char_length(firebase_installation_id) between 11 and 128
        and firebase_installation_id ~ '^[A-Za-z0-9_\-]+$'
    )
);

create table private.notification_deliveries (
    id bigint generated always as identity primary key,
    outbox_id bigint not null references private.notification_outbox (id) on delete cascade,
    device_id uuid not null references private.push_devices (id) on delete cascade,
    state text not null default 'pending'
        check (state in ('pending', 'sending', 'sent', 'failed', 'discarded')),
    attempts integer not null default 0 check (attempts between 0 and 10),
    next_attempt_at timestamptz not null default now(),
    lease_token uuid,
    leased_until timestamptz,
    last_error_code text check (
        last_error_code is null or last_error_code in (
            'FCM_TRANSIENT', 'FCM_AUTH', 'FCM_INVALID_INSTALLATION', 'NO_DEVICE'
        )
    ),
    sent_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (outbox_id, device_id),
    constraint notification_delivery_lease_pair check (
        (lease_token is null and leased_until is null)
        or (lease_token is not null and leased_until is not null)
    )
);

create index notification_deliveries_worker_idx
    on private.notification_deliveries (state, next_attempt_at, id);

alter table private.push_devices enable row level security;
alter table private.notification_deliveries enable row level security;
revoke all on table private.push_devices from anon, authenticated;
revoke all on table private.notification_deliveries from anon, authenticated;

create or replace function private.seed_notification_deliveries()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into private.notification_deliveries (outbox_id, device_id)
    select new.id, device.id
      from private.push_devices device
     where device.user_id = new.recipient_id
       and device.active
    on conflict do nothing;
    return new;
end;
$$;

create trigger notification_outbox_seed_devices
after insert on private.notification_outbox
for each row execute function private.seed_notification_deliveries();

create or replace function public.register_push_device(
    target_installation_id uuid,
    target_firebase_installation_id text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_fid text := btrim(target_firebase_installation_id);
    v_device_id uuid;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if not private.account_is_active(v_user) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE';
    end if;
    if target_installation_id is null
       or char_length(v_fid) not between 11 and 128
       or v_fid !~ '^[A-Za-z0-9_\-]+$' then
        raise exception using errcode = 'P0001', message = 'INVALID_FIREBASE_INSTALLATION';
    end if;

    perform pg_advisory_xact_lock(hashtextextended('push:' || v_fid, 0));
    delete from private.push_devices device
     where device.firebase_installation_id = v_fid
       and (device.user_id, device.installation_id) <> (v_user, target_installation_id);

    insert into private.push_devices (
        user_id, installation_id, firebase_installation_id, active, updated_at, last_seen_at
    )
    values (v_user, target_installation_id, v_fid, true, now(), now())
    on conflict (user_id, installation_id) do update
       set firebase_installation_id = excluded.firebase_installation_id,
           active = true,
           updated_at = now(),
           last_seen_at = now()
    returning id into v_device_id;

    update private.push_devices device
       set active = false, updated_at = now()
     where device.id in (
         select ranked.id
           from (
               select candidate.id,
                      row_number() over (order by candidate.last_seen_at desc, candidate.id) as position
                 from private.push_devices candidate
                where candidate.user_id = v_user and candidate.active
           ) ranked
          where ranked.position > 5
     );

    insert into private.notification_deliveries (outbox_id, device_id)
    select outbox.id, v_device_id
      from private.notification_outbox outbox
     where outbox.recipient_id = v_user
       and outbox.created_at >= now() - interval '5 minutes'
       and outbox.state in ('pending', 'failed')
    on conflict do nothing;
    return true;
end;
$$;

create or replace function public.unregister_push_device(target_installation_id uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare v_user uuid := auth.uid();
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    update private.push_devices
       set active = false, updated_at = now()
     where user_id = v_user and installation_id = target_installation_id;
    return found;
end;
$$;

create or replace function public.claim_notification_deliveries(batch_size integer default 50)
returns table (
    delivery_id bigint,
    lease_token uuid,
    firebase_installation_id text,
    payload jsonb
)
language plpgsql
security definer
set search_path = ''
as $$
begin
    if auth.role() <> 'service_role' then
        raise exception using errcode = '42501', message = 'FORBIDDEN';
    end if;
    if batch_size not between 1 and 100 then
        raise exception using errcode = 'P0001', message = 'INVALID_BATCH_SIZE';
    end if;

    update private.notification_deliveries delivery
       set state = 'failed', lease_token = null, leased_until = null,
           next_attempt_at = now(), updated_at = now(), last_error_code = 'FCM_TRANSIENT'
     where delivery.state = 'sending' and delivery.leased_until < now();

    return query
    with candidates as (
        select delivery.id
          from private.notification_deliveries delivery
          join private.push_devices device on device.id = delivery.device_id
          join private.notification_outbox outbox on outbox.id = delivery.outbox_id
         where delivery.state in ('pending', 'failed')
           and delivery.attempts < 10
           and delivery.next_attempt_at <= now()
           and device.active
           and private.account_is_active(outbox.recipient_id)
         order by delivery.next_attempt_at, delivery.id
         for update of delivery skip locked
         limit batch_size
    ), leased as (
        update private.notification_deliveries delivery
           set state = 'sending', attempts = delivery.attempts + 1,
               lease_token = gen_random_uuid(), leased_until = now() + interval '2 minutes',
               updated_at = now()
          from candidates
         where delivery.id = candidates.id
        returning delivery.id, delivery.device_id, delivery.outbox_id, delivery.lease_token
    )
    select leased.id, leased.lease_token, device.firebase_installation_id, outbox.payload
      from leased
      join private.push_devices device on device.id = leased.device_id
      join private.notification_outbox outbox on outbox.id = leased.outbox_id;
end;
$$;

create or replace function public.complete_notification_delivery(
    target_delivery_id bigint,
    target_lease_token uuid,
    outcome text,
    error_code text default null
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_delivery private.notification_deliveries%rowtype;
begin
    if auth.role() <> 'service_role' then
        raise exception using errcode = '42501', message = 'FORBIDDEN';
    end if;
    if outcome not in ('sent', 'retry', 'invalid') then
        raise exception using errcode = 'P0001', message = 'INVALID_DELIVERY_OUTCOME';
    end if;

    select * into v_delivery
      from private.notification_deliveries delivery
     where delivery.id = target_delivery_id
     for update;
    if v_delivery.id is null or v_delivery.state <> 'sending'
       or v_delivery.lease_token <> target_lease_token
       or v_delivery.leased_until < now() then
        return false;
    end if;

    if outcome = 'sent' then
        update private.notification_deliveries
           set state = 'sent', sent_at = now(), last_error_code = null,
               lease_token = null, leased_until = null, updated_at = now()
         where id = target_delivery_id;
    elsif outcome = 'invalid' then
        update private.push_devices set active = false, updated_at = now()
         where id = v_delivery.device_id;
        update private.notification_deliveries
           set state = 'discarded', last_error_code = 'FCM_INVALID_INSTALLATION',
               lease_token = null, leased_until = null, updated_at = now()
         where id = target_delivery_id;
    else
        update private.notification_deliveries
           set state = case when attempts >= 10 then 'discarded' else 'failed' end,
               last_error_code = case
                   when error_code in ('FCM_TRANSIENT', 'FCM_AUTH') then error_code
                   else 'FCM_TRANSIENT'
               end,
               next_attempt_at = now() + least(
                   interval '6 hours',
                   interval '1 minute' * power(2::double precision, least(attempts, 8))
               ),
               lease_token = null, leased_until = null, updated_at = now()
         where id = target_delivery_id;
    end if;

    update private.notification_outbox outbox
       set state = case
               when exists (
                   select 1 from private.notification_deliveries delivery
                    where delivery.outbox_id = v_delivery.outbox_id
                      and delivery.state in ('pending', 'sending', 'failed')
                      and delivery.attempts < 10
               ) then 'pending'
               when exists (
                   select 1 from private.notification_deliveries delivery
                    where delivery.outbox_id = v_delivery.outbox_id and delivery.state = 'sent'
               ) then 'sent'
               else 'failed'
           end,
           attempts = (
               select coalesce(max(delivery.attempts), 0)
                 from private.notification_deliveries delivery
                where delivery.outbox_id = v_delivery.outbox_id
           ),
           updated_at = now()
     where outbox.id = v_delivery.outbox_id;
    return true;
end;
$$;

alter table private.chat_media
    add column automation_state text not null default 'queued'
        check (automation_state in ('queued', 'processing', 'review', 'completed')),
    add column automation_attempts integer not null default 0
        check (automation_attempts between 0 and 10),
    add column automation_next_attempt_at timestamptz not null default now(),
    add column automation_lease_token uuid,
    add column automation_leased_until timestamptz,
    add column automation_error_code text check (
        automation_error_code is null or automation_error_code in (
            'VISION_UNAVAILABLE', 'VISION_INVALID_RESPONSE', 'MEDIA_NOT_FOUND'
        )
    ),
    add constraint chat_media_automation_lease_pair check (
        (automation_lease_token is null and automation_leased_until is null)
        or (automation_lease_token is not null and automation_leased_until is not null)
    );

create index chat_media_automation_worker_idx
    on private.chat_media (automation_state, automation_next_attempt_at, created_at)
    where status = 'pending';

create or replace function public.claim_chat_media_moderation(batch_size integer default 10)
returns table (
    message_id uuid,
    lease_token uuid,
    object_path text,
    mime_type text
)
language plpgsql
security definer
set search_path = ''
as $$
begin
    if auth.role() <> 'service_role' then
        raise exception using errcode = '42501', message = 'FORBIDDEN';
    end if;
    if batch_size not between 1 and 25 then
        raise exception using errcode = 'P0001', message = 'INVALID_BATCH_SIZE';
    end if;

    update private.chat_media media
       set automation_state = 'queued', automation_lease_token = null,
           automation_leased_until = null, automation_next_attempt_at = now(),
           automation_error_code = 'VISION_UNAVAILABLE'
     where media.status = 'pending' and media.automation_state = 'processing'
       and media.automation_leased_until < now();

    return query
    with candidates as (
        select media.message_id
          from private.chat_media media
         where media.status = 'pending'
           and media.automation_state = 'queued'
           and media.automation_attempts < 10
           and media.automation_next_attempt_at <= now()
         order by media.automation_next_attempt_at, media.created_at
         for update skip locked
         limit batch_size
    ), leased as (
        update private.chat_media media
           set automation_state = 'processing',
               automation_attempts = media.automation_attempts + 1,
               automation_lease_token = gen_random_uuid(),
               automation_leased_until = now() + interval '3 minutes'
          from candidates
         where media.message_id = candidates.message_id
        returning media.message_id, media.automation_lease_token,
                  media.object_path, media.mime_type
    )
    select leased.message_id, leased.automation_lease_token,
           leased.object_path, leased.mime_type
      from leased;
end;
$$;

create or replace function public.complete_chat_media_moderation(
    target_message_id uuid,
    target_lease_token uuid,
    outcome text,
    error_code text default null
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare v_media private.chat_media%rowtype;
begin
    if auth.role() <> 'service_role' then
        raise exception using errcode = '42501', message = 'FORBIDDEN';
    end if;
    if outcome not in ('approved', 'adult', 'abusive', 'review', 'retry') then
        raise exception using errcode = 'P0001', message = 'INVALID_MODERATION_OUTCOME';
    end if;
    select * into v_media from private.chat_media media
     where media.message_id = target_message_id for update;
    if v_media.message_id is null or v_media.status <> 'pending'
       or v_media.automation_state <> 'processing'
       or v_media.automation_lease_token <> target_lease_token
       or v_media.automation_leased_until < now() then
        return false;
    end if;

    if outcome in ('approved', 'adult', 'abusive') then
        update private.chat_media
           set status = outcome::public.chat_media_status, moderated_at = now(),
               automation_state = 'completed', automation_error_code = null,
               automation_lease_token = null, automation_leased_until = null
         where message_id = target_message_id;
    elsif outcome = 'review' then
        update private.chat_media
           set automation_state = 'review', automation_error_code = null,
               automation_lease_token = null, automation_leased_until = null
         where message_id = target_message_id;
    else
        update private.chat_media
           set automation_state = case when automation_attempts >= 10 then 'review' else 'queued' end,
               automation_error_code = case
                   when error_code in ('VISION_UNAVAILABLE', 'VISION_INVALID_RESPONSE', 'MEDIA_NOT_FOUND')
                       then error_code
                   else 'VISION_UNAVAILABLE'
               end,
               automation_next_attempt_at = now() + least(
                   interval '6 hours',
                   interval '1 minute' * power(2::double precision, least(automation_attempts, 8))
               ),
               automation_lease_token = null, automation_leased_until = null
         where message_id = target_message_id;
    end if;
    return true;
end;
$$;

create or replace function public.moderate_chat_media(
    target_message_id uuid,
    decision public.chat_media_status
)
returns public.chat_media_status
language plpgsql
security definer
set search_path = ''
as $$
begin
    if decision = 'pending' then
        raise exception using errcode = 'P0001', message = 'INVALID_MODERATION_DECISION';
    end if;
    update private.chat_media
       set status = decision, moderated_at = now(), automation_state = 'completed',
           automation_error_code = null, automation_lease_token = null,
           automation_leased_until = null
     where message_id = target_message_id;
    if not found then
        raise exception using errcode = 'P0001', message = 'CHAT_PHOTO_NOT_FOUND';
    end if;
    return decision;
end;
$$;

revoke all on function public.register_push_device(uuid, text) from public;
revoke all on function public.unregister_push_device(uuid) from public;
revoke all on function public.claim_notification_deliveries(integer) from public;
revoke all on function public.complete_notification_delivery(bigint, uuid, text, text) from public;
revoke all on function public.claim_chat_media_moderation(integer) from public;
revoke all on function public.complete_chat_media_moderation(uuid, uuid, text, text) from public;

grant execute on function public.register_push_device(uuid, text) to authenticated;
grant execute on function public.unregister_push_device(uuid) to authenticated;
grant execute on function public.claim_notification_deliveries(integer) to service_role;
grant execute on function public.complete_notification_delivery(bigint, uuid, text, text) to service_role;
grant execute on function public.claim_chat_media_moderation(integer) to service_role;
grant execute on function public.complete_chat_media_moderation(uuid, uuid, text, text) to service_role;
