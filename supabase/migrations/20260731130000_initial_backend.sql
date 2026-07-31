create schema if not exists private;

create extension if not exists pgcrypto with schema extensions;
create extension if not exists postgis with schema extensions;

create type public.account_status as enum ('pending', 'active', 'suspended', 'deleted');
create type public.conversation_status as enum ('active', 'blocked', 'closed');
create type public.report_reason as enum ('spam', 'harassment', 'fake_profile', 'other');
create type public.moderation_state as enum ('pending_review', 'in_review', 'resolved', 'dismissed');

create table public.accounts (
    id uuid primary key references auth.users (id) on delete cascade,
    status public.account_status not null default 'pending',
    birth_date date,
    adult_verified_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint accounts_adult_state check (
        status <> 'active' or (birth_date is not null and adult_verified_at is not null)
    )
);

create table public.profiles (
    id uuid primary key references public.accounts (id) on delete cascade,
    display_name text not null check (char_length(btrim(display_name)) between 1 and 60),
    age smallint not null check (age between 18 and 120),
    bio text not null default '' check (char_length(bio) <= 500),
    intent text not null default 'Conhecer pessoas' check (char_length(intent) between 1 and 80),
    region_code text not null check (char_length(region_code) between 2 and 40),
    discovery_visible boolean not null default true,
    verified boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

comment on column public.profiles.region_code is
    'Approximate region identifier only. Exact latitude/longitude must not be persisted here.';

create table public.entitlements (
    user_id uuid primary key references public.accounts (id) on delete cascade,
    plan text not null default 'free' check (plan in ('free', 'extra', 'pro')),
    new_conversation_limit integer not null default 5 check (new_conversation_limit between 0 and 1000),
    valid_until timestamptz,
    updated_at timestamptz not null default now()
);

create table public.conversations (
    id uuid primary key default gen_random_uuid(),
    participant_a uuid not null references public.accounts (id) on delete cascade,
    participant_b uuid not null references public.accounts (id) on delete cascade,
    started_by uuid not null references public.accounts (id) on delete restrict,
    status public.conversation_status not null default 'active',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_message_at timestamptz not null default now(),
    constraint conversations_canonical_pair check (participant_a < participant_b),
    constraint conversations_starter_is_member check (started_by in (participant_a, participant_b)),
    constraint conversations_unique_pair unique (participant_a, participant_b)
);

create table public.conversation_openings (
    id bigint generated always as identity primary key,
    conversation_id uuid not null unique references public.conversations (id) on delete cascade,
    opened_by uuid not null references public.accounts (id) on delete cascade,
    opened_at timestamptz not null default now()
);

create table public.messages (
    id uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references public.conversations (id) on delete cascade,
    sender_id uuid not null references public.accounts (id) on delete cascade,
    body text not null check (char_length(btrim(body)) between 1 and 2000),
    created_at timestamptz not null default now(),
    removed_at timestamptz
);

create table public.blocks (
    id bigint generated always as identity primary key,
    blocker_id uuid not null references public.accounts (id) on delete cascade,
    blocked_id uuid not null references public.accounts (id) on delete cascade,
    created_at timestamptz not null default now(),
    constraint blocks_distinct_users check (blocker_id <> blocked_id),
    constraint blocks_unique_direction unique (blocker_id, blocked_id)
);

create table public.reports (
    id uuid primary key default gen_random_uuid(),
    reporter_id uuid not null references public.accounts (id) on delete cascade,
    reported_user_id uuid not null references public.accounts (id) on delete cascade,
    conversation_id uuid references public.conversations (id) on delete set null,
    message_id uuid references public.messages (id) on delete set null,
    reason public.report_reason not null,
    details text not null default '' check (char_length(details) <= 1000),
    created_at timestamptz not null default now(),
    constraint reports_distinct_users check (reporter_id <> reported_user_id)
);

create table public.moderation_cases (
    id uuid primary key default gen_random_uuid(),
    report_id uuid not null unique references public.reports (id) on delete restrict,
    state public.moderation_state not null default 'pending_review',
    assigned_to uuid references auth.users (id) on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    resolved_at timestamptz
);

create table public.audit_events (
    id bigint generated always as identity primary key,
    event_type text not null check (char_length(event_type) between 1 and 80),
    actor_id uuid references auth.users (id) on delete set null,
    subject_user_id uuid references auth.users (id) on delete set null,
    moderation_case_id uuid references public.moderation_cases (id) on delete set null,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint audit_metadata_is_object check (jsonb_typeof(metadata) = 'object')
);

create index profiles_discovery_page_idx
    on public.profiles (region_code, id)
    where discovery_visible;
create index conversations_participant_a_recent_idx
    on public.conversations (participant_a, last_message_at desc)
    where status = 'active';
create index conversations_participant_b_recent_idx
    on public.conversations (participant_b, last_message_at desc)
    where status = 'active';
create index conversation_openings_quota_idx
    on public.conversation_openings (opened_by, opened_at desc);
create index messages_conversation_page_idx
    on public.messages (conversation_id, created_at desc, id desc);
create index blocks_reverse_lookup_idx
    on public.blocks (blocked_id, blocker_id);
create index reports_reported_user_recent_idx
    on public.reports (reported_user_id, created_at desc);
create index moderation_cases_queue_idx
    on public.moderation_cases (state, created_at);

create or replace function private.touch_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

create trigger accounts_touch_updated_at
before update on public.accounts
for each row execute function private.touch_updated_at();

create trigger profiles_touch_updated_at
before update on public.profiles
for each row execute function private.touch_updated_at();

create trigger entitlements_touch_updated_at
before update on public.entitlements
for each row execute function private.touch_updated_at();

create trigger conversations_touch_updated_at
before update on public.conversations
for each row execute function private.touch_updated_at();

create trigger moderation_cases_touch_updated_at
before update on public.moderation_cases
for each row execute function private.touch_updated_at();

create or replace function private.handle_new_auth_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.accounts (id)
    values (new.id)
    on conflict (id) do nothing;

    insert into public.entitlements (user_id)
    values (new.id)
    on conflict (user_id) do nothing;

    return new;
end;
$$;

create trigger auth_user_created
after insert on auth.users
for each row execute function private.handle_new_auth_user();

create or replace function private.is_blocked_pair(first_user uuid, second_user uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.blocks b
        where (b.blocker_id = first_user and b.blocked_id = second_user)
           or (b.blocker_id = second_user and b.blocked_id = first_user)
    );
$$;

create or replace function private.account_is_active(user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.accounts a
        where a.id = user_id
          and a.status = 'active'
          and a.adult_verified_at is not null
          and a.birth_date <= current_date - interval '18 years'
    );
$$;

create or replace function private.opening_limit(user_id uuid)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
    select coalesce(
        (
            select e.new_conversation_limit
            from public.entitlements e
            where e.user_id = opening_limit.user_id
              and (e.valid_until is null or e.valid_until > now())
        ),
        5
    );
$$;

create or replace function private.can_access_conversation(conversation_id uuid, user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.conversations c
        where c.id = can_access_conversation.conversation_id
          and c.status = 'active'
          and can_access_conversation.user_id in (c.participant_a, c.participant_b)
          and not private.is_blocked_pair(c.participant_a, c.participant_b)
    );
$$;

create or replace function public.get_chat_quota()
returns table (
    limit_count integer,
    used_count integer,
    remaining_count integer,
    next_reset_at timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_limit integer;
    v_used integer;
    v_oldest timestamptz;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;

    v_limit := private.opening_limit(v_user);
    select count(*)::integer, min(o.opened_at)
      into v_used, v_oldest
      from public.conversation_openings o
     where o.opened_by = v_user
       and o.opened_at > now() - interval '24 hours';

    return query select
        v_limit,
        v_used,
        greatest(v_limit - v_used, 0),
        case when v_oldest is null then null else v_oldest + interval '24 hours' end;
end;
$$;

create or replace function public.start_conversation(
    recipient_id uuid,
    first_message text
)
returns table (
    conversation_id uuid,
    was_created boolean,
    remaining_quota integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_sender uuid := auth.uid();
    v_participant_a uuid;
    v_participant_b uuid;
    v_conversation_id uuid;
    v_limit integer;
    v_used integer;
    v_now timestamptz := clock_timestamp();
begin
    if v_sender is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if recipient_id is null or recipient_id = v_sender then
        raise exception using errcode = 'P0001', message = 'INVALID_RECIPIENT';
    end if;
    if first_message is null
       or char_length(btrim(first_message)) = 0
       or char_length(btrim(first_message)) > 2000 then
        raise exception using errcode = 'P0001', message = 'INVALID_MESSAGE';
    end if;
    if not private.account_is_active(v_sender) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE';
    end if;
    if not private.account_is_active(recipient_id) then
        raise exception using errcode = 'P0001', message = 'RECIPIENT_NOT_AVAILABLE';
    end if;
    if private.is_blocked_pair(v_sender, recipient_id) then
        raise exception using errcode = 'P0001', message = 'CHAT_BLOCKED';
    end if;

    if v_sender < recipient_id then
        v_participant_a := v_sender;
        v_participant_b := recipient_id;
    else
        v_participant_a := recipient_id;
        v_participant_b := v_sender;
    end if;

    perform pg_advisory_xact_lock(hashtextextended(v_sender::text, 0));
    perform pg_advisory_xact_lock(
        hashtextextended(v_participant_a::text || ':' || v_participant_b::text, 0)
    );

    select c.id
      into v_conversation_id
      from public.conversations c
     where c.participant_a = v_participant_a
       and c.participant_b = v_participant_b
       and c.status = 'active'
     for update;

    v_limit := private.opening_limit(v_sender);
    select count(*)::integer
      into v_used
      from public.conversation_openings o
     where o.opened_by = v_sender
       and o.opened_at > v_now - interval '24 hours';

    if v_conversation_id is not null then
        insert into public.messages (conversation_id, sender_id, body, created_at)
        values (v_conversation_id, v_sender, btrim(first_message), v_now);

        update public.conversations
           set last_message_at = v_now
         where id = v_conversation_id;

        return query select v_conversation_id, false, greatest(v_limit - v_used, 0);
        return;
    end if;

    if v_used >= v_limit then
        raise exception using errcode = 'P0001', message = 'CHAT_QUOTA_EXHAUSTED';
    end if;

    insert into public.conversations (
        participant_a,
        participant_b,
        started_by,
        created_at,
        updated_at,
        last_message_at
    )
    values (
        v_participant_a,
        v_participant_b,
        v_sender,
        v_now,
        v_now,
        v_now
    )
    returning id into v_conversation_id;

    insert into public.conversation_openings (conversation_id, opened_by, opened_at)
    values (v_conversation_id, v_sender, v_now);

    insert into public.messages (conversation_id, sender_id, body, created_at)
    values (v_conversation_id, v_sender, btrim(first_message), v_now);

    return query select v_conversation_id, true, greatest(v_limit - v_used - 1, 0);
end;
$$;

create or replace function public.send_message(
    conversation_id uuid,
    message_body text
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
    if message_body is null
       or char_length(btrim(message_body)) = 0
       or char_length(btrim(message_body)) > 2000 then
        raise exception using errcode = 'P0001', message = 'INVALID_MESSAGE';
    end if;

    perform 1
      from public.conversations c
     where c.id = conversation_id
       and v_sender in (c.participant_a, c.participant_b)
       and c.status = 'active'
       and not private.is_blocked_pair(c.participant_a, c.participant_b)
     for update;

    if not found then
        raise exception using errcode = 'P0001', message = 'CHAT_NOT_AVAILABLE';
    end if;

    insert into public.messages (conversation_id, sender_id, body, created_at)
    values (conversation_id, v_sender, btrim(message_body), v_now)
    returning id into v_message_id;

    update public.conversations
       set last_message_at = v_now
     where id = conversation_id;

    return v_message_id;
end;
$$;

create or replace function public.block_user(blocked_user_id uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_blocker uuid := auth.uid();
begin
    if v_blocker is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if blocked_user_id is null or blocked_user_id = v_blocker then
        raise exception using errcode = 'P0001', message = 'INVALID_BLOCK_TARGET';
    end if;

    insert into public.blocks (blocker_id, blocked_id)
    values (v_blocker, blocked_user_id)
    on conflict (blocker_id, blocked_id) do nothing;

    update public.conversations c
       set status = 'blocked'
     where v_blocker in (c.participant_a, c.participant_b)
       and blocked_user_id in (c.participant_a, c.participant_b)
       and c.status = 'active';

    insert into public.audit_events (event_type, actor_id, subject_user_id)
    values ('user_blocked', v_blocker, blocked_user_id);

    return true;
end;
$$;

create or replace function public.report_user(
    reported_user_id uuid,
    report_reason public.report_reason,
    report_details text default '',
    related_conversation_id uuid default null,
    related_message_id uuid default null
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_reporter uuid := auth.uid();
    v_report_id uuid;
    v_case_id uuid;
begin
    if v_reporter is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if reported_user_id is null or reported_user_id = v_reporter then
        raise exception using errcode = 'P0001', message = 'INVALID_REPORT_TARGET';
    end if;
    if report_details is null or char_length(report_details) > 1000 then
        raise exception using errcode = 'P0001', message = 'INVALID_REPORT_DETAILS';
    end if;
    if related_conversation_id is not null and not exists (
        select 1
        from public.conversations c
        where c.id = related_conversation_id
          and v_reporter in (c.participant_a, c.participant_b)
          and reported_user_id in (c.participant_a, c.participant_b)
    ) then
        raise exception using errcode = 'P0001', message = 'INVALID_REPORT_CONTEXT';
    end if;
    if related_message_id is not null and not exists (
        select 1
        from public.messages m
        where m.id = related_message_id
          and m.conversation_id = related_conversation_id
          and m.sender_id = reported_user_id
    ) then
        raise exception using errcode = 'P0001', message = 'INVALID_REPORT_MESSAGE';
    end if;

    insert into public.reports (
        reporter_id,
        reported_user_id,
        conversation_id,
        message_id,
        reason,
        details
    )
    values (
        v_reporter,
        reported_user_id,
        related_conversation_id,
        related_message_id,
        report_reason,
        btrim(report_details)
    )
    returning id into v_report_id;

    insert into public.moderation_cases (report_id)
    values (v_report_id)
    returning id into v_case_id;

    insert into public.blocks (blocker_id, blocked_id)
    values (v_reporter, reported_user_id)
    on conflict (blocker_id, blocked_id) do nothing;

    update public.conversations c
       set status = 'blocked'
     where v_reporter in (c.participant_a, c.participant_b)
       and reported_user_id in (c.participant_a, c.participant_b)
       and c.status = 'active';

    insert into public.audit_events (
        event_type,
        actor_id,
        subject_user_id,
        moderation_case_id,
        metadata
    )
    values (
        'user_reported',
        v_reporter,
        reported_user_id,
        v_case_id,
        jsonb_build_object('reason', report_reason::text)
    );

    return v_case_id;
end;
$$;

alter table public.accounts enable row level security;
alter table public.profiles enable row level security;
alter table public.entitlements enable row level security;
alter table public.conversations enable row level security;
alter table public.conversation_openings enable row level security;
alter table public.messages enable row level security;
alter table public.blocks enable row level security;
alter table public.reports enable row level security;
alter table public.moderation_cases enable row level security;
alter table public.audit_events enable row level security;

create policy accounts_select_self
on public.accounts for select
to authenticated
using ((select auth.uid()) = id);

create policy profiles_select_visible_or_self
on public.profiles for select
to authenticated
using (
    (select auth.uid()) = id
    or (
        discovery_visible
        and private.account_is_active(id)
        and not private.is_blocked_pair((select auth.uid()), id)
    )
);

create policy entitlements_select_self
on public.entitlements for select
to authenticated
using ((select auth.uid()) = user_id);

create policy conversations_select_participant
on public.conversations for select
to authenticated
using (private.can_access_conversation(id, (select auth.uid())));

create policy messages_select_participant
on public.messages for select
to authenticated
using (private.can_access_conversation(conversation_id, (select auth.uid())));

create policy blocks_select_created_by_self
on public.blocks for select
to authenticated
using ((select auth.uid()) = blocker_id);

create policy reports_select_created_by_self
on public.reports for select
to authenticated
using ((select auth.uid()) = reporter_id);

revoke all on table public.accounts from anon, authenticated;
revoke all on table public.profiles from anon, authenticated;
revoke all on table public.entitlements from anon, authenticated;
revoke all on table public.conversations from anon, authenticated;
revoke all on table public.conversation_openings from anon, authenticated;
revoke all on table public.messages from anon, authenticated;
revoke all on table public.blocks from anon, authenticated;
revoke all on table public.reports from anon, authenticated;
revoke all on table public.moderation_cases from anon, authenticated;
revoke all on table public.audit_events from anon, authenticated;

grant select on table public.accounts to authenticated;
grant select on table public.profiles to authenticated;
grant select on table public.entitlements to authenticated;
grant select on table public.conversations to authenticated;
grant select on table public.messages to authenticated;
grant select on table public.blocks to authenticated;
grant select on table public.reports to authenticated;

revoke all on function public.get_chat_quota() from public;
revoke all on function public.start_conversation(uuid, text) from public;
revoke all on function public.send_message(uuid, text) from public;
revoke all on function public.block_user(uuid) from public;
revoke all on function public.report_user(uuid, public.report_reason, text, uuid, uuid) from public;

grant execute on function public.get_chat_quota() to authenticated;
grant execute on function public.start_conversation(uuid, text) to authenticated;
grant execute on function public.send_message(uuid, text) to authenticated;
grant execute on function public.block_user(uuid) to authenticated;
grant execute on function public.report_user(uuid, public.report_reason, text, uuid, uuid) to authenticated;

revoke all on schema private from public;
grant usage on schema private to authenticated;
grant execute on function private.is_blocked_pair(uuid, uuid) to authenticated;
grant execute on function private.account_is_active(uuid) to authenticated;
grant execute on function private.opening_limit(uuid) to authenticated;
grant execute on function private.can_access_conversation(uuid, uuid) to authenticated;

do $$
begin
    if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
        if not exists (
            select 1
            from pg_publication_tables
            where pubname = 'supabase_realtime'
              and schemaname = 'public'
              and tablename = 'messages'
        ) then
            alter publication supabase_realtime add table public.messages;
        end if;

        if not exists (
            select 1
            from pg_publication_tables
            where pubname = 'supabase_realtime'
              and schemaname = 'public'
              and tablename = 'conversations'
        ) then
            alter publication supabase_realtime add table public.conversations;
        end if;
    end if;
end;
$$;
