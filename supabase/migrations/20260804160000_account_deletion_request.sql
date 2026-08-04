-- Immediate logical account deletion. Physical erasure remains an audited worker
-- operation so evidence under a valid safety hold is not destroyed by the client.

create table private.account_deletion_requests (
    user_id uuid primary key references auth.users (id) on delete cascade,
    requested_at timestamptz not null default now(),
    state text not null default 'pending' check (state in ('pending', 'processing', 'completed', 'held')),
    updated_at timestamptz not null default now()
);

alter table private.account_deletion_requests enable row level security;
revoke all on table private.account_deletion_requests from anon, authenticated;

create or replace function public.request_account_deletion()
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;

    perform pg_advisory_xact_lock(hashtextextended('account-delete:' || v_user::text, 0));

    insert into private.account_deletion_requests (user_id, requested_at, state, updated_at)
    values (v_user, now(), 'pending', now())
    on conflict (user_id) do update
       set requested_at = least(private.account_deletion_requests.requested_at, excluded.requested_at),
           updated_at = now();

    update public.accounts
       set status = 'deleted', updated_at = now()
     where id = v_user and status <> 'deleted';

    update public.profiles
       set discovery_visible = false, updated_at = now()
     where id = v_user;

    update public.conversations
       set status = 'closed', updated_at = now()
     where v_user in (participant_a, participant_b)
       and status = 'active';

    update private.private_album_grants grant_row
       set revoked_at = coalesce(grant_row.revoked_at, now()),
           revoked_by = coalesce(grant_row.revoked_by, v_user),
           revoke_reason = coalesce(grant_row.revoke_reason, 'album_deleted')
      from private.private_albums album
     where album.id = grant_row.album_id
       and grant_row.revoked_at is null
       and (album.owner_id = v_user or grant_row.recipient_id = v_user);

    delete from private.notification_outbox where recipient_id = v_user and state = 'pending';

    insert into public.audit_events (event_type, actor_id, subject_user_id)
    values ('account_deletion_requested', v_user, v_user);

    return true;
end;
$$;

revoke all on function public.request_account_deletion() from public;
grant execute on function public.request_account_deletion() to authenticated;
