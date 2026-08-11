-- Per-user conversation deletion. The peer keeps their history; a later
-- message restores the conversation for the user who dismissed it.

alter table public.conversation_user_states
    add column if not exists deleted_at timestamptz;

create or replace function public.set_conversation_deleted(
    target_conversation_id uuid,
    deleted boolean
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then raise exception 'AUTH_REQUIRED' using errcode = '42501'; end if;
    if not private.can_access_conversation(target_conversation_id, v_user) then
        raise exception 'CHAT_NOT_AVAILABLE' using errcode = '42501';
    end if;

    insert into public.conversation_user_states(conversation_id, user_id, deleted_at, archived_at)
    values (target_conversation_id, v_user, case when deleted then now() end, null)
    on conflict (conversation_id, user_id) do update
       set deleted_at = excluded.deleted_at,
           archived_at = null;
    return deleted;
end;
$$;

create or replace function public.list_deleted_conversation_ids()
returns setof uuid
language sql
stable
security definer
set search_path = ''
as $$
    select state.conversation_id
      from public.conversation_user_states state
     where state.user_id = auth.uid()
       and state.deleted_at is not null;
$$;

create or replace function private.restore_conversation_for_new_message()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    update public.conversation_user_states state
       set deleted_at = null
     where state.conversation_id = new.conversation_id
       and state.user_id <> new.sender_id
       and state.deleted_at is not null;
    return new;
end;
$$;

drop trigger if exists messages_restore_deleted_conversation on public.messages;
create trigger messages_restore_deleted_conversation
after insert on public.messages
for each row execute function private.restore_conversation_for_new_message();

revoke all on function public.set_conversation_deleted(uuid, boolean),
    public.list_deleted_conversation_ids() from public, anon;
grant execute on function public.set_conversation_deleted(uuid, boolean),
    public.list_deleted_conversation_ids() to authenticated;

