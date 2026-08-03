-- Supabase Storage authorizes an upload twice with two different metadata
-- shapes. The client preflight INSERT carries mimetype/contentLength; only the
-- trusted Storage completion writes the final mimetype/size metadata.
create or replace function private.private_album_upload_metadata_is_safe(
    object_path text,
    expected_mime_type text,
    metadata jsonb
)
returns boolean
language sql
immutable
set search_path = ''
as $$
    select jsonb_typeof(metadata) = 'object'
       and jsonb_typeof(metadata -> 'mimetype') = 'string'
       and lower(metadata ->> 'mimetype') = expected_mime_type
       and jsonb_typeof(metadata -> 'contentLength') = 'number'
       and case
           when coalesce(metadata ->> 'contentLength', '') ~ '^[0-9]{1,10}$'
               then (metadata ->> 'contentLength')::bigint between 1 and 5242880
           else false
       end
       and not (metadata ? 'size')
       and object_path ~ case expected_mime_type
           when 'image/jpeg' then '\.jpg$'
           when 'image/png' then '\.png$'
           when 'image/webp' then '\.webp$'
           else 'a^'
       end;
$$;

-- Keep the hardening migration's path lock and authoritative reservation
-- checks, but validate the transport metadata used by Storage's preflight.
-- private_album_metadata_is_safe remains unchanged and continues to require
-- final size metadata when finalize_private_album_item runs.
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

    -- VOLATILE forces this recheck after the advisory lock rather than relying
    -- on a decision made before a concurrent tombstone committed.
    select exists (
        select 1
        from private.private_album_items item
        join private.private_albums album on album.id = item.album_id
        where item.object_path = can_insert_private_album_object.object_path
          and item.status = 'uploading'
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

revoke all on function private.private_album_upload_metadata_is_safe(
    text, text, jsonb
) from public;
revoke all on function private.can_insert_private_album_object(
    text, uuid, jsonb
) from public;
grant execute on function private.can_insert_private_album_object(
    text, uuid, jsonb
) to authenticated;

-- INSERT ... RETURNING needs a SELECT policy in some Storage releases. This
-- policy is usable only inside the exact upload operation and only while the
-- caller still owns an active, server-reserved uploading item. List, download
-- and signed-URL operations use different operation names and see no rows.
drop policy if exists matcher_private_albums_upload_returning on storage.objects;
create policy matcher_private_albums_upload_returning
on storage.objects for select
to authenticated
using (
    bucket_id = 'private-albums'
    and storage.allow_only_operation('storage.object.upload')
    and coalesce(owner_id, owner::text) = (select auth.uid())::text
    and private.can_insert_private_album_object(
        name,
        (select auth.uid()),
        metadata
    )
);
