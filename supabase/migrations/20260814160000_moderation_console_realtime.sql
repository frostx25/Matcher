-- A single, metadata-free pulse lets authorized staff refresh the console
-- without exposing moderation records through Realtime.

create table public.moderation_console_sync (
    singleton boolean primary key default true check (singleton),
    revision bigint not null default 0,
    updated_at timestamptz not null default now()
);

insert into public.moderation_console_sync (singleton) values (true)
on conflict (singleton) do nothing;

alter table public.moderation_console_sync enable row level security;
revoke all on table public.moderation_console_sync from public, anon;
grant select on table public.moderation_console_sync to authenticated;

create policy moderation_console_sync_staff_read
on public.moderation_console_sync for select
to authenticated
using (private.is_active_moderator((select auth.uid())));

create function private.touch_moderation_console_sync()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    update public.moderation_console_sync
       set revision = revision + 1,
           updated_at = now()
     where singleton;
    return null;
end;
$$;

revoke all on function private.touch_moderation_console_sync() from public, anon, authenticated;

create trigger reports_touch_moderation_console
after insert or update or delete on public.reports
for each statement execute function private.touch_moderation_console_sync();

create trigger moderation_cases_touch_moderation_console
after insert or update or delete on public.moderation_cases
for each statement execute function private.touch_moderation_console_sync();

create trigger audit_events_touch_moderation_console
after insert or update or delete on public.audit_events
for each statement execute function private.touch_moderation_console_sync();

create trigger profile_photo_submissions_touch_moderation_console
after insert or update or delete on private.profile_photo_submissions
for each statement execute function private.touch_moderation_console_sync();

create trigger moderation_appeals_touch_moderation_console
after insert or update or delete on private.moderation_appeals
for each statement execute function private.touch_moderation_console_sync();

create trigger moderation_staff_touch_moderation_console
after insert or update or delete on private.moderation_staff
for each statement execute function private.touch_moderation_console_sync();

do $$
begin
    alter publication supabase_realtime add table public.moderation_console_sync;
exception
    when duplicate_object then null;
end;
$$;

comment on table public.moderation_console_sync is
    'Metadata-free Realtime pulse visible only to active moderation staff.';
