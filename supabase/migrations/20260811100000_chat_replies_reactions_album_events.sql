-- Conversational parity: replies, lightweight reactions and auditable album-share events.

alter table public.messages
    add column if not exists reply_to_message_id uuid references public.messages(id) on delete set null;

create index if not exists messages_reply_to_idx
    on public.messages (reply_to_message_id)
    where reply_to_message_id is not null;

create table private.message_reactions (
    message_id uuid not null references public.messages(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    reaction text not null default 'heart' check (reaction = 'heart'),
    created_at timestamptz not null default now(),
    primary key (message_id, user_id, reaction)
);

create table private.chat_album_events (
    message_id uuid primary key references public.messages(id) on delete cascade,
    album_id uuid not null references private.private_albums(id) on delete cascade,
    owner_id uuid not null references auth.users(id) on delete cascade,
    recipient_id uuid not null references auth.users(id) on delete cascade,
    event_type text not null check (event_type in ('shared', 'revoked')),
    created_at timestamptz not null default now()
);

alter table private.message_reactions enable row level security;
alter table private.chat_album_events enable row level security;
revoke all on table private.message_reactions, private.chat_album_events from anon, authenticated;

create or replace function public.send_message(
    conversation_id uuid,
    message_body text,
    client_message_id uuid,
    reply_to_message_id uuid
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
    if v_sender is null then raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED'; end if;
    if not private.account_is_active(v_sender) then raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE'; end if;
    if client_message_id is null then raise exception using errcode = 'P0001', message = 'INVALID_CLIENT_MESSAGE_ID'; end if;
    if message_body is null or char_length(btrim(message_body)) = 0 or char_length(btrim(message_body)) > 2000 then
        raise exception using errcode = 'P0001', message = 'INVALID_MESSAGE';
    end if;
    if reply_to_message_id is not null and not exists (
        select 1 from public.messages replied
         where replied.id = send_message.reply_to_message_id
           and replied.conversation_id = send_message.conversation_id
           and replied.removed_at is null
    ) then
        raise exception using errcode = 'P0001', message = 'INVALID_REPLY';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(v_sender::text || ':' || client_message_id::text, 0));
    select message.id into v_message_id from public.messages message
     where message.sender_id = v_sender and message.client_message_id = send_message.client_message_id;
    if v_message_id is not null then
        if not exists (
            select 1 from public.messages message
             where message.id = v_message_id
               and message.conversation_id = send_message.conversation_id
               and message.kind = 'text'
               and message.body = btrim(send_message.message_body)
               and message.reply_to_message_id is not distinct from send_message.reply_to_message_id
        ) then
            raise exception using errcode = 'P0001', message = 'CLIENT_MESSAGE_CONFLICT';
        end if;
        return v_message_id;
    end if;

    perform 1 from public.conversations conversation
     where conversation.id = send_message.conversation_id
       and private.can_access_conversation(conversation.id, v_sender)
     for update;
    if not found then raise exception using errcode = 'P0001', message = 'CHAT_NOT_AVAILABLE'; end if;

    insert into public.messages (conversation_id, sender_id, body, kind, client_message_id, reply_to_message_id, created_at)
    values (conversation_id, v_sender, btrim(message_body), 'text', client_message_id, reply_to_message_id, v_now)
    returning id into v_message_id;
    update public.conversations set last_message_at = v_now where id = conversation_id;
    return v_message_id;
end;
$$;

create or replace function public.send_message(conversation_id uuid, message_body text, client_message_id uuid)
returns uuid language sql security definer set search_path = '' as $$
    select public.send_message(conversation_id, message_body, client_message_id, null);
$$;

create or replace function public.toggle_message_reaction(target_message_id uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare v_user uuid := auth.uid();
begin
    if v_user is null then raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED'; end if;
    if not exists (
        select 1 from public.messages message
         where message.id = target_message_id
           and message.removed_at is null
           and private.can_access_conversation(message.conversation_id, v_user)
    ) then raise exception using errcode = 'P0001', message = 'MESSAGE_NOT_AVAILABLE'; end if;

    delete from private.message_reactions reaction
     where reaction.message_id = target_message_id and reaction.user_id = v_user and reaction.reaction = 'heart';
    if found then return false; end if;
    insert into private.message_reactions(message_id, user_id) values (target_message_id, v_user);
    return true;
end;
$$;

drop function public.list_chat_messages(uuid);

create function public.list_chat_messages(target_conversation_id uuid)
returns table (
    id uuid, conversation_id uuid, sender_id uuid, body text, kind public.chat_message_kind,
    client_message_id uuid, created_at timestamptz, delivered_at timestamptz, read_at timestamptz,
    media_status public.chat_media_status, reply_to_message_id uuid, reply_preview text,
    reaction_count integer, reacted_by_me boolean, album_event text, album_id uuid
)
language sql stable security definer set search_path = '' as $$
    select message.id, message.conversation_id, message.sender_id, message.body, message.kind,
           message.client_message_id, message.created_at, message.delivered_at, message.read_at, media.status,
           message.reply_to_message_id,
           case when replied.kind = 'photo' then 'Foto' else left(coalesce(replied.body, ''), 120) end,
           count(reaction.user_id)::integer,
           coalesce(bool_or(reaction.user_id = auth.uid()), false), album_event.event_type, album_event.album_id
      from public.messages message
      left join public.messages replied on replied.id = message.reply_to_message_id
      left join private.chat_media media on media.message_id = message.id
      left join private.message_reactions reaction on reaction.message_id = message.id
      left join private.chat_album_events album_event on album_event.message_id = message.id
     where message.conversation_id = target_conversation_id
       and private.can_access_conversation(target_conversation_id, auth.uid())
       and message.removed_at is null
     group by message.id, replied.id, media.status, album_event.event_type, album_event.album_id
     order by message.created_at, message.id
     limit 200;
$$;

create or replace function private.emit_album_chat_event()
returns trigger language plpgsql security definer set search_path = '' as $$
declare v_conversation uuid; v_message uuid; v_type text; v_now timestamptz := clock_timestamp();
begin
    if tg_op = 'INSERT' or (old.revoked_at is not null and new.revoked_at is null) then v_type := 'shared';
    elsif old.revoked_at is null and new.revoked_at is not null then v_type := 'revoked';
    else return new;
    end if;
    select conversation.id into v_conversation from public.conversations conversation
     where new.recipient_id in (conversation.participant_a, conversation.participant_b)
       and (select album.owner_id from private.private_albums album where album.id = new.album_id)
           in (conversation.participant_a, conversation.participant_b)
       and conversation.status = 'active' order by conversation.created_at limit 1;
    if v_conversation is null then return new; end if;
    insert into public.messages(conversation_id, sender_id, body, kind, client_message_id, created_at)
    select v_conversation, album.owner_id,
           case when v_type = 'shared' then 'Liberou o álbum privado' else 'Revogou o álbum privado' end,
           'text', gen_random_uuid(), v_now from private.private_albums album where album.id = new.album_id
    returning id into v_message;
    insert into private.chat_album_events(message_id, album_id, owner_id, recipient_id, event_type)
    select v_message, new.album_id, album.owner_id, new.recipient_id, v_type
      from private.private_albums album where album.id = new.album_id;
    update public.conversations set last_message_at = v_now where id = v_conversation;
    return new;
end;
$$;

drop trigger if exists private_album_grants_emit_chat_event on private.private_album_grants;
create trigger private_album_grants_emit_chat_event
after insert or update of revoked_at on private.private_album_grants
for each row execute function private.emit_album_chat_event();

revoke all on function public.send_message(uuid, text, uuid, uuid), public.toggle_message_reaction(uuid) from public, anon;
grant execute on function public.send_message(uuid, text, uuid, uuid), public.toggle_message_reaction(uuid) to authenticated;
revoke all on function public.list_chat_messages(uuid) from public, anon;
grant execute on function public.list_chat_messages(uuid) to authenticated;
revoke all on function private.emit_album_chat_event() from public, anon, authenticated;
