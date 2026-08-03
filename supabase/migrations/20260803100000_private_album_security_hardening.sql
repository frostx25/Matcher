-- Security hardening for profile reads, private album concurrency, deletion,
-- cleanup retries and restricted report evidence retention.

-- Authenticated clients no longer receive a row-level SELECT surface over the
-- whole profile table. Contextual reads are exposed only through RPCs.
drop policy if exists profiles_select_visible_or_self on public.profiles;
revoke select on table public.profiles from authenticated;

create or replace function public.get_my_profile()
returns table (
    id uuid,
    display_name text,
    age smallint,
    bio text,
    intent text,
    region_code text,
    verified boolean,
    avatar_path text
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;

    return query
    select
        profile.id,
        profile.display_name,
        profile.age,
        profile.bio,
        profile.intent,
        profile.region_code,
        profile.verified,
        profile.avatar_path
    from public.profiles profile
    where profile.id = v_user;
end;
$$;

revoke all on function public.get_my_profile() from public;
grant execute on function public.get_my_profile() to authenticated;

-- Keep already-installed APKs compatible without inferring gender. The legacy
-- overload delegates to the authoritative implementation with hidden,
-- conservative defaults.
create function public.complete_onboarding(
    birth_year integer,
    display_name text,
    region_code text,
    terms_version text,
    terms_accepted boolean,
    bio text default '',
    intent text default 'Conhecer pessoas'
)
returns table (
    profile_id uuid,
    account_status public.account_status,
    calculated_age smallint
)
language plpgsql
security definer
set search_path = ''
as $$
begin
    return query
    select *
    from public.complete_onboarding(
        $1,
        $2,
        $3,
        $4,
        $5,
        array['prefer_not_to_say']::text[],
        null::text,
        false,
        array['everyone']::text[],
        $6,
        $7
    );
end;
$$;

revoke all on function public.complete_onboarding(
    integer, text, text, text, boolean, text, text
) from public;
grant execute on function public.complete_onboarding(
    integer, text, text, text, boolean, text, text
) to authenticated;

-- Canonical locks make block/grant decisions deterministic for a user pair.
create or replace function private.lock_user_pair(first_user uuid, second_user uuid)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    v_first uuid;
    v_second uuid;
begin
    if first_user is null or second_user is null or first_user = second_user then
        raise exception using errcode = 'P0001', message = 'INVALID_USER_PAIR';
    end if;

    v_first := least(first_user, second_user);
    v_second := greatest(first_user, second_user);
    perform pg_advisory_xact_lock(
        hashtextextended(
            'matcher:user-pair:' || v_first::text || ':' || v_second::text,
            0
        )
    );
end;
$$;

create or replace function private.serialize_block_pair()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    perform private.lock_user_pair(new.blocker_id, new.blocked_id);
    return new;
end;
$$;

drop trigger if exists blocks_serialize_pair on public.blocks;
create trigger blocks_serialize_pair
before insert on public.blocks
for each row execute function private.serialize_block_pair();

-- A deleting album can coexist with one new active album while old evidence is
-- retained. Only the active album participates in owner-facing operations.
alter table private.private_albums
    drop constraint if exists private_albums_owner_id_key;

create unique index private_albums_one_active_per_owner_idx
    on private.private_albums (owner_id)
    where status = 'active';

-- Restricted evidence stores only the immutable object path and internal IDs.
-- It deliberately has no grants for anon/authenticated and survives logical
-- album deletion for the minimum 30-day hold.
create table private.private_album_report_evidence (
    report_id uuid not null references public.reports (id) on delete restrict,
    moderation_case_id uuid not null references public.moderation_cases (id) on delete restrict,
    album_id uuid not null,
    album_item_id uuid,
    object_path text not null check (char_length(object_path) between 100 and 180),
    created_at timestamptz not null default now(),
    hold_until timestamptz not null default (now() + interval '30 days'),
    primary key (report_id, object_path),
    constraint private_album_evidence_minimum_hold check (
        hold_until >= created_at + interval '30 days'
    )
);

-- The marker is intentionally independent from the nullable album/report FKs.
-- It preserves only the minimum pair/case association needed to prevent a
-- pending report from being bypassed after physical cleanup.
create table private.private_album_report_markers (
    report_id uuid primary key references public.reports (id) on delete restrict,
    moderation_case_id uuid not null unique
        references public.moderation_cases (id) on delete restrict,
    owner_id uuid not null,
    reporter_id uuid not null,
    created_at timestamptz not null default now(),
    constraint private_album_report_marker_distinct_users check (
        owner_id <> reporter_id
    )
);

create index private_album_report_evidence_path_hold_idx
    on private.private_album_report_evidence (object_path, hold_until);
create index private_album_report_evidence_pair_idx
    on private.private_album_report_evidence (album_id, hold_until);

alter table private.private_album_report_evidence enable row level security;
alter table private.private_album_report_markers enable row level security;
revoke all on table private.private_album_report_evidence from anon, authenticated;
revoke all on table private.private_album_report_markers from anon, authenticated;

create or replace function private.backfill_private_album_report_retention(
    hold_started_at timestamptz default now()
)
returns table (
    marker_rows integer,
    evidence_rows integer
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    v_started_at timestamptz := coalesce(hold_started_at, clock_timestamp());
    v_changed integer := 0;
begin
    marker_rows := 0;
    evidence_rows := 0;

    -- A live album FK is authoritative proof that this report came from the
    -- private-album flow.
    insert into private.private_album_report_markers (
        report_id,
        moderation_case_id,
        owner_id,
        reporter_id,
        created_at
    )
    select
        report.id,
        moderation_case.id,
        report.reported_user_id,
        report.reporter_id,
        report.created_at
    from public.reports report
    join public.moderation_cases moderation_case
      on moderation_case.report_id = report.id
    where report.private_album_id is not null
    on conflict (report_id) do nothing;
    get diagnostics marker_rows = row_count;

    -- If ON DELETE SET NULL already removed the album FK, accept only the
    -- exact internal audit correlation. Actor, subject and case must all match
    -- the report; a generic report or an ambiguous event is never inferred.
    insert into private.private_album_report_markers (
        report_id,
        moderation_case_id,
        owner_id,
        reporter_id,
        created_at
    )
    select distinct
        report.id,
        moderation_case.id,
        report.reported_user_id,
        report.reporter_id,
        least(report.created_at, audit_event.created_at)
    from public.reports report
    join public.moderation_cases moderation_case
      on moderation_case.report_id = report.id
    join public.audit_events audit_event
      on audit_event.moderation_case_id = moderation_case.id
     and audit_event.event_type = 'private_album.reported'
     and audit_event.actor_id = report.reporter_id
     and audit_event.subject_user_id = report.reported_user_id
    where report.private_album_id is null
    on conflict (report_id) do nothing;
    get diagnostics v_changed = row_count;
    marker_rows := marker_rows + v_changed;

    -- Existing object bytes receive a fresh minimum hold beginning at deploy.
    -- The item FK/path and Storage row must still agree; no path is reconstructed
    -- from audit text, names or other user content. A legacy full-album report
    -- (no item FK) only covers items that existed when the report was submitted,
    -- so later uploads are not swept into the retention hold.
    insert into private.private_album_report_evidence (
        report_id,
        moderation_case_id,
        album_id,
        album_item_id,
        object_path,
        created_at,
        hold_until
    )
    select
        report.id,
        moderation_case.id,
        album.id,
        item.id,
        item.object_path,
        v_started_at,
        v_started_at + interval '30 days'
    from public.reports report
    join public.moderation_cases moderation_case
      on moderation_case.report_id = report.id
    join private.private_albums album
      on album.id = report.private_album_id
    join private.private_album_items item
      on item.album_id = album.id
     and (
         report.private_album_item_id = item.id
         or (
             report.private_album_item_id is null
             and item.created_at <= report.created_at
         )
     )
    join storage.objects object
      on object.bucket_id = 'private-albums'
     and object.name = item.object_path
    on conflict (report_id, object_path) do update
       set hold_until = greatest(
           private.private_album_report_evidence.hold_until,
           excluded.hold_until
       )
     where private.private_album_report_evidence.hold_until < excluded.hold_until;
    get diagnostics evidence_rows = row_count;

    return next;
end;
$$;

revoke all on function private.backfill_private_album_report_retention(timestamptz)
from public;

do $$
begin
    perform * from private.backfill_private_album_report_retention(clock_timestamp());
end;
$$;

create or replace function private.private_album_object_hold_until(p_object_path text)
returns timestamptz
language sql
stable
security definer
set search_path = ''
as $$
    select max(evidence.hold_until)
    from private.private_album_report_evidence evidence
    where evidence.object_path = p_object_path
      and evidence.hold_until > now();
$$;

create or replace function private.album_report_regrant_is_prohibited(
    p_owner_id uuid,
    p_recipient_id uuid
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from private.private_album_report_markers marker
        join public.moderation_cases moderation_case
          on moderation_case.id = marker.moderation_case_id
        where marker.owner_id = p_owner_id
          and marker.reporter_id = p_recipient_id
          and (
              moderation_case.state in ('pending_review', 'in_review')
              or exists (
                  select 1
                  from private.private_album_report_evidence evidence
                  where evidence.report_id = marker.report_id
                    and evidence.hold_until > now()
              )
          )
    );
$$;

create or replace function private.guard_private_album_grant()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_owner uuid;
    v_album_status text;
begin
    if new.revoked_at is not null then
        return new;
    end if;

    select album.owner_id, album.status
      into v_owner, v_album_status
      from private.private_albums album
     where album.id = new.album_id;

    if v_owner is null or v_album_status <> 'active' then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_NOT_AVAILABLE';
    end if;

    perform private.lock_user_pair(v_owner, new.recipient_id);

    -- These checks intentionally run again after the canonical lock.
    if not private.account_is_active(v_owner) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE';
    end if;
    if not private.account_is_active(new.recipient_id) then
        raise exception using errcode = 'P0001', message = 'RECIPIENT_NOT_AVAILABLE';
    end if;
    if private.is_blocked_pair(v_owner, new.recipient_id) then
        raise exception using errcode = 'P0001', message = 'ALBUM_ACCESS_BLOCKED';
    end if;
    if private.album_report_regrant_is_prohibited(v_owner, new.recipient_id) then
        raise exception using errcode = 'P0001', message = 'ALBUM_ACCESS_REPORTED';
    end if;

    return new;
end;
$$;

drop trigger if exists private_album_grants_guard_active on private.private_album_grants;
create trigger private_album_grants_guard_active
before insert or update of revoked_at, revoke_reason
on private.private_album_grants
for each row
when (new.revoked_at is null)
execute function private.guard_private_album_grant();

drop function public.grant_private_album_access(uuid);
create function public.grant_private_album_access(
    target_album_id uuid,
    recipient_id uuid
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_album uuid;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if recipient_id is null or recipient_id = v_user then
        raise exception using errcode = 'P0001', message = 'INVALID_ALBUM_RECIPIENT';
    end if;

    perform private.lock_user_pair(v_user, recipient_id);

    if not private.account_is_active(v_user) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE';
    end if;
    if not private.account_is_active(recipient_id) then
        raise exception using errcode = 'P0001', message = 'RECIPIENT_NOT_AVAILABLE';
    end if;
    if private.is_blocked_pair(v_user, recipient_id) then
        raise exception using errcode = 'P0001', message = 'ALBUM_ACCESS_BLOCKED';
    end if;
    if private.album_report_regrant_is_prohibited(v_user, recipient_id) then
        raise exception using errcode = 'P0001', message = 'ALBUM_ACCESS_REPORTED';
    end if;

    select album.id
      into v_album
      from private.private_albums album
     where album.id = target_album_id
       and album.owner_id = v_user
       and album.status = 'active'
     for update;

    if not found then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_NOT_FOUND';
    end if;

    insert into private.private_album_grants (
        album_id,
        recipient_id,
        granted_at,
        revoked_at,
        revoked_by,
        revoke_reason
    )
    values (v_album, recipient_id, now(), null, null, null)
    on conflict on constraint private_album_grants_pkey do update
       set granted_at = excluded.granted_at,
           revoked_at = null,
           revoked_by = null,
           revoke_reason = null;

    insert into public.audit_events (event_type, actor_id, subject_user_id)
    values ('private_album.access_granted', v_user, recipient_id);

    return true;
end;
$$;

drop function public.revoke_private_album_access(uuid);
create function public.revoke_private_album_access(
    target_album_id uuid,
    recipient_id uuid
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_changed boolean;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if target_album_id is null
       or recipient_id is null
       or recipient_id = v_user then
        raise exception using errcode = 'P0001', message = 'INVALID_ALBUM_RECIPIENT';
    end if;

    perform private.lock_user_pair(v_user, recipient_id);

    update private.private_album_grants album_grant
       set revoked_at = now(),
           revoked_by = v_user,
           revoke_reason = 'owner'
      from private.private_albums album
     where album.id = target_album_id
       and album.id = album_grant.album_id
       and album.owner_id = v_user
       and album_grant.recipient_id = revoke_private_album_access.recipient_id
       and album_grant.revoked_at is null;

    v_changed := found;
    if v_changed then
        insert into public.audit_events (event_type, actor_id, subject_user_id)
        values ('private_album.access_revoked', v_user, recipient_id);
    end if;
    return v_changed;
end;
$$;

-- Object-path locks serialize the Storage INSERT policy with every logical or
-- physical deletion path.
create or replace function private.lock_private_album_object_path(p_object_path text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
    if p_object_path is null or char_length(p_object_path) not between 100 and 180 then
        raise exception using errcode = 'P0001', message = 'INVALID_PRIVATE_ALBUM_OBJECT_PATH';
    end if;
    perform pg_advisory_xact_lock(
        hashtextextended('matcher:private-album-object:' || p_object_path, 0)
    );
end;
$$;

create or replace function private.lock_private_album_owner(p_owner_id uuid)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
    if p_owner_id is null then
        raise exception using errcode = 'P0001', message = 'INVALID_PRIVATE_ALBUM_OWNER';
    end if;
    perform pg_advisory_xact_lock(
        hashtextextended('private-album:' || p_owner_id::text, 0)
    );
end;
$$;

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
          and private.private_album_metadata_is_safe(
              item.object_path,
              item.mime_type,
              can_insert_private_album_object.metadata
          )
    ) into v_allowed;

    return coalesce(v_allowed, false);
end;
$$;

-- Cleanup queue leases and retry metadata. Queue entries are tombstones: they
-- are created even when Storage is momentarily absent, so an old upload can
-- never become an untracked orphan.
alter table private.private_album_cleanup_queue
    add column lease_token uuid,
    add column leased_until timestamptz,
    add column next_attempt_at timestamptz not null default now(),
    add column last_error_code text,
    add constraint private_album_cleanup_last_error_safe check (
        last_error_code is null or last_error_code ~ '^[A-Z0-9_]{1,40}$'
    ),
    add constraint private_album_cleanup_lease_complete check (
        (lease_token is null and leased_until is null)
        or (lease_token is not null and leased_until is not null)
    );

create index private_album_cleanup_ready_idx
    on private.private_album_cleanup_queue (next_attempt_at, requested_at, object_path);

create or replace function private.enqueue_private_album_object(p_object_path text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into private.private_album_cleanup_queue (
        object_path,
        requested_at,
        next_attempt_at
    )
    values (p_object_path, now(), now())
    on conflict on constraint private_album_cleanup_queue_pkey do update
       set requested_at = least(
               private.private_album_cleanup_queue.requested_at,
               excluded.requested_at
           ),
           next_attempt_at = case
               when private.private_album_cleanup_queue.lease_token is null
                   then least(
                       private.private_album_cleanup_queue.next_attempt_at,
                       excluded.next_attempt_at
                   )
               else private.private_album_cleanup_queue.next_attempt_at
           end;
end;
$$;

create or replace function private.queue_private_album_object_after_item_delete()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    perform private.enqueue_private_album_object(old.object_path);
    return old;
end;
$$;

create or replace function private.lock_private_album_item_before_delete()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    perform private.lock_private_album_object_path(old.object_path);
    return old;
end;
$$;

drop trigger if exists private_album_items_lock_before_delete
on private.private_album_items;
create trigger private_album_items_lock_before_delete
before delete on private.private_album_items
for each row execute function private.lock_private_album_item_before_delete();

create or replace function private.lock_account_private_album_before_delete()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_path text;
begin
    perform private.lock_private_album_owner(old.id);
    for v_path in
        select item.object_path
        from private.private_album_items item
        join private.private_albums album on album.id = item.album_id
        where album.owner_id = old.id
        order by item.object_path
    loop
        perform private.lock_private_album_object_path(v_path);
    end loop;
    return old;
end;
$$;

drop trigger if exists accounts_lock_private_album_before_delete on public.accounts;
create trigger accounts_lock_private_album_before_delete
before delete on public.accounts
for each row execute function private.lock_account_private_album_before_delete();

create or replace function private.guard_private_album_storage_delete()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    if old.bucket_id <> 'private-albums' then
        return old;
    end if;

    perform private.lock_private_album_object_path(old.name);
    if private.private_album_object_hold_until(old.name) is not null then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_EVIDENCE_HOLD';
    end if;
    return old;
end;
$$;

drop trigger if exists storage_guard_private_album_delete on storage.objects;
create trigger storage_guard_private_album_delete
before delete on storage.objects
for each row execute function private.guard_private_album_storage_delete();

create or replace function private.clear_deleted_private_album_object()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_album uuid;
begin
    if old.bucket_id <> 'private-albums' then
        return old;
    end if;

    select item.album_id
      into v_album
      from private.private_album_items item
     where item.object_path = old.name;

    delete from private.private_album_items item
     where item.object_path = old.name;

    delete from private.private_album_report_evidence evidence
     where evidence.object_path = old.name
       and evidence.hold_until <= now();

    if v_album is not null
       and not exists (
           select 1
           from private.private_album_items item
           where item.album_id = v_album
       ) then
        delete from private.private_albums album
         where album.id = v_album
           and album.status = 'deleting';
    end if;

    return old;
end;
$$;

-- Owner operations use only the current active album. Old deleting albums are
-- internal evidence containers and never reappear in owner/client reads.
create or replace function public.create_private_album(
    content_policy_version text,
    content_policy_accepted boolean
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_album private.private_albums%rowtype;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if not private.account_is_active(v_user) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE';
    end if;
    if content_policy_accepted is distinct from true
       or content_policy_version is null
       or char_length(btrim(content_policy_version)) not between 1 and 40 then
        raise exception using errcode = 'P0001', message = 'CONTENT_POLICY_REQUIRED';
    end if;

    perform private.lock_private_album_owner(v_user);

    if exists (
        select 1
        from private.private_albums album
        where album.owner_id = v_user
          and album.status = 'removed_by_moderation'
    ) then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_NOT_AVAILABLE';
    end if;

    select album.*
      into v_album
      from private.private_albums album
     where album.owner_id = v_user
       and album.status = 'active'
     for update;

    if not found then
        insert into private.private_albums (
            owner_id,
            content_policy_version,
            content_policy_accepted_at
        )
        values (v_user, btrim(content_policy_version), now())
        returning * into v_album;
    else
        update private.private_albums album
           set content_policy_version = btrim(create_private_album.content_policy_version),
               content_policy_accepted_at = now()
         where album.id = v_album.id;
    end if;

    return v_album.id;
end;
$$;

drop function public.reserve_private_album_item(text);
create function public.reserve_private_album_item(
    target_album_id uuid,
    mime_type text
)
returns table (
    item_id uuid,
    object_path text,
    "position" smallint
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_album private.private_albums%rowtype;
    v_item uuid := gen_random_uuid();
    v_position smallint;
    v_extension text;
    v_path text;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if not private.account_is_active(v_user) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE';
    end if;

    v_extension := private.private_album_extension(lower(mime_type));
    if v_extension is null then
        raise exception using errcode = 'P0001', message = 'INVALID_PRIVATE_ALBUM_MEDIA_TYPE';
    end if;

    perform private.lock_private_album_owner(v_user);

    select album.*
      into v_album
      from private.private_albums album
     where album.id = target_album_id
       and album.owner_id = v_user
       and album.status = 'active'
     for update;

    if not found then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_NOT_FOUND';
    end if;

    select slot::smallint
      into v_position
      from generate_series(0, 9) slot
     where not exists (
         select 1
         from private.private_album_items item
         where item.album_id = v_album.id
           and item.position = slot
     )
     order by slot
     limit 1;

    if v_position is null then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_LIMIT_REACHED';
    end if;

    v_path := v_user::text || '/' || v_album.id::text || '/' ||
        v_item::text || '.' || v_extension;

    insert into private.private_album_items (
        id, album_id, object_path, mime_type, position, status
    )
    values (v_item, v_album.id, v_path, lower(mime_type), v_position, 'uploading');

    return query select v_item, v_path, v_position;
end;
$$;

create or replace function public.get_my_private_album()
returns table (
    album_id uuid,
    album_status text,
    item_count integer
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;

    return query
    select
        album.id,
        album.status,
        count(item.id) filter (
            where item.status in ('uploading', 'available')
        )::integer
    from private.private_albums album
    left join private.private_album_items item on item.album_id = album.id
    where album.owner_id = v_user
      and album.status = 'active'
    group by album.id, album.status;
end;
$$;

create or replace function public.list_my_private_album_items()
returns table (
    item_id uuid,
    "position" smallint,
    item_status text
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;

    return query
    select item.id, item.position, item.status
    from private.private_album_items item
    join private.private_albums album on album.id = item.album_id
    where album.owner_id = v_user
      and album.status = 'active'
      and item.status <> 'deleting'
    order by item.position nulls last, item.created_at, item.id;
end;
$$;

drop function public.get_private_album(uuid);
create function public.get_private_album(target_album_id uuid)
returns table (
    album_id uuid,
    item_id uuid,
    "position" smallint
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;

    if target_album_id is null
       or not private.can_access_private_album(target_album_id, v_user) then
        return;
    end if;

    return query
    select item.album_id, item.id, item.position
    from private.private_album_items item
    where item.album_id = target_album_id
      and item.status = 'available'
    order by item.position, item.id;
end;
$$;

drop function public.report_private_album(uuid, public.report_reason, text, uuid);
create function public.report_private_album(
    target_album_id uuid,
    report_reason public.report_reason,
    report_details text default '',
    album_item_id uuid default null
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_reporter uuid := auth.uid();
    v_owner uuid;
    v_album uuid;
    v_report uuid;
    v_case uuid;
    v_paths text[] := array[]::text[];
    v_path text;
    v_expected_evidence integer := 0;
    v_inserted_evidence integer := 0;
    v_now timestamptz := clock_timestamp();
begin
    if v_reporter is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if target_album_id is null then
        raise exception using errcode = 'P0001', message = 'INVALID_REPORT_TARGET';
    end if;
    if report_details is null or char_length(report_details) > 1000 then
        raise exception using errcode = 'P0001', message = 'INVALID_REPORT_DETAILS';
    end if;

    select album.owner_id
      into v_owner
      from private.private_albums album
     where album.id = target_album_id
       and album.status = 'active';

    if v_owner is null or v_owner = v_reporter then
        raise exception using errcode = 'P0001', message = 'INVALID_REPORT_TARGET';
    end if;

    perform private.lock_user_pair(v_reporter, v_owner);
    perform private.lock_private_album_owner(v_owner);

    select album.id
      into v_album
      from private.private_albums album
      join private.private_album_grants album_grant
        on album_grant.album_id = album.id
       and album_grant.recipient_id = v_reporter
       and album_grant.revoked_at is null
     where album.id = target_album_id
       and album.owner_id = v_owner
       and album.status = 'active'
       and private.can_access_private_album(album.id, v_reporter);

    if not found then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_NOT_AVAILABLE';
    end if;

    select coalesce(array_agg(item.object_path order by item.object_path), array[]::text[])
      into v_paths
      from private.private_album_items item
     where item.album_id = v_album
       and item.status = 'available'
       and (album_item_id is null or item.id = album_item_id);

    if cardinality(v_paths) = 0 then
        if album_item_id is not null then
            raise exception using errcode = 'P0001', message = 'INVALID_REPORT_CONTEXT';
        end if;
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_REPORT_RETRY';
    end if;
    if album_item_id is not null and cardinality(v_paths) <> 1 then
        raise exception using errcode = 'P0001', message = 'INVALID_REPORT_CONTEXT';
    end if;

    foreach v_path in array v_paths
    loop
        perform private.lock_private_album_object_path(v_path);
    end loop;

    -- Revalidate the grant and album only after owner -> ordered-path locks,
    -- matching begin deletion's lock order and avoiding a grant/path deadlock.
    perform 1
      from private.private_albums album
      join private.private_album_grants album_grant
        on album_grant.album_id = album.id
       and album_grant.recipient_id = v_reporter
       and album_grant.revoked_at is null
     where album.id = v_album
       and album.owner_id = v_owner
       and album.status = 'active'
       and private.can_access_private_album(album.id, v_reporter)
     for update of album, album_grant;

    if not found then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_NOT_AVAILABLE';
    end if;

    v_expected_evidence := cardinality(v_paths);
    if v_expected_evidence > 0 and (
        select count(*)
        from private.private_album_items item
        where item.album_id = v_album
          and item.status = 'available'
          and item.object_path = any(v_paths)
    ) <> v_expected_evidence then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_REPORT_RETRY';
    end if;

    insert into public.reports (
        reporter_id,
        reported_user_id,
        reason,
        details,
        private_album_id,
        private_album_item_id
    )
    values (
        v_reporter,
        v_owner,
        report_reason,
        btrim(report_details),
        v_album,
        album_item_id
    )
    returning id into v_report;

    insert into public.moderation_cases (report_id)
    values (v_report)
    returning id into v_case;

    insert into private.private_album_report_markers (
        report_id,
        moderation_case_id,
        owner_id,
        reporter_id,
        created_at
    )
    values (v_report, v_case, v_owner, v_reporter, v_now);

    insert into private.private_album_report_evidence (
        report_id,
        moderation_case_id,
        album_id,
        album_item_id,
        object_path,
        created_at,
        hold_until
    )
    select
        v_report,
        v_case,
        v_album,
        item.id,
        item.object_path,
        v_now,
        v_now + interval '30 days'
    from private.private_album_items item
    where item.album_id = v_album
      and item.status = 'available'
      and item.object_path = any(v_paths);

    get diagnostics v_inserted_evidence = row_count;
    if v_inserted_evidence <> v_expected_evidence then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_REPORT_RETRY';
    end if;

    update private.private_album_grants album_grant
       set revoked_at = v_now,
           revoked_by = v_reporter,
           revoke_reason = 'reported'
     where album_grant.album_id = v_album
       and album_grant.recipient_id = v_reporter
       and album_grant.revoked_at is null;

    insert into public.audit_events (
        event_type,
        actor_id,
        subject_user_id,
        moderation_case_id,
        metadata
    )
    values (
        'private_album.reported',
        v_reporter,
        v_owner,
        v_case,
        jsonb_build_object('reason', report_reason::text)
    );

    return v_case;
end;
$$;

drop function public.mark_private_album_item_for_deletion(uuid);
create function public.mark_private_album_item_for_deletion(album_item_id uuid)
returns table (
    object_path text,
    delete_now boolean,
    hold_until timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_path text;
    v_hold_until timestamptz;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;

    select item.object_path
      into v_path
      from private.private_album_items item
      join private.private_albums album on album.id = item.album_id
     where item.id = album_item_id
       and album.owner_id = v_user;

    if v_path is null then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_ITEM_NOT_FOUND';
    end if;

    perform private.lock_private_album_object_path(v_path);

    update private.private_album_items item
       set status = 'deleting',
           position = null
      from private.private_albums album
     where album.id = item.album_id
       and album.owner_id = v_user
       and item.id = album_item_id
       and item.status <> 'deleting';

    if not found and not exists (
        select 1
        from private.private_album_items item
        join private.private_albums album on album.id = item.album_id
        where item.id = album_item_id
          and album.owner_id = v_user
          and item.status = 'deleting'
    ) then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_ITEM_NOT_FOUND';
    end if;

    perform private.enqueue_private_album_object(v_path);
    v_hold_until := private.private_album_object_hold_until(v_path);
    return query select v_path, v_hold_until is null, v_hold_until;
end;
$$;

drop function public.begin_private_album_deletion();
create function public.begin_private_album_deletion(target_album_id uuid)
returns table (
    object_path text,
    delete_now boolean,
    hold_until timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_status text;
    v_path text;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if target_album_id is null then
        raise exception using errcode = 'P0001', message = 'INVALID_PRIVATE_ALBUM';
    end if;

    perform private.lock_private_album_owner(v_user);

    select album.status
      into v_status
      from private.private_albums album
     where album.id = target_album_id
       and album.owner_id = v_user;

    -- Missing (including a non-owned opaque id) is an idempotent no-op and
    -- cannot affect a replacement active album.
    if v_status is null then
        return;
    end if;
    if v_status not in ('active', 'deleting') then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_NOT_AVAILABLE';
    end if;

    for v_path in
        select item.object_path
        from private.private_album_items item
        where item.album_id = target_album_id
        order by item.object_path
    loop
        perform private.lock_private_album_object_path(v_path);
    end loop;

    select album.status
      into v_status
      from private.private_albums album
     where album.id = target_album_id
       and album.owner_id = v_user
     for update;

    if v_status = 'active' then
        update private.private_albums album
           set status = 'deleting'
         where album.id = target_album_id;

        update private.private_album_grants album_grant
           set revoked_at = coalesce(album_grant.revoked_at, now()),
               revoked_by = coalesce(album_grant.revoked_by, v_user),
               revoke_reason = coalesce(album_grant.revoke_reason, 'album_deleted')
         where album_grant.album_id = target_album_id;

        update private.private_album_items item
           set status = 'deleting',
               position = null
         where item.album_id = target_album_id;
    elsif v_status <> 'deleting' then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_NOT_AVAILABLE';
    end if;

    for v_path in
        select item.object_path
        from private.private_album_items item
        where item.album_id = target_album_id
        order by item.object_path
    loop
        perform private.enqueue_private_album_object(v_path);
    end loop;

    if not exists (
        select 1
        from private.private_album_items item
        where item.album_id = target_album_id
    ) then
        delete from private.private_albums album
         where album.id = target_album_id
           and album.owner_id = v_user
           and album.status = 'deleting';
        return;
    end if;

    return query
    select
        item.object_path,
        private.private_album_object_hold_until(item.object_path) is null,
        private.private_album_object_hold_until(item.object_path)
    from private.private_album_items item
    where item.album_id = target_album_id
    order by item.created_at, item.id;
end;
$$;

drop function public.finalize_private_album_deletion();
create function public.finalize_private_album_deletion(target_album_id uuid)
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
    if target_album_id is null then
        raise exception using errcode = 'P0001', message = 'INVALID_PRIVATE_ALBUM';
    end if;

    if not exists (
        select 1
        from private.private_albums album
        where album.id = target_album_id
          and album.owner_id = v_user
          and album.status = 'deleting'
    ) then
        if exists (
            select 1
            from private.private_albums album
            where album.id = target_album_id
              and album.owner_id = v_user
        ) then
            raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_NOT_DELETING';
        end if;
        return true;
    end if;

    if exists (
        select 1
        from private.private_album_items item
        join storage.objects object
          on object.bucket_id = 'private-albums'
         and object.name = item.object_path
        where item.album_id = target_album_id
          and private.private_album_object_hold_until(item.object_path) is null
    ) then
        return false;
    end if;

    -- Remove non-held metadata whose object is already absent. The item
    -- BEFORE/AFTER triggers keep a path tombstone queued for stale uploads and
    -- idempotent worker confirmation.
    delete from private.private_album_items item
     where item.album_id = target_album_id
       and private.private_album_object_hold_until(item.object_path) is null
       and not exists (
           select 1
           from storage.objects object
           where object.bucket_id = 'private-albums'
             and object.name = item.object_path
       );

    if not exists (
        select 1
        from private.private_album_items item
        where item.album_id = target_album_id
    ) then
        delete from private.private_albums album
         where album.id = target_album_id
           and album.owner_id = v_user
           and album.status = 'deleting';
    end if;

    -- Metadata for held objects remains private until cleanup after hold expiry.
    -- This is a successful logical deletion because no deleting album is ever
    -- returned by owner or recipient RPCs.
    return true;
end;
$$;

create or replace function public.moderate_private_album_item(
    album_item_id uuid,
    decision text
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_owner uuid;
    v_path text;
begin
    if auth.role() <> 'service_role' then
        raise exception using errcode = '42501', message = 'SERVICE_ROLE_REQUIRED';
    end if;
    if decision <> 'removed_by_moderation' then
        raise exception using errcode = 'P0001', message = 'INVALID_PRIVATE_ALBUM_DECISION';
    end if;

    select album.owner_id, item.object_path
      into v_owner, v_path
      from private.private_album_items item
      join private.private_albums album on album.id = item.album_id
     where item.id = album_item_id;

    if v_path is null then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_ITEM_NOT_FOUND';
    end if;

    perform private.lock_private_album_object_path(v_path);

    update private.private_album_items item
       set status = 'removed_by_moderation',
           position = null
     where item.id = album_item_id;

    perform private.enqueue_private_album_object(v_path);

    insert into public.audit_events (event_type, actor_id, subject_user_id)
    values ('private_album.item_removed_by_moderation', auth.uid(), v_owner);

    return decision;
end;
$$;

create or replace function public.moderate_private_album(
    album_id uuid,
    decision text
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_owner uuid;
    v_path text;
begin
    if auth.role() <> 'service_role' then
        raise exception using errcode = '42501', message = 'SERVICE_ROLE_REQUIRED';
    end if;
    if decision <> 'removed_by_moderation' then
        raise exception using errcode = 'P0001', message = 'INVALID_PRIVATE_ALBUM_DECISION';
    end if;

    select album.owner_id
      into v_owner
      from private.private_albums album
     where album.id = moderate_private_album.album_id;

    if v_owner is null then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_NOT_FOUND';
    end if;

    perform private.lock_private_album_owner(v_owner);
    for v_path in
        select item.object_path
        from private.private_album_items item
        where item.album_id = moderate_private_album.album_id
        order by item.object_path
    loop
        perform private.lock_private_album_object_path(v_path);
    end loop;

    update private.private_albums album
       set status = 'removed_by_moderation'
     where album.id = moderate_private_album.album_id;

    update private.private_album_grants album_grant
       set revoked_at = coalesce(album_grant.revoked_at, now()),
           revoke_reason = coalesce(album_grant.revoke_reason, 'moderation')
     where album_grant.album_id = moderate_private_album.album_id;

    update private.private_album_items item
       set status = 'removed_by_moderation',
           position = null
     where item.album_id = moderate_private_album.album_id;

    for v_path in
        select item.object_path
        from private.private_album_items item
        where item.album_id = moderate_private_album.album_id
        order by item.object_path
    loop
        perform private.enqueue_private_album_object(v_path);
    end loop;

    insert into public.audit_events (event_type, actor_id, subject_user_id)
    values ('private_album.removed_by_moderation', auth.uid(), v_owner);

    return decision;
end;
$$;

drop function public.get_private_album_cleanup_batch(integer);
create function public.get_private_album_cleanup_batch(batch_size integer default 100)
returns table (
    object_path text,
    lease_token uuid,
    leased_until timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
begin
    if auth.role() <> 'service_role' then
        raise exception using errcode = '42501', message = 'SERVICE_ROLE_REQUIRED';
    end if;
    if batch_size not between 1 and 500 then
        raise exception using errcode = 'P0001', message = 'INVALID_BATCH_SIZE';
    end if;

    return query
    with selected as (
        select queued.object_path
        from private.private_album_cleanup_queue queued
        where queued.next_attempt_at <= now()
          and (queued.leased_until is null or queued.leased_until <= now())
          and private.private_album_object_hold_until(queued.object_path) is null
        order by queued.next_attempt_at, queued.requested_at, queued.object_path
        limit batch_size
        for update skip locked
    )
    update private.private_album_cleanup_queue queued
       set attempts = queued.attempts + 1,
           last_attempt_at = now(),
           lease_token = gen_random_uuid(),
           leased_until = now() + interval '5 minutes',
           last_error_code = null
      from selected
     where queued.object_path = selected.object_path
    returning queued.object_path, queued.lease_token, queued.leased_until;
end;
$$;

drop function public.confirm_private_album_object_deleted(text);
create function public.confirm_private_album_object_deleted(
    object_path text,
    lease_token uuid
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_album uuid;
begin
    if auth.role() <> 'service_role' then
        raise exception using errcode = '42501', message = 'SERVICE_ROLE_REQUIRED';
    end if;
    if lease_token is null or not exists (
        select 1
        from private.private_album_cleanup_queue queued
        where queued.object_path = confirm_private_album_object_deleted.object_path
          and queued.lease_token = confirm_private_album_object_deleted.lease_token
    ) then
        raise exception using errcode = 'P0001', message = 'CLEANUP_LEASE_NOT_OWNED';
    end if;
    if private.private_album_object_hold_until(object_path) is not null then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_EVIDENCE_HOLD';
    end if;
    if exists (
        select 1
        from storage.objects object
        where object.bucket_id = 'private-albums'
          and object.name = confirm_private_album_object_deleted.object_path
    ) then
        return false;
    end if;

    select item.album_id
      into v_album
      from private.private_album_items item
     where item.object_path = confirm_private_album_object_deleted.object_path;

    delete from private.private_album_items item
     where item.object_path = confirm_private_album_object_deleted.object_path
       and item.status in ('deleting', 'removed_by_moderation');

    delete from private.private_album_report_evidence evidence
     where evidence.object_path = confirm_private_album_object_deleted.object_path
       and evidence.hold_until <= now();

    delete from private.private_album_cleanup_queue queued
     where queued.object_path = confirm_private_album_object_deleted.object_path
       and queued.lease_token = confirm_private_album_object_deleted.lease_token;

    if v_album is not null
       and not exists (
           select 1 from private.private_album_items item where item.album_id = v_album
       ) then
        delete from private.private_albums album
         where album.id = v_album
           and album.status = 'deleting';
    end if;

    return true;
end;
$$;

create function public.fail_private_album_object_cleanup(
    object_path text,
    lease_token uuid,
    failure_code text default 'DELETE_FAILED'
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_changed boolean;
begin
    if auth.role() <> 'service_role' then
        raise exception using errcode = '42501', message = 'SERVICE_ROLE_REQUIRED';
    end if;
    if failure_code is null or failure_code !~ '^[A-Z0-9_]{1,40}$' then
        raise exception using errcode = 'P0001', message = 'INVALID_CLEANUP_FAILURE_CODE';
    end if;

    update private.private_album_cleanup_queue queued
       set lease_token = null,
           leased_until = null,
           last_error_code = failure_code,
           next_attempt_at = now() + least(
               interval '6 hours',
               interval '30 seconds' * power(
                   2::double precision,
                   least(greatest(queued.attempts - 1, 0), 10)
               )
           )
     where queued.object_path = fail_private_album_object_cleanup.object_path
       and queued.lease_token = fail_private_album_object_cleanup.lease_token;

    v_changed := found;
    if not v_changed then
        raise exception using errcode = 'P0001', message = 'CLEANUP_LEASE_NOT_OWNED';
    end if;
    return true;
end;
$$;

-- Re-apply the exact public API permissions after replacing return shapes.
revoke all on function public.reserve_private_album_item(uuid, text) from public;
revoke all on function public.get_private_album(uuid) from public;
revoke all on function public.grant_private_album_access(uuid, uuid) from public;
revoke all on function public.revoke_private_album_access(uuid, uuid) from public;
revoke all on function public.report_private_album(
    uuid, public.report_reason, text, uuid
) from public;
revoke all on function public.mark_private_album_item_for_deletion(uuid) from public;
revoke all on function public.begin_private_album_deletion(uuid) from public;
revoke all on function public.finalize_private_album_deletion(uuid) from public;
grant execute on function public.reserve_private_album_item(uuid, text) to authenticated;
grant execute on function public.get_private_album(uuid) to authenticated;
grant execute on function public.grant_private_album_access(uuid, uuid) to authenticated;
grant execute on function public.revoke_private_album_access(uuid, uuid) to authenticated;
grant execute on function public.report_private_album(
    uuid, public.report_reason, text, uuid
) to authenticated;
grant execute on function public.mark_private_album_item_for_deletion(uuid) to authenticated;
grant execute on function public.begin_private_album_deletion(uuid) to authenticated;
grant execute on function public.finalize_private_album_deletion(uuid) to authenticated;

revoke all on function public.get_private_album_cleanup_batch(integer) from public;
revoke all on function public.confirm_private_album_object_deleted(text, uuid) from public;
revoke all on function public.fail_private_album_object_cleanup(text, uuid, text) from public;
grant execute on function public.get_private_album_cleanup_batch(integer) to service_role;
grant execute on function public.confirm_private_album_object_deleted(text, uuid) to service_role;
grant execute on function public.fail_private_album_object_cleanup(text, uuid, text) to service_role;

revoke all on function private.lock_user_pair(uuid, uuid) from public;
revoke all on function private.private_album_object_hold_until(text) from public;
revoke all on function private.album_report_regrant_is_prohibited(uuid, uuid) from public;
revoke all on function private.lock_private_album_object_path(text) from public;
revoke all on function private.lock_private_album_owner(uuid) from public;
revoke all on function private.enqueue_private_album_object(text) from public;

-- The Storage INSERT policy calls only this narrow helper; there remain no
-- authenticated SELECT/UPDATE/DELETE grants or policies for private-albums.
revoke all on function private.can_insert_private_album_object(text, uuid, jsonb) from public;
grant execute on function private.can_insert_private_album_object(text, uuid, jsonb)
to authenticated;
