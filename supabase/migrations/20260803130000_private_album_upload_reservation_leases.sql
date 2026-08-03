-- Bound private-album upload reservations to a server-owned lease. A client
-- request ID makes retries idempotent, while expired reservations become
-- ordinary cleanup tombstones processed by the existing leased worker.

alter table private.private_album_items
    add column reservation_key uuid,
    add column upload_expires_at timestamptz;

-- Existing rows receive opaque keys only to make the uniqueness invariant
-- total. Existing in-flight uploads age from their original creation time, so
-- an already-abandoned reservation is not granted a fresh lease on rollout.
update private.private_album_items item
   set reservation_key = gen_random_uuid(),
       upload_expires_at = case
           when item.status = 'uploading'
               then item.created_at + interval '30 minutes'
           else null
       end;

alter table private.private_album_items
    alter column reservation_key set not null,
    add constraint private_album_uploading_has_expiry check (
        status <> 'uploading' or upload_expires_at is not null
    );

create unique index private_album_items_reservation_key_idx
    on private.private_album_items (album_id, reservation_key);

create index private_album_items_expired_upload_idx
    on private.private_album_items (upload_expires_at, id)
    where status = 'uploading';

-- This helper never returns paths. It acquires the same per-object advisory
-- lock as Storage before changing state, then leaves physical removal to the
-- existing cleanup queue and its service-role-only leases.
create function private.reap_expired_private_album_uploads(
    batch_size integer,
    target_album_id uuid
)
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    v_candidate record;
    v_path text;
    v_reaped integer := 0;
begin
    if batch_size not between 1 and 500 then
        raise exception using errcode = 'P0001', message = 'INVALID_BATCH_SIZE';
    end if;

    for v_candidate in
        select item.id, item.object_path
        from private.private_album_items item
        where item.status = 'uploading'
          and item.upload_expires_at <= clock_timestamp()
          and (
              reap_expired_private_album_uploads.target_album_id is null
              or item.album_id = reap_expired_private_album_uploads.target_album_id
          )
        order by item.upload_expires_at, item.id
        limit batch_size
    loop
        perform private.lock_private_album_object_path(v_candidate.object_path);
        v_path := null;

        update private.private_album_items item
           set status = 'deleting',
               position = null
         where item.id = v_candidate.id
           and item.status = 'uploading'
           and item.upload_expires_at <= clock_timestamp()
        returning item.object_path into v_path;

        if v_path is not null then
            perform private.enqueue_private_album_object(v_path);
            v_reaped := v_reaped + 1;
        end if;
    end loop;

    return v_reaped;
end;
$$;

-- The three-argument overload is the idempotent contract. The key is only a
-- correlation token; ownership, album generation, MIME, expiry and capacity
-- remain authoritative server decisions. Paths are returned only for a live
-- uploading reservation, never for completed or expired retries.
create function public.reserve_private_album_item(
    target_album_id uuid,
    mime_type text,
    idempotency_key uuid
)
returns table (
    item_id uuid,
    object_path text,
    "position" smallint,
    reservation_status text,
    upload_expires_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_album private.private_albums%rowtype;
    v_existing private.private_album_items%rowtype;
    v_item uuid := gen_random_uuid();
    v_position smallint;
    v_extension text;
    v_path text;
    v_expires_at timestamptz;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if not private.account_is_active(v_user) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE';
    end if;
    if target_album_id is null or idempotency_key is null then
        raise exception using errcode = 'P0001', message = 'INVALID_PRIVATE_ALBUM_RESERVATION';
    end if;

    v_extension := private.private_album_extension(lower(mime_type));
    if v_extension is null then
        raise exception using errcode = 'P0001', message = 'INVALID_PRIVATE_ALBUM_MEDIA_TYPE';
    end if;

    perform private.lock_private_album_owner(v_user);

    select album.*
      into v_album
      from private.private_albums album
     where album.id = target_album_id
       and album.owner_id = v_user
       and album.status = 'active'
     for update;

    if not found then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_NOT_FOUND';
    end if;

    select item.*
      into v_existing
      from private.private_album_items item
     where item.album_id = v_album.id
       and item.reservation_key = idempotency_key
     for update;

    if found then
        if v_existing.mime_type <> lower(mime_type) then
            raise exception using
                errcode = 'P0001',
                message = 'PRIVATE_ALBUM_IDEMPOTENCY_CONFLICT';
        end if;

        if v_existing.status = 'uploading'
           and v_existing.upload_expires_at > clock_timestamp() then
            return query select
                v_existing.id,
                v_existing.object_path,
                v_existing.position,
                'uploading'::text,
                v_existing.upload_expires_at;
            return;
        end if;

        if v_existing.status = 'available' then
            return query select
                v_existing.id,
                null::text,
                v_existing.position,
                'available'::text,
                v_existing.upload_expires_at;
            return;
        end if;

        -- Reap a just-expired reservation before returning a terminal retry
        -- state. The caller must use a new key for a new intended upload.
        perform private.reap_expired_private_album_uploads(10, v_album.id);
        return query select
            v_existing.id,
            null::text,
            null::smallint,
            'expired'::text,
            v_existing.upload_expires_at;
        return;
    end if;

    -- Free only server-expired slots in this owned album before enforcing the
    -- ten-item limit. The immutable old path remains tombstoned in cleanup.
    perform private.reap_expired_private_album_uploads(10, v_album.id);

    select slot::smallint
      into v_position
      from generate_series(0, 9) slot
     where not exists (
         select 1
         from private.private_album_items item
         where item.album_id = v_album.id
           and item.position = slot
     )
     order by slot
     limit 1;

    if v_position is null then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_LIMIT_REACHED';
    end if;

    v_path := v_user::text || '/' || v_album.id::text || '/' ||
        v_item::text || '.' || v_extension;
    v_expires_at := clock_timestamp() + interval '30 minutes';

    insert into private.private_album_items (
        id,
        album_id,
        object_path,
        mime_type,
        position,
        status,
        reservation_key,
        upload_expires_at
    )
    values (
        v_item,
        v_album.id,
        v_path,
        lower(mime_type),
        v_position,
        'uploading',
        idempotency_key,
        v_expires_at
    );

    return query select
        v_item,
        v_path,
        v_position,
        'uploading'::text,
        v_expires_at;
end;
$$;

-- Temporary compatibility for already-installed clients. It remains safe and
-- TTL-bounded, but only the explicit client-generated key overload can dedupe
-- a retry whose first RPC response was lost.
create or replace function public.reserve_private_album_item(
    target_album_id uuid,
    mime_type text
)
returns table (
    item_id uuid,
    object_path text,
    "position" smallint
)
language plpgsql
security definer
set search_path = ''
as $$
begin
    return query
    select reserved.item_id, reserved.object_path, reserved.position
    from public.reserve_private_album_item(
        reserve_private_album_item.target_album_id,
        reserve_private_album_item.mime_type,
        gen_random_uuid()
    ) reserved;
end;
$$;

-- Finalization uses the same path lock as Storage and the reaper. A completed
-- Storage INSERT does not extend the lease: once the server deadline passes,
-- finalization atomically tombstones the item and queues its bytes for cleanup.
create or replace function public.finalize_private_album_item(album_item_id uuid)
returns table (
    item_id uuid,
    "position" smallint,
    item_status text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_path text;
    v_item private.private_album_items%rowtype;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if not private.account_is_active(v_user) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE';
    end if;

    select item.object_path
      into v_path
      from private.private_album_items item
      join private.private_albums album on album.id = item.album_id
     where item.id = album_item_id
       and album.owner_id = v_user
       and album.status = 'active';

    if v_path is null then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_ITEM_NOT_FOUND';
    end if;

    perform private.lock_private_album_object_path(v_path);

    select item.*
      into v_item
      from private.private_album_items item
      join private.private_albums album on album.id = item.album_id
     where item.id = album_item_id
       and item.object_path = v_path
       and album.owner_id = v_user
       and album.status = 'active'
     for update of item;

    if not found then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_ITEM_NOT_FOUND';
    end if;

    if v_item.status = 'available' then
        return query select v_item.id, v_item.position, v_item.status;
        return;
    end if;
    if v_item.status <> 'uploading' then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_ITEM_NOT_FINALIZABLE';
    end if;

    if v_item.upload_expires_at <= clock_timestamp() then
        update private.private_album_items item
           set status = 'deleting',
               position = null
         where item.id = v_item.id
        returning item.id, item.position, item.status
             into v_item.id, v_item.position, v_item.status;

        perform private.enqueue_private_album_object(v_item.object_path);
        return query select v_item.id, v_item.position, v_item.status;
        return;
    end if;

    if not exists (
        select 1
        from storage.objects object
        where object.bucket_id = 'private-albums'
          and object.name = v_item.object_path
          and coalesce(object.owner_id, object.owner::text) = v_user::text
          and private.private_album_metadata_is_safe(
              v_item.object_path,
              v_item.mime_type,
              object.metadata
          )
    ) then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_OBJECT_NOT_FOUND';
    end if;

    update private.private_album_items item
       set status = 'available'
     where item.id = v_item.id
    returning item.id, item.position, item.status
         into v_item.id, v_item.position, v_item.status;

    return query select v_item.id, v_item.position, v_item.status;
end;
$$;

-- A late Storage request cannot revive an expired lease, even if the reaper
-- has not yet converted its row into a cleanup tombstone.
create or replace function private.can_insert_private_album_object(
    object_path text,
    viewer_id uuid,
    metadata jsonb
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    v_allowed boolean;
begin
    if object_path is null then
        return false;
    end if;

    perform private.lock_private_album_object_path(object_path);

    select exists (
        select 1
        from private.private_album_items item
        join private.private_albums album on album.id = item.album_id
        where item.object_path = can_insert_private_album_object.object_path
          and item.status = 'uploading'
          and item.upload_expires_at > clock_timestamp()
          and album.status = 'active'
          and album.owner_id = can_insert_private_album_object.viewer_id
          and private.account_is_active(can_insert_private_album_object.viewer_id)
          and private.private_album_path_is_valid(
              album.owner_id,
              album.id,
              item.id,
              item.mime_type,
              item.object_path
          )
          and private.private_album_upload_metadata_is_safe(
              item.object_path,
              item.mime_type,
              can_insert_private_album_object.metadata
          )
    ) into v_allowed;

    return coalesce(v_allowed, false);
end;
$$;

-- Operational entry point returns only a count and is not needed by the
-- normal worker: cleanup-batch acquisition below also performs a bounded reap.
create function public.reap_expired_private_album_uploads(
    batch_size integer default 100
)
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
    if auth.role() <> 'service_role' then
        raise exception using errcode = '42501', message = 'SERVICE_ROLE_REQUIRED';
    end if;

    return private.reap_expired_private_album_uploads(batch_size, null);
end;
$$;

-- Reaping is folded into the existing worker poll so cleanup does not depend
-- on pg_cron, a paid scheduler or a second Edge Function.
create or replace function public.get_private_album_cleanup_batch(
    batch_size integer default 100
)
returns table (
    object_path text,
    lease_token uuid,
    leased_until timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
begin
    if auth.role() <> 'service_role' then
        raise exception using errcode = '42501', message = 'SERVICE_ROLE_REQUIRED';
    end if;
    if batch_size not between 1 and 500 then
        raise exception using errcode = 'P0001', message = 'INVALID_BATCH_SIZE';
    end if;

    perform private.reap_expired_private_album_uploads(batch_size, null);

    return query
    with selected as (
        select queued.object_path
        from private.private_album_cleanup_queue queued
        where queued.next_attempt_at <= now()
          and (queued.leased_until is null or queued.leased_until <= now())
          and private.private_album_object_hold_until(queued.object_path) is null
        order by queued.next_attempt_at, queued.requested_at, queued.object_path
        limit batch_size
        for update skip locked
    )
    update private.private_album_cleanup_queue queued
       set attempts = queued.attempts + 1,
           last_attempt_at = now(),
           lease_token = gen_random_uuid(),
           leased_until = now() + interval '5 minutes',
           last_error_code = null
      from selected
     where queued.object_path = selected.object_path
    returning queued.object_path, queued.lease_token, queued.leased_until;
end;
$$;

revoke all on function private.reap_expired_private_album_uploads(integer, uuid)
from public, anon, authenticated, service_role;

revoke all on function public.reserve_private_album_item(uuid, text, uuid) from public;
grant execute on function public.reserve_private_album_item(uuid, text, uuid)
to authenticated;

revoke all on function public.reserve_private_album_item(uuid, text) from public;
grant execute on function public.reserve_private_album_item(uuid, text)
to authenticated;

revoke all on function public.finalize_private_album_item(uuid) from public;
grant execute on function public.finalize_private_album_item(uuid)
to authenticated;

revoke all on function public.reap_expired_private_album_uploads(integer) from public;
grant execute on function public.reap_expired_private_album_uploads(integer)
to service_role;

revoke all on function public.get_private_album_cleanup_batch(integer) from public;
grant execute on function public.get_private_album_cleanup_batch(integer)
to service_role;

revoke all on function private.can_insert_private_album_object(text, uuid, jsonb)
from public;
grant execute on function private.can_insert_private_album_object(text, uuid, jsonb)
to authenticated;
