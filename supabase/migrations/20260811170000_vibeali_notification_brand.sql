-- Public-facing product rename. Internal identifiers remain stable so existing
-- Firebase installations and Android upgrades keep working.

alter table private.notification_outbox
    drop constraint if exists notification_payload_is_private;

update private.notification_outbox
   set payload = jsonb_set(payload, '{title}', '"VibeAli"'::jsonb, false)
 where payload ->> 'title' = 'Matcher';

alter table private.notification_outbox
    add constraint notification_payload_is_private check (
        payload = jsonb_build_object(
            'title', 'VibeAli',
            'body', 'Nova mensagem',
            'conversation_id', payload ->> 'conversation_id'
        )
    );

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

    if v_recipient is null then return new; end if;

    select state.muted into v_muted
      from public.conversation_user_states state
     where state.conversation_id = new.conversation_id
       and state.user_id = v_recipient;

    if not coalesce(v_muted, false) then
        insert into private.notification_outbox (recipient_id, message_id, payload)
        values (
            v_recipient,
            new.id,
            jsonb_build_object(
                'title', 'VibeAli',
                'body', 'Nova mensagem',
                'conversation_id', new.conversation_id::text
            )
        )
        on conflict (message_id) do nothing;
    end if;
    return new;
end;
$$;
