-- Direct-chat media, per-participant read/mute state and privacy-safe push outbox.
-- Chat media is distinct from the private album: selecting one photo never grants
-- access to the owner's album.

create type public.chat_message_kind as enum ('text', 'photo');
create type public.chat_media_status as enum ('pending', 'approved', 'adult', 'abusive', 'removed');

alter table public.messages
    drop constraint if exists messages_body_check;
alter table public.messages
    alter column body drop not null,
    add column kind public.chat_message_kind not null default 'text',
    add column client_message_id uuid,
    add column delivered_at timestamptz,
    add column read_at timestamptz,
    add constraint messages_content_matches_kind check (
        (kind = 'text' and body is not null and char_length(btrim(body)) between 1 and 2000)
        or (kind = 'photo' and body is null)
    ),
    add constraint messages_delivery_order check (
        read_at is null or (delivered_at is not null and read_at >= delivered_at)
    );

create unique index messages_sender_client_key_unique
    on public.messages (sender_id, client_message_id)
    where client_message_id is not null;

create table public.conversation_user_states (
    conversation_id uuid not null references public.conversations (id) on delete cascade,
    user_id uuid not null references public.accounts (id) on delete cascade,
    last_read_at timestamptz,
    muted boolean not null default false,
    updated_at timestamptz not null default now(),
    primary key (conversation_id, user_id)
);

create table private.chat_media (
    message_id uuid primary key references public.messages (id) on delete cascade,
    sender_id uuid not null references public.accounts (id) on delete cascade,
    conversation_id uuid not null references public.conversations (id) on delete cascade,
    object_path text not null unique,
    mime_type text not null check (mime_type in ('image/jpeg', 'image/png', 'image/webp')),
    status public.chat_media_status not null default 'pending',
    moderated_at timestamptz,
    created_at timestamptz not null default now()
);

create table private.notification_outbox (
    id bigint generated always as identity primary key,
    recipient_id uuid not null references public.accounts (id) on delete cascade,
    message_id uuid not null unique references public.messages (id) on delete cascade,
    payload jsonb not null,
    state text not null default 'pending' check (state in ('pending', 'sending', 'sent', 'failed')),
    attempts integer not null default 0 check (attempts between 0 and 20),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint notification_payload_is_private check (
        payload = jsonb_build_object(
            'title', 'Matcher',
            'body', 'Nova mensagem',
            'conversation_id', payload ->> 'conversation_id'
        )
    )
);

revoke all on table private.chat_media from anon, authenticated;
revoke all on table private.notification_outbox from anon, authenticated;

create or replace function private.ensure_chat_user_states()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.conversation_user_states (conversation_id, user_id)
    values (new.id, new.participant_a), (new.id, new.participant_b)
    on conflict do nothing;
    return new;
end;
$$;

create trigger conversations_create_user_states
after insert on public.conversations
for each row execute function private.ensure_chat_user_states();

insert into public.conversation_user_states (conversation_id, user_id)
select conversation.id, participant.user_id
from public.conversations conversation
cross join lateral (
    values (conversation.participant_a), (conversation.participant_b)
) participant(user_id)
on conflict do nothing;

create or replace function private.enqueue_private_message_notification()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_recipient uuid;
    v_muted boolean;
begin
    select case
               when conversation.participant_a = new.sender_id then conversation.participant_b
               else conversation.participant_a
           end
      into v_recipient
      from public.conversations conversation
     where conversation.id = new.conversation_id
       and new.sender_id in (conversation.participant_a, conversation.participant_b)
       and conversation.status = 'active';

    if v_recipient is null then
        return new;
    end if;

    select state.muted
      into v_muted
      from public.conversation_user_states state
     where state.conversation_id = new.conversation_id
       and state.user_id = v_recipient;

    if not coalesce(v_muted, false) then
        insert into private.notification_outbox (recipient_id, message_id, payload)
        values (
            v_recipient,
            new.id,
            jsonb_build_object(
                'title', 'Matcher',
                'body', 'Nova mensagem',
                'conversation_id', new.conversation_id::text
            )
        )
        on conflict (message_id) do nothing;
    end if;
    return new;
end;
$$;

create trigger messages_enqueue_private_notification
after insert on public.messages
for each row execute function private.enqueue_private_message_notification();

create or replace function private.chat_media_metadata_is_safe(metadata jsonb)
returns boolean
language sql
immutable
set search_path = ''
as $$
    select metadata is not null
       and lower(coalesce(metadata ->> 'mimetype', '')) in ('image/jpeg', 'image/png', 'image/webp')
       and case
           when coalesce(metadata ->> 'size', metadata ->> 'contentLength', '') ~ '^[0-9]{1,10}$'
               then coalesce(metadata ->> 'size', metadata ->> 'contentLength')::bigint between 1 and 5242880
           else false
       end;
$$;

create or replace function private.chat_media_path_is_valid(
    user_id uuid,
    conversation_id uuid,
    client_key uuid,
    object_path text
)
returns boolean
language sql
immutable
set search_path = ''
as $$
    select object_path ~ (
        '^' || user_id::text || '/' || conversation_id::text || '/' || client_key::text || '\.(jpg|jpeg|png|webp)$'
    );
$$;

create or replace function private.can_read_chat_media(object_path text, viewer_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select viewer_id is not null and exists (
        select 1
          from private.chat_media media
          join public.conversations conversation on conversation.id = media.conversation_id
         where media.object_path = can_read_chat_media.object_path
           and can_read_chat_media.viewer_id in (conversation.participant_a, conversation.participant_b)
           and conversation.status = 'active'
           and private.account_is_active(conversation.participant_a)
           and private.account_is_active(conversation.participant_b)
           and not private.is_blocked_pair(conversation.participant_a, conversation.participant_b)
           and (media.sender_id = can_read_chat_media.viewer_id or media.status = 'approved')
    );
$$;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'chat-media',
    'chat-media',
    false,
    5242880,
    array['image/jpeg', 'image/png', 'image/webp']::text[]
)
on conflict (id) do update
   set public = excluded.public,
       file_size_limit = excluded.file_size_limit,
       allowed_mime_types = excluded.allowed_mime_types;

create policy matcher_chat_media_insert
on storage.objects for insert
to authenticated
with check (
    bucket_id = 'chat-media'
    and coalesce(owner_id, owner::text) = (select auth.uid())::text
    and private.chat_media_metadata_is_safe(metadata)
    and exists (
        select 1
          from public.conversations conversation
         where conversation.id::text = (storage.foldername(name))[2]
           and (select auth.uid()) in (conversation.participant_a, conversation.participant_b)
           and conversation.status = 'active'
           and (storage.foldername(name))[1] = (select auth.uid())::text
           and not private.is_blocked_pair(conversation.participant_a, conversation.participant_b)
    )
);

create policy matcher_chat_media_select
on storage.objects for select
to authenticated
using (
    bucket_id = 'chat-media'
    and private.can_read_chat_media(name, (select auth.uid()))
);

create policy matcher_chat_media_delete_own
on storage.objects for delete
to authenticated
using (
    bucket_id = 'chat-media'
    and coalesce(owner_id, owner::text) = (select auth.uid())::text
    and (storage.foldername(name))[1] = (select auth.uid())::text
);

create or replace function public.send_message(
    conversation_id uuid,
    message_body text,
    client_message_id uuid
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_sender uuid := auth.uid();
    v_message_id uuid;
    v_now timestamptz := clock_timestamp();
begin
    if v_sender is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if not private.account_is_active(v_sender) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE';
    end if;
    if client_message_id is null then
        raise exception using errcode = 'P0001', message = 'INVALID_CLIENT_MESSAGE_ID';
    end if;
    if message_body is null or char_length(btrim(message_body)) = 0 or char_length(btrim(message_body)) > 2000 then
        raise exception using errcode = 'P0001', message = 'INVALID_MESSAGE';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(v_sender::text || ':' || client_message_id::text, 0));

    select message.id into v_message_id
      from public.messages message
     where message.sender_id = v_sender and message.client_message_id = send_message.client_message_id;
    if v_message_id is not null then
        if not exists (
            select 1 from public.messages message
             where message.id = v_message_id
               and message.conversation_id = send_message.conversation_id
               and message.kind = 'text'
               and message.body = btrim(send_message.message_body)
        ) then
            raise exception using errcode = 'P0001', message = 'CLIENT_MESSAGE_CONFLICT';
        end if;
        return v_message_id;
    end if;

    perform 1 from public.conversations conversation
     where conversation.id = send_message.conversation_id
       and private.can_access_conversation(conversation.id, v_sender)
     for update;
    if not found then
        raise exception using errcode = 'P0001', message = 'CHAT_NOT_AVAILABLE';
    end if;

    insert into public.messages (conversation_id, sender_id, body, kind, client_message_id, created_at)
    values (send_message.conversation_id, v_sender, btrim(message_body), 'text', client_message_id, v_now)
    returning id into v_message_id;
    update public.conversations set last_message_at = v_now where id = send_message.conversation_id;
    return v_message_id;
end;
$$;

create or replace function public.send_message(conversation_id uuid, message_body text)
returns uuid
language sql
security definer
set search_path = ''
as $$
    select public.send_message(conversation_id, message_body, gen_random_uuid());
$$;

create or replace function public.send_photo_message(
    conversation_id uuid,
    client_message_id uuid,
    object_path text,
    media_type text
)
returns table (message_id uuid, moderation_status public.chat_media_status)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_sender uuid := auth.uid();
    v_message_id uuid;
    v_existing private.chat_media%rowtype;
    v_object storage.objects%rowtype;
    v_now timestamptz := clock_timestamp();
begin
    if v_sender is null then raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED'; end if;
    if not private.account_is_active(v_sender) then raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE'; end if;
    if client_message_id is null or media_type not in ('image/jpeg', 'image/png', 'image/webp')
       or not private.chat_media_path_is_valid(v_sender, conversation_id, client_message_id, object_path) then
        raise exception using errcode = 'P0001', message = 'INVALID_CHAT_PHOTO';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(v_sender::text || ':' || client_message_id::text, 0));
    select message.id into v_message_id from public.messages message
     where message.sender_id = v_sender and message.client_message_id = send_photo_message.client_message_id;
    if v_message_id is not null then
        select * into v_existing from private.chat_media media where media.message_id = v_message_id;
        if v_existing.conversation_id <> send_photo_message.conversation_id
           or v_existing.object_path <> send_photo_message.object_path
           or v_existing.mime_type <> send_photo_message.media_type then
            raise exception using errcode = 'P0001', message = 'CLIENT_MESSAGE_CONFLICT';
        end if;
        return query select v_message_id, v_existing.status;
        return;
    end if;

    perform 1 from public.conversations conversation
     where conversation.id = send_photo_message.conversation_id
       and private.can_access_conversation(conversation.id, v_sender)
     for update;
    if not found then raise exception using errcode = 'P0001', message = 'CHAT_NOT_AVAILABLE'; end if;

    select object.* into v_object from storage.objects object
     where object.bucket_id = 'chat-media' and object.name = send_photo_message.object_path;
    if v_object.id is null or coalesce(v_object.owner_id, v_object.owner::text) <> v_sender::text
       or not private.chat_media_metadata_is_safe(v_object.metadata)
       or coalesce(v_object.metadata ->> 'size', '') !~ '^[0-9]{1,10}$'
       or lower(v_object.metadata ->> 'mimetype') <> media_type then
        raise exception using errcode = 'P0001', message = 'CHAT_PHOTO_NOT_FOUND';
    end if;

    insert into public.messages (conversation_id, sender_id, body, kind, client_message_id, created_at)
    values (conversation_id, v_sender, null, 'photo', client_message_id, v_now)
    returning id into v_message_id;
    insert into private.chat_media (message_id, sender_id, conversation_id, object_path, mime_type)
    values (v_message_id, v_sender, conversation_id, object_path, media_type);
    update public.conversations set last_message_at = v_now where id = conversation_id;
    return query select v_message_id, 'pending'::public.chat_media_status;
end;
$$;

create or replace function public.list_chat_messages(target_conversation_id uuid)
returns table (
    id uuid,
    conversation_id uuid,
    sender_id uuid,
    body text,
    kind public.chat_message_kind,
    client_message_id uuid,
    created_at timestamptz,
    delivered_at timestamptz,
    read_at timestamptz,
    media_status public.chat_media_status
)
language sql
stable
security definer
set search_path = ''
as $$
    select message.id, message.conversation_id, message.sender_id, message.body, message.kind,
           message.client_message_id, message.created_at, message.delivered_at, message.read_at, media.status
      from public.messages message
      left join private.chat_media media on media.message_id = message.id
     where message.conversation_id = target_conversation_id
       and private.can_access_conversation(target_conversation_id, auth.uid())
       and message.removed_at is null
     order by message.created_at, message.id
     limit 200;
$$;

create or replace function public.authorize_chat_media(target_message_id uuid)
returns table (object_path text, mime_type text)
language sql
stable
security definer
set search_path = ''
as $$
    select media.object_path, media.mime_type
      from private.chat_media media
     where media.message_id = target_message_id
       and private.can_read_chat_media(media.object_path, auth.uid());
$$;

create or replace function public.mark_chat_delivered()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare v_user uuid := auth.uid(); v_count integer;
begin
    if v_user is null then raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED'; end if;
    update public.messages message set delivered_at = coalesce(message.delivered_at, now())
     from public.conversations conversation
     where conversation.id = message.conversation_id
       and v_user in (conversation.participant_a, conversation.participant_b)
       and message.sender_id <> v_user and message.delivered_at is null
       and private.can_access_conversation(conversation.id, v_user);
    get diagnostics v_count = row_count;
    return v_count;
end;
$$;

create or replace function public.get_chat_user_states()
returns table (conversation_id uuid, muted boolean, unread_count integer)
language sql
stable
security definer
set search_path = ''
as $$
    select state.conversation_id,
           state.muted,
           count(message.id) filter (
               where message.sender_id <> auth.uid() and message.read_at is null
           )::integer as unread_count
      from public.conversation_user_states state
      join public.conversations conversation on conversation.id = state.conversation_id
      left join public.messages message
        on message.conversation_id = state.conversation_id and message.removed_at is null
     where state.user_id = auth.uid()
       and private.can_access_conversation(state.conversation_id, auth.uid())
     group by state.conversation_id, state.muted;
$$;

create or replace function public.mark_conversation_read(target_conversation_id uuid)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare v_user uuid := auth.uid(); v_now timestamptz := clock_timestamp(); v_count integer;
begin
    if v_user is null or not private.can_access_conversation(target_conversation_id, v_user) then
        raise exception using errcode = 'P0001', message = 'CHAT_NOT_AVAILABLE';
    end if;
    update public.conversation_user_states set last_read_at = v_now, updated_at = v_now
     where conversation_id = target_conversation_id and user_id = v_user;
    update public.messages set delivered_at = coalesce(delivered_at, v_now), read_at = coalesce(read_at, v_now)
     where conversation_id = target_conversation_id and sender_id <> v_user and read_at is null;
    get diagnostics v_count = row_count;
    return v_count;
end;
$$;

create or replace function public.set_conversation_muted(target_conversation_id uuid, should_mute boolean)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare v_user uuid := auth.uid();
begin
    if v_user is null or not private.can_access_conversation(target_conversation_id, v_user) then
        raise exception using errcode = 'P0001', message = 'CHAT_NOT_AVAILABLE';
    end if;
    update public.conversation_user_states set muted = should_mute, updated_at = now()
     where conversation_id = target_conversation_id and user_id = v_user;
    return should_mute;
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
    if decision = 'pending' then raise exception using errcode = 'P0001', message = 'INVALID_MODERATION_DECISION'; end if;
    update private.chat_media set status = decision, moderated_at = now()
     where message_id = target_message_id;
    if not found then raise exception using errcode = 'P0001', message = 'CHAT_PHOTO_NOT_FOUND'; end if;
    return decision;
end;
$$;

alter table public.conversation_user_states enable row level security;
create policy conversation_user_states_select_self
on public.conversation_user_states for select to authenticated
using (user_id = (select auth.uid()) and private.can_access_conversation(conversation_id, (select auth.uid())));
revoke all on table public.conversation_user_states from anon, authenticated;
grant select on table public.conversation_user_states to authenticated;

revoke all on function public.send_message(uuid, text, uuid) from public;
revoke all on function public.send_photo_message(uuid, uuid, text, text) from public;
revoke all on function public.list_chat_messages(uuid) from public;
revoke all on function public.authorize_chat_media(uuid) from public;
revoke all on function public.mark_chat_delivered() from public;
revoke all on function public.get_chat_user_states() from public;
revoke all on function public.mark_conversation_read(uuid) from public;
revoke all on function public.set_conversation_muted(uuid, boolean) from public;
revoke all on function public.moderate_chat_media(uuid, public.chat_media_status) from public;

grant execute on function public.send_message(uuid, text, uuid) to authenticated;
grant execute on function public.send_photo_message(uuid, uuid, text, text) to authenticated;
grant execute on function public.list_chat_messages(uuid) to authenticated;
grant execute on function public.authorize_chat_media(uuid) to authenticated;
grant execute on function public.mark_chat_delivered() to authenticated;
grant execute on function public.get_chat_user_states() to authenticated;
grant execute on function public.mark_conversation_read(uuid) to authenticated;
grant execute on function public.set_conversation_muted(uuid, boolean) to authenticated;
grant execute on function public.moderate_chat_media(uuid, public.chat_media_status) to service_role;

revoke all on function private.chat_media_metadata_is_safe(jsonb) from public;
revoke all on function private.chat_media_path_is_valid(uuid, uuid, uuid, text) from public;
revoke all on function private.can_read_chat_media(text, uuid) from public;
grant execute on function private.chat_media_metadata_is_safe(jsonb) to authenticated;
grant execute on function private.can_read_chat_media(text, uuid) to authenticated;

do $$
begin
    if exists (select 1 from pg_publication where pubname = 'supabase_realtime')
       and not exists (
           select 1 from pg_publication_tables
            where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'conversation_user_states'
       ) then
        alter publication supabase_realtime add table public.conversation_user_states;
    end if;
end;
$$;
