-- Supabase Storage uses contentLength during upload preflight and persists size
-- after completion. Both representations must enforce the same 5 MB ceiling.

create or replace function private.profile_photo_metadata_is_safe(metadata jsonb)
returns boolean
language sql
immutable
set search_path = ''
as $$
    select metadata is not null
       and lower(coalesce(metadata ->> 'mimetype', '')) in (
           'image/jpeg', 'image/png', 'image/webp'
       )
       and case
           when coalesce(metadata ->> 'size', '') ~ '^[0-9]{1,10}$'
               then (metadata ->> 'size')::bigint between 1 and 5242880
           when coalesce(metadata ->> 'contentLength', '') ~ '^[0-9]{1,10}$'
               then (metadata ->> 'contentLength')::bigint between 1 and 5242880
           else false
       end;
$$;

revoke all on function private.profile_photo_metadata_is_safe(jsonb) from public;
grant execute on function private.profile_photo_metadata_is_safe(jsonb) to authenticated;
