-- Ephemeral, participant-only typing state. No message content is stored.

create table public.conversation_typing_states (
    conversation_id uuid not null references public.conversations(id) on delete cascade,
    user_id uuid not null references public.accounts(id) on delete cascade,
    expires_at timestamptz not null,
    primary key (conversation_id, user_id)
);

alter table public.conversation_typing_states enable row level security;
revoke all on table public.conversation_typing_states from public, anon, authenticated;
grant select on table public.conversation_typing_states to authenticated;

create policy conversation_typing_participant_select on public.conversation_typing_states
for select to authenticated
using (private.can_access_conversation(conversation_id, auth.uid()));

create or replace function public.set_conversation_typing(target_conversation_id uuid, typing boolean)
returns boolean
language plpgsql security definer set search_path = '' as $$
declare v_user uuid := auth.uid();
begin
    if v_user is null then raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED'; end if;
    if not private.can_access_conversation(target_conversation_id, v_user) then
        raise exception using errcode = 'P0001', message = 'CHAT_NOT_AVAILABLE';
    end if;
    delete from public.conversation_typing_states state where state.expires_at <= now();
    if typing then
        insert into public.conversation_typing_states(conversation_id, user_id, expires_at)
        values (target_conversation_id, v_user, now() + interval '8 seconds')
        on conflict (conversation_id, user_id) do update set expires_at = excluded.expires_at;
    else
        delete from public.conversation_typing_states state
         where state.conversation_id = target_conversation_id and state.user_id = v_user;
    end if;
    return typing;
end;
$$;

create or replace function public.list_typing_conversation_ids()
returns setof uuid
language sql stable security definer set search_path = '' as $$
    select state.conversation_id from public.conversation_typing_states state
     where state.user_id <> auth.uid() and state.expires_at > now()
       and private.can_access_conversation(state.conversation_id, auth.uid());
$$;

revoke all on function public.set_conversation_typing(uuid, boolean),
 public.list_typing_conversation_ids() from public, anon;
grant execute on function public.set_conversation_typing(uuid, boolean),
 public.list_typing_conversation_ids() to authenticated;

do $$ begin
    alter publication supabase_realtime add table public.conversation_typing_states;
exception when duplicate_object then null;
end $$;
