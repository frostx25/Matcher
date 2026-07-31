-- Gender identity is profile data with user-controlled visibility. Discovery
-- preference and private-album metadata remain in the private schema and are
-- exposed only through narrow, authenticated RPCs.

create table public.gender_options (
    id text primary key,
    label_pt_br text not null check (char_length(btrim(label_pt_br)) between 1 and 60),
    catalog_version smallint not null default 1 check (catalog_version > 0),
    identity_selectable boolean not null,
    preference_selectable boolean not null,
    allows_self_description boolean not null default false,
    exclusive_selection boolean not null default false,
    active boolean not null default true,
    sort_order smallint not null unique,
    constraint gender_options_id_format check (id ~ '^[a-z][a-z0-9_]{1,39}$')
);

insert into public.gender_options (
    id,
    label_pt_br,
    identity_selectable,
    preference_selectable,
    allows_self_description,
    exclusive_selection,
    sort_order
)
values
    ('woman', 'Mulher', true, true, false, false, 10),
    ('man', 'Homem', true, true, false, false, 20),
    ('trans_woman', 'Mulher trans', true, true, false, false, 30),
    ('trans_man', 'Homem trans', true, true, false, false, 40),
    ('non_binary', 'Pessoa não binária', true, true, false, false, 50),
    ('genderqueer', 'Genderqueer', true, true, false, false, 60),
    ('self_described', 'Autodescrição', true, true, true, false, 70),
    ('prefer_not_to_say', 'Prefiro não informar', true, false, false, true, 80),
    ('everyone', 'Todas as pessoas', false, true, false, true, 90);

alter table public.gender_options enable row level security;

create policy gender_options_select_authenticated
on public.gender_options for select
to authenticated
using (true);

revoke all on table public.gender_options from anon, authenticated;
grant select on table public.gender_options to authenticated;

create table private.profile_identities (
    user_id uuid primary key references public.profiles (id) on delete cascade,
    gender_identity_ids text[] not null,
    self_description text,
    gender_visible boolean not null default true,
    catalog_version smallint not null default 1 check (catalog_version > 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table private.profile_preferences (
    user_id uuid primary key references public.profiles (id) on delete cascade,
    looking_for_gender_ids text[] not null,
    cursor_version bigint not null default 1 check (cursor_version > 0),
    catalog_version smallint not null default 1 check (catalog_version > 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

alter table private.profile_identities enable row level security;
alter table private.profile_preferences enable row level security;
revoke all on table private.profile_identities from anon, authenticated;
revoke all on table private.profile_preferences from anon, authenticated;

create trigger profile_identities_touch_updated_at
before update on private.profile_identities
for each row execute function private.touch_updated_at();

create trigger profile_preferences_touch_updated_at
before update on private.profile_preferences
for each row execute function private.touch_updated_at();

create or replace function private.gender_ids_are_valid(
    selected_ids text[],
    selection_kind text
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select selected_ids is not null
       and cardinality(selected_ids) between 1 and 12
       and not exists (
           select 1
           from unnest(selected_ids) selected(id)
           where selected.id is null or btrim(selected.id) = ''
       )
       and cardinality(selected_ids) = (
           select count(distinct selected.id)::integer
           from unnest(selected_ids) selected(id)
       )
       and not exists (
           select 1
           from unnest(selected_ids) selected(id)
           left join public.gender_options option on option.id = selected.id
           where option.id is null
              or not option.active
              or case selection_kind
                    when 'identity' then not option.identity_selectable
                    when 'preference' then not option.preference_selectable
                    else true
                 end
       )
       and not exists (
           select 1
           from unnest(selected_ids) selected(id)
           join public.gender_options option on option.id = selected.id
           where option.exclusive_selection
             and cardinality(selected_ids) <> 1
       );
$$;

create or replace function private.validate_profile_identity()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    if not private.gender_ids_are_valid(new.gender_identity_ids, 'identity') then
        raise exception using errcode = 'P0001', message = 'INVALID_GENDER_IDENTITY';
    end if;

    if 'prefer_not_to_say' = any(new.gender_identity_ids) then
        new.gender_visible := false;
        new.self_description := null;
    elsif 'self_described' = any(new.gender_identity_ids) then
        if new.self_description is null
           or char_length(btrim(new.self_description)) not between 1 and 60 then
            raise exception using errcode = 'P0001', message = 'INVALID_GENDER_SELF_DESCRIPTION';
        end if;
        new.self_description := btrim(new.self_description);
    else
        if new.self_description is not null
           and char_length(btrim(new.self_description)) > 0 then
            raise exception using errcode = 'P0001', message = 'INVALID_GENDER_SELF_DESCRIPTION';
        end if;
        new.self_description := null;
    end if;

    return new;
end;
$$;

create or replace function private.validate_profile_preference()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    if not private.gender_ids_are_valid(new.looking_for_gender_ids, 'preference') then
        raise exception using errcode = 'P0001', message = 'INVALID_DISCOVERY_PREFERENCE';
    end if;
    return new;
end;
$$;

create trigger profile_identities_validate
before insert or update on private.profile_identities
for each row execute function private.validate_profile_identity();

create trigger profile_preferences_validate
before insert or update on private.profile_preferences
for each row execute function private.validate_profile_preference();

create index profile_identities_visible_gender_idx
    on private.profile_identities using gin (gender_identity_ids)
    where gender_visible;

create or replace function private.initialize_profile_gender_defaults()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into private.profile_identities (
        user_id,
        gender_identity_ids,
        gender_visible
    )
    values (new.id, array['prefer_not_to_say']::text[], false)
    on conflict (user_id) do nothing;

    insert into private.profile_preferences (
        user_id,
        looking_for_gender_ids
    )
    values (new.id, array['everyone']::text[])
    on conflict (user_id) do nothing;

    return new;
end;
$$;

create trigger profiles_initialize_gender_defaults
after insert on public.profiles
for each row execute function private.initialize_profile_gender_defaults();

insert into private.profile_identities (
    user_id,
    gender_identity_ids,
    gender_visible
)
select profile.id, array['prefer_not_to_say']::text[], false
from public.profiles profile
on conflict (user_id) do nothing;

insert into private.profile_preferences (
    user_id,
    looking_for_gender_ids
)
select profile.id, array['everyone']::text[]
from public.profiles profile
on conflict (user_id) do nothing;

revoke all on function public.complete_onboarding(
    integer,
    text,
    text,
    text,
    boolean,
    text,
    text
) from public;

drop function public.complete_onboarding(
    integer,
    text,
    text,
    text,
    boolean,
    text,
    text
);

create function public.complete_onboarding(
    birth_year integer,
    display_name text,
    region_code text,
    terms_version text,
    terms_accepted boolean,
    gender_identity_ids text[],
    gender_self_description text,
    gender_visible boolean,
    looking_for_gender_ids text[],
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
declare
    v_user uuid := auth.uid();
    v_existing_birth_year smallint;
    v_existing_status public.account_status;
    v_age integer;
    v_verified boolean;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if terms_accepted is distinct from true
       or terms_version is null
       or char_length(btrim(terms_version)) not between 1 and 40 then
        raise exception using errcode = 'P0001', message = 'TERMS_REQUIRED';
    end if;
    if birth_year is null then
        raise exception using errcode = 'P0001', message = 'INVALID_BIRTH_YEAR';
    end if;

    v_age := extract(year from current_date)::integer - birth_year;
    if v_age < 18 then
        raise exception using errcode = 'P0001', message = 'ADULTS_ONLY';
    end if;
    if v_age > 120 then
        raise exception using errcode = 'P0001', message = 'INVALID_BIRTH_YEAR';
    end if;
    if display_name is null or char_length(btrim(display_name)) not between 1 and 60 then
        raise exception using errcode = 'P0001', message = 'INVALID_DISPLAY_NAME';
    end if;
    if bio is null or char_length(bio) > 500 then
        raise exception using errcode = 'P0001', message = 'INVALID_BIO';
    end if;
    if intent is null or char_length(btrim(intent)) not between 1 and 80 then
        raise exception using errcode = 'P0001', message = 'INVALID_INTENT';
    end if;
    if region_code is null or char_length(btrim(region_code)) not between 2 and 40 then
        raise exception using errcode = 'P0001', message = 'INVALID_REGION';
    end if;
    if not private.gender_ids_are_valid(gender_identity_ids, 'identity') then
        raise exception using errcode = 'P0001', message = 'INVALID_GENDER_IDENTITY';
    end if;
    if not private.gender_ids_are_valid(looking_for_gender_ids, 'preference') then
        raise exception using errcode = 'P0001', message = 'INVALID_DISCOVERY_PREFERENCE';
    end if;

    select account.birth_year, account.status
      into v_existing_birth_year, v_existing_status
      from public.accounts account
     where account.id = v_user
     for update;

    if not found then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_FOUND';
    end if;
    if v_existing_status in ('suspended', 'deleted') then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_EDITABLE';
    end if;
    if v_existing_birth_year is not null and v_existing_birth_year <> birth_year then
        raise exception using errcode = 'P0001', message = 'BIRTH_YEAR_LOCKED';
    end if;

    update public.accounts account
       set status = 'active',
           birth_year = complete_onboarding.birth_year,
           terms_accepted_at = now(),
           terms_version = btrim(complete_onboarding.terms_version)
     where account.id = v_user;

    v_verified := private.account_has_document_verification(v_user);

    insert into public.profiles (
        id,
        display_name,
        age,
        bio,
        intent,
        region_code,
        discovery_visible,
        verified
    )
    values (
        v_user,
        btrim(display_name),
        v_age::smallint,
        btrim(bio),
        btrim(intent),
        btrim(region_code),
        true,
        v_verified
    )
    on conflict (id) do update
       set display_name = excluded.display_name,
           age = excluded.age,
           bio = excluded.bio,
           intent = excluded.intent,
           region_code = excluded.region_code,
           discovery_visible = true,
           verified = excluded.verified;

    insert into private.profile_identities (
        user_id,
        gender_identity_ids,
        self_description,
        gender_visible,
        catalog_version
    )
    values (
        v_user,
        complete_onboarding.gender_identity_ids,
        complete_onboarding.gender_self_description,
        complete_onboarding.gender_visible,
        1
    )
    on conflict (user_id) do update
       set gender_identity_ids = excluded.gender_identity_ids,
           self_description = excluded.self_description,
           gender_visible = excluded.gender_visible,
           catalog_version = excluded.catalog_version;

    insert into private.profile_preferences (
        user_id,
        looking_for_gender_ids,
        cursor_version,
        catalog_version
    )
    values (
        v_user,
        complete_onboarding.looking_for_gender_ids,
        1,
        1
    )
    on conflict (user_id) do update
       set looking_for_gender_ids = excluded.looking_for_gender_ids,
           cursor_version = private.profile_preferences.cursor_version + 1,
           catalog_version = excluded.catalog_version;

    insert into public.audit_events (
        event_type,
        actor_id,
        subject_user_id,
        metadata
    )
    values (
        'account.onboarding_completed',
        v_user,
        v_user,
        jsonb_build_object('terms_version', btrim(terms_version))
    );

    return query select v_user, 'active'::public.account_status, v_age::smallint;
end;
$$;

create or replace function public.get_my_gender_settings()
returns table (
    gender_identity_ids text[],
    gender_self_description text,
    gender_visible boolean,
    looking_for_gender_ids text[],
    preference_cursor_version bigint
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
        identity.gender_identity_ids,
        identity.self_description,
        identity.gender_visible,
        preference.looking_for_gender_ids,
        preference.cursor_version
    from private.profile_identities identity
    join private.profile_preferences preference
      on preference.user_id = identity.user_id
    where identity.user_id = v_user;
end;
$$;

create type public.gender_settings_result as (
    gender_identity_ids text[],
    gender_self_description text,
    gender_visible boolean,
    looking_for_gender_ids text[],
    preference_cursor_version bigint
);

create or replace function public.update_gender_settings(
    gender_identity_ids text[],
    gender_self_description text,
    gender_visible boolean,
    looking_for_gender_ids text[]
)
returns setof public.gender_settings_result
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
    if not private.account_is_active(v_user) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE';
    end if;
    if not private.gender_ids_are_valid(update_gender_settings.gender_identity_ids, 'identity') then
        raise exception using errcode = 'P0001', message = 'INVALID_GENDER_IDENTITY';
    end if;
    if not private.gender_ids_are_valid(update_gender_settings.looking_for_gender_ids, 'preference') then
        raise exception using errcode = 'P0001', message = 'INVALID_DISCOVERY_PREFERENCE';
    end if;

    insert into private.profile_identities (
        user_id,
        gender_identity_ids,
        self_description,
        gender_visible,
        catalog_version
    )
    values (
        v_user,
        update_gender_settings.gender_identity_ids,
        update_gender_settings.gender_self_description,
        update_gender_settings.gender_visible,
        1
    )
    on conflict (user_id) do update
       set gender_identity_ids = excluded.gender_identity_ids,
           self_description = excluded.self_description,
           gender_visible = excluded.gender_visible,
           catalog_version = excluded.catalog_version;

    insert into private.profile_preferences (
        user_id,
        looking_for_gender_ids,
        cursor_version,
        catalog_version
    )
    values (
        v_user,
        update_gender_settings.looking_for_gender_ids,
        1,
        1
    )
    on conflict (user_id) do update
       set looking_for_gender_ids = excluded.looking_for_gender_ids,
           cursor_version = private.profile_preferences.cursor_version + 1,
           catalog_version = excluded.catalog_version;

    insert into public.audit_events (event_type, actor_id, subject_user_id)
    values ('profile.gender_settings_updated', v_user, v_user);

    return query
    select
        identity.gender_identity_ids,
        identity.self_description,
        identity.gender_visible,
        preference.looking_for_gender_ids,
        preference.cursor_version
    from private.profile_identities identity
    join private.profile_preferences preference
      on preference.user_id = identity.user_id
    where identity.user_id = v_user;
end;
$$;

create type public.discovery_profile_result as (
    id uuid,
    display_name text,
    age smallint,
    bio text,
    intent text,
    region_code text,
    verified boolean,
    avatar_path text,
    gender_identity_ids text[],
    gender_self_description text,
    has_more boolean,
    preference_cursor_version bigint
);

create or replace function public.get_discovery_profiles(
    cursor_profile_id uuid default null,
    page_size integer default 20,
    preference_cursor_version bigint default null
)
returns setof public.discovery_profile_result
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_looking_for text[];
    v_cursor_version bigint;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if not private.account_is_active(v_user) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE';
    end if;
    if page_size not between 1 and 50 then
        raise exception using errcode = 'P0001', message = 'INVALID_PAGE_SIZE';
    end if;

    select preference.looking_for_gender_ids, preference.cursor_version
      into v_looking_for, v_cursor_version
      from private.profile_preferences preference
     where preference.user_id = v_user;

    if not found then
        v_looking_for := array['everyone']::text[];
        v_cursor_version := 1;
    end if;

    if cursor_profile_id is not null and preference_cursor_version is null then
        raise exception using errcode = 'P0001', message = 'INVALID_DISCOVERY_CURSOR';
    end if;
    if preference_cursor_version is not null
       and preference_cursor_version <> v_cursor_version then
        raise exception using errcode = 'P0001', message = 'DISCOVERY_CURSOR_STALE';
    end if;

    return query
    with eligible as (
        select
            profile.id,
            profile.display_name,
            profile.age,
            profile.bio,
            profile.intent,
            profile.region_code,
            profile.verified,
            profile.avatar_path,
            case
                when coalesce(identity.gender_visible, false)
                 and not ('prefer_not_to_say' = any(coalesce(
                     identity.gender_identity_ids,
                     array['prefer_not_to_say']::text[]
                 )))
                    then identity.gender_identity_ids
                else array[]::text[]
            end as visible_gender_identity_ids,
            case
                when coalesce(identity.gender_visible, false)
                 and 'self_described' = any(coalesce(
                     identity.gender_identity_ids,
                     array[]::text[]
                 ))
                    then identity.self_description
                else null::text
            end as visible_gender_self_description
        from public.profiles profile
        join public.accounts account on account.id = profile.id
        left join private.profile_identities identity on identity.user_id = profile.id
        where profile.id <> v_user
          and profile.discovery_visible
          and account.status = 'active'
          and account.birth_year <= extract(year from current_date)::integer - 18
          and account.terms_accepted_at is not null
          and account.terms_version is not null
          and char_length(btrim(account.terms_version)) between 1 and 40
          and (cursor_profile_id is null or profile.id > cursor_profile_id)
          and not private.is_blocked_pair(v_user, profile.id)
          and (
              'everyone' = any(v_looking_for)
              or (
                  coalesce(identity.gender_visible, false)
                  and not ('prefer_not_to_say' = any(coalesce(
                      identity.gender_identity_ids,
                      array['prefer_not_to_say']::text[]
                  )))
                  and identity.gender_identity_ids && v_looking_for
              )
          )
        order by profile.id
        limit page_size + 1
    ), page_rows as (
        select *
        from eligible
        order by id
        limit page_size
    )
    select
        page_rows.id,
        page_rows.display_name,
        page_rows.age,
        page_rows.bio,
        page_rows.intent,
        page_rows.region_code,
        page_rows.verified,
        page_rows.avatar_path,
        page_rows.visible_gender_identity_ids,
        page_rows.visible_gender_self_description,
        (select count(*) from eligible) > page_size,
        v_cursor_version
    from page_rows
    order by page_rows.id;
end;
$$;

-- Private albums use server-reserved immutable object paths. Items become
-- available immediately after the object is verified; there is no approval
-- queue. Moderation can still make an item or an album unreadable.
create table private.private_albums (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null unique references public.accounts (id) on delete cascade,
    status text not null default 'active' check (
        status in ('active', 'removed_by_moderation', 'deleting')
    ),
    content_policy_version text not null check (
        char_length(btrim(content_policy_version)) between 1 and 40
    ),
    content_policy_accepted_at timestamptz not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table private.private_album_items (
    id uuid primary key default gen_random_uuid(),
    album_id uuid not null references private.private_albums (id) on delete cascade,
    object_path text not null unique check (char_length(object_path) between 100 and 180),
    mime_type text not null check (mime_type in ('image/jpeg', 'image/png', 'image/webp')),
    position smallint check (position between 0 and 9),
    status text not null default 'uploading' check (
        status in ('uploading', 'available', 'removed_by_moderation', 'deleting')
    ),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint private_album_items_position_unique unique (album_id, position)
);

create table private.private_album_grants (
    album_id uuid not null references private.private_albums (id) on delete cascade,
    recipient_id uuid not null references public.accounts (id) on delete cascade,
    granted_at timestamptz not null default now(),
    revoked_at timestamptz,
    revoked_by uuid references auth.users (id) on delete set null,
    revoke_reason text check (
        revoke_reason is null
        or revoke_reason in ('owner', 'blocked', 'reported', 'album_deleted', 'moderation')
    ),
    primary key (album_id, recipient_id)
);

create table private.private_album_cleanup_queue (
    object_path text primary key,
    requested_at timestamptz not null default now(),
    attempts integer not null default 0 check (attempts >= 0),
    last_attempt_at timestamptz
);

alter table private.private_albums enable row level security;
alter table private.private_album_items enable row level security;
alter table private.private_album_grants enable row level security;
alter table private.private_album_cleanup_queue enable row level security;

revoke all on table private.private_albums from anon, authenticated;
revoke all on table private.private_album_items from anon, authenticated;
revoke all on table private.private_album_grants from anon, authenticated;
revoke all on table private.private_album_cleanup_queue from anon, authenticated;

create trigger private_albums_touch_updated_at
before update on private.private_albums
for each row execute function private.touch_updated_at();

create trigger private_album_items_touch_updated_at
before update on private.private_album_items
for each row execute function private.touch_updated_at();

create index private_album_items_album_status_position_idx
    on private.private_album_items (album_id, status, position);

create index private_album_grants_recipient_active_idx
    on private.private_album_grants (recipient_id, granted_at desc)
    where revoked_at is null;

alter type public.report_reason add value if not exists 'inappropriate_photo';

alter table public.reports
    add column private_album_id uuid references private.private_albums (id) on delete set null,
    add column private_album_item_id uuid references private.private_album_items (id) on delete set null;

insert into storage.buckets (
    id,
    name,
    public,
    file_size_limit,
    allowed_mime_types
)
values (
    'private-albums',
    'private-albums',
    false,
    5242880,
    array['image/jpeg', 'image/png', 'image/webp']::text[]
)
on conflict (id) do update
   set public = excluded.public,
       file_size_limit = excluded.file_size_limit,
       allowed_mime_types = excluded.allowed_mime_types;

create or replace function private.private_album_extension(mime_type text)
returns text
language sql
immutable
set search_path = ''
as $$
    select case mime_type
        when 'image/jpeg' then 'jpg'
        when 'image/png' then 'png'
        when 'image/webp' then 'webp'
        else null
    end;
$$;

create or replace function private.private_album_path_is_valid(
    owner_id uuid,
    album_id uuid,
    item_id uuid,
    mime_type text,
    object_path text
)
returns boolean
language sql
immutable
set search_path = ''
as $$
    select private.private_album_extension(mime_type) is not null
       and object_path = owner_id::text || '/' || album_id::text || '/' ||
           item_id::text || '.' || private.private_album_extension(mime_type);
$$;

create or replace function private.private_album_metadata_is_safe(
    object_path text,
    expected_mime_type text,
    metadata jsonb
)
returns boolean
language sql
immutable
set search_path = ''
as $$
    select metadata is not null
       and lower(coalesce(metadata ->> 'mimetype', '')) = expected_mime_type
       and case
           when coalesce(metadata ->> 'size', '') ~ '^[0-9]{1,10}$'
               then (metadata ->> 'size')::bigint between 1 and 5242880
           else false
       end
       and object_path ~ case expected_mime_type
           when 'image/jpeg' then '\.jpg$'
           when 'image/png' then '\.png$'
           when 'image/webp' then '\.webp$'
           else 'a^'
       end;
$$;

create or replace function private.can_insert_private_album_object(
    object_path text,
    viewer_id uuid,
    metadata jsonb
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select viewer_id is not null
       and private.account_is_active(viewer_id)
       and exists (
           select 1
           from private.private_album_items item
           join private.private_albums album on album.id = item.album_id
           where item.object_path = can_insert_private_album_object.object_path
             and item.status = 'uploading'
             and album.status = 'active'
             and album.owner_id = can_insert_private_album_object.viewer_id
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
       );
$$;

create or replace function private.can_access_private_album(
    album_id uuid,
    viewer_id uuid
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select viewer_id is not null
       and private.account_is_active(viewer_id)
       and exists (
           select 1
           from private.private_albums album
           where album.id = can_access_private_album.album_id
             and album.status = 'active'
             and private.account_is_active(album.owner_id)
             and (
                 album.owner_id = can_access_private_album.viewer_id
                 or (
                     not private.is_blocked_pair(
                         album.owner_id,
                         can_access_private_album.viewer_id
                     )
                     and exists (
                         select 1
                         from private.private_album_grants album_grant
                         where album_grant.album_id = album.id
                           and album_grant.recipient_id = can_access_private_album.viewer_id
                           and album_grant.revoked_at is null
                     )
                 )
             )
       );
$$;

create policy matcher_private_albums_insert
on storage.objects for insert
to authenticated
with check (
    bucket_id = 'private-albums'
    and coalesce(owner_id, owner::text) = (select auth.uid())::text
    and private.can_insert_private_album_object(
        name,
        (select auth.uid()),
        metadata
    )
);

-- Deliberately no SELECT, UPDATE or DELETE policy. Giving authenticated users
-- SELECT would also allow them to mint a signed URL before revocation, while
-- direct DELETE would bypass the metadata-first deletion contract. Reads and
-- physical deletion therefore go through authenticated Edge Functions that
-- reauthorize with the caller JWT before using service_role for Storage only.

create or replace function private.queue_private_album_object_after_item_delete()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    if exists (
        select 1
        from storage.objects object
        where object.bucket_id = 'private-albums'
          and object.name = old.object_path
    ) then
        insert into private.private_album_cleanup_queue (object_path)
        values (old.object_path)
        on conflict on constraint private_album_cleanup_queue_pkey do update
           set requested_at = least(
               private.private_album_cleanup_queue.requested_at,
               excluded.requested_at
           );
    end if;
    return old;
end;
$$;

create trigger private_album_items_queue_object_after_delete
after delete on private.private_album_items
for each row execute function private.queue_private_album_object_after_item_delete();

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

    delete from private.private_album_cleanup_queue queued
     where queued.object_path = old.name;

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

create trigger storage_clear_deleted_private_album_object
after delete on storage.objects
for each row execute function private.clear_deleted_private_album_object();

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

    perform pg_advisory_xact_lock(hashtextextended('private-album:' || v_user::text, 0));

    insert into private.private_albums (
        owner_id,
        content_policy_version,
        content_policy_accepted_at
    )
    values (v_user, btrim(content_policy_version), now())
    on conflict (owner_id) do nothing;

    select album.*
      into v_album
      from private.private_albums album
     where album.owner_id = v_user
     for update;

    if v_album.status <> 'active' then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_NOT_AVAILABLE';
    end if;

    update private.private_albums album
       set content_policy_version = btrim(create_private_album.content_policy_version),
           content_policy_accepted_at = now()
     where album.id = v_album.id;

    return v_album.id;
end;
$$;

create or replace function public.reserve_private_album_item(mime_type text)
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

    select album.*
      into v_album
      from private.private_albums album
     where album.owner_id = v_user
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
        id,
        album_id,
        object_path,
        mime_type,
        position,
        status
    )
    values (
        v_item,
        v_album.id,
        v_path,
        lower(mime_type),
        v_position,
        'uploading'
    );

    return query select v_item, v_path, v_position;
end;
$$;

create or replace function public.finalize_private_album_item(album_item_id uuid)
returns table (
    item_id uuid,
    "position" smallint,
    item_status text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_item private.private_album_items%rowtype;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if not private.account_is_active(v_user) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE';
    end if;

    select item.*
      into v_item
      from private.private_album_items item
      join private.private_albums album on album.id = item.album_id
     where item.id = album_item_id
       and album.owner_id = v_user
       and album.status = 'active'
     for update of item;

    if not found then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_ITEM_NOT_FOUND';
    end if;

    if v_item.status = 'available' then
        return query select v_item.id, v_item.position, v_item.status;
        return;
    end if;
    if v_item.status <> 'uploading' then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_ITEM_NOT_FINALIZABLE';
    end if;

    if not exists (
        select 1
        from storage.objects object
        where object.bucket_id = 'private-albums'
          and object.name = v_item.object_path
          and coalesce(object.owner_id, object.owner::text) = v_user::text
          and private.private_album_metadata_is_safe(
              v_item.object_path,
              v_item.mime_type,
              object.metadata
          )
    ) then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_OBJECT_NOT_FOUND';
    end if;

    update private.private_album_items item
       set status = 'available'
     where item.id = v_item.id
    returning item.id, item.position, item.status
         into v_item.id, v_item.position, v_item.status;

    return query select v_item.id, v_item.position, v_item.status;
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
      and album.status <> 'deleting'
      and item.status <> 'deleting'
    order by item.position nulls last, item.created_at, item.id;
end;
$$;

create or replace function public.get_private_album(album_owner_id uuid)
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
    v_album_id uuid;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;

    select album.id
      into v_album_id
      from private.private_albums album
     where album.owner_id = album_owner_id;

    if v_album_id is null
       or not private.can_access_private_album(v_album_id, v_user) then
        return;
    end if;

    return query
    select item.album_id, item.id, item.position
    from private.private_album_items item
    where item.album_id = v_album_id
      and item.status = 'available'
    order by item.position, item.id;
end;
$$;

create or replace function public.list_private_albums_shared_with_me()
returns table (
    album_id uuid,
    owner_id uuid,
    owner_display_name text,
    item_count integer,
    granted_at timestamptz
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
    if not private.account_is_active(v_user) then
        return;
    end if;

    return query
    select
        album.id,
        album.owner_id,
        profile.display_name,
        count(item.id) filter (where item.status = 'available')::integer,
        album_grant.granted_at
    from private.private_album_grants album_grant
    join private.private_albums album on album.id = album_grant.album_id
    join public.profiles profile on profile.id = album.owner_id
    left join private.private_album_items item on item.album_id = album.id
    where album_grant.recipient_id = v_user
      and album_grant.revoked_at is null
      and private.can_access_private_album(album.id, v_user)
    group by album.id, album.owner_id, profile.display_name, album_grant.granted_at
    order by album_grant.granted_at desc, album.id;
end;
$$;

create or replace function public.authorize_private_album_item(album_item_id uuid)
returns table (
    object_path text,
    mime_type text
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
    select item.object_path, item.mime_type
    from private.private_album_items item
    where item.id = album_item_id
      and item.status = 'available'
      and private.can_access_private_album(item.album_id, v_user);
end;
$$;

create or replace function public.grant_private_album_access(recipient_id uuid)
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
    if not private.account_is_active(v_user) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE';
    end if;
    if not private.account_is_active(recipient_id) then
        raise exception using errcode = 'P0001', message = 'RECIPIENT_NOT_AVAILABLE';
    end if;
    if private.is_blocked_pair(v_user, recipient_id) then
        raise exception using errcode = 'P0001', message = 'ALBUM_ACCESS_BLOCKED';
    end if;

    select album.id
      into v_album
      from private.private_albums album
     where album.owner_id = v_user
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

create or replace function public.revoke_private_album_access(recipient_id uuid)
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
    if recipient_id is null or recipient_id = v_user then
        raise exception using errcode = 'P0001', message = 'INVALID_ALBUM_RECIPIENT';
    end if;

    update private.private_album_grants album_grant
       set revoked_at = now(),
           revoked_by = v_user,
           revoke_reason = 'owner'
      from private.private_albums album
     where album.id = album_grant.album_id
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

create or replace function public.list_private_album_grants()
returns table (
    recipient_id uuid,
    display_name text,
    granted_at timestamptz
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
    select album_grant.recipient_id, profile.display_name, album_grant.granted_at
    from private.private_album_grants album_grant
    join private.private_albums album on album.id = album_grant.album_id
    join public.profiles profile on profile.id = album_grant.recipient_id
    where album.owner_id = v_user
      and album_grant.revoked_at is null
    order by album_grant.granted_at desc, album_grant.recipient_id;
end;
$$;

create or replace function private.revoke_private_album_grants_after_block()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    update private.private_album_grants album_grant
       set revoked_at = now(),
           revoked_by = new.blocker_id,
           revoke_reason = 'blocked'
      from private.private_albums album
     where album.id = album_grant.album_id
       and album_grant.revoked_at is null
       and (
           (album.owner_id = new.blocker_id and album_grant.recipient_id = new.blocked_id)
           or
           (album.owner_id = new.blocked_id and album_grant.recipient_id = new.blocker_id)
       );
    return new;
end;
$$;

create trigger blocks_revoke_private_album_grants
after insert on public.blocks
for each row execute function private.revoke_private_album_grants_after_block();

create or replace function public.report_private_album(
    album_owner_id uuid,
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
    v_album uuid;
    v_report uuid;
    v_case uuid;
begin
    if v_reporter is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if album_owner_id is null or album_owner_id = v_reporter then
        raise exception using errcode = 'P0001', message = 'INVALID_REPORT_TARGET';
    end if;
    if report_details is null or char_length(report_details) > 1000 then
        raise exception using errcode = 'P0001', message = 'INVALID_REPORT_DETAILS';
    end if;

    select album.id
      into v_album
      from private.private_albums album
      join private.private_album_grants album_grant
        on album_grant.album_id = album.id
       and album_grant.recipient_id = v_reporter
       and album_grant.revoked_at is null
     where album.owner_id = album_owner_id
       and album.status = 'active'
       and private.can_access_private_album(album.id, v_reporter)
     for update of album_grant;

    if not found then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_NOT_AVAILABLE';
    end if;
    if album_item_id is not null and not exists (
        select 1
        from private.private_album_items item
        where item.id = album_item_id
          and item.album_id = v_album
          and item.status = 'available'
    ) then
        raise exception using errcode = 'P0001', message = 'INVALID_REPORT_CONTEXT';
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
        album_owner_id,
        report_reason,
        btrim(report_details),
        v_album,
        album_item_id
    )
    returning id into v_report;

    insert into public.moderation_cases (report_id)
    values (v_report)
    returning id into v_case;

    update private.private_album_grants album_grant
       set revoked_at = now(),
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
        album_owner_id,
        v_case,
        jsonb_build_object('reason', report_reason::text)
    );

    return v_case;
end;
$$;

create or replace function public.mark_private_album_item_for_deletion(
    album_item_id uuid
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_path text;
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;

    update private.private_album_items item
       set status = 'deleting',
           position = null
      from private.private_albums album
     where album.id = item.album_id
       and album.owner_id = v_user
       and item.id = album_item_id
       and item.status <> 'deleting'
    returning item.object_path into v_path;

    if v_path is null then
        select item.object_path
          into v_path
          from private.private_album_items item
          join private.private_albums album on album.id = item.album_id
         where item.id = album_item_id
           and album.owner_id = v_user
           and item.status = 'deleting';
    end if;
    if v_path is null then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_ITEM_NOT_FOUND';
    end if;

    if exists (
        select 1 from storage.objects object
        where object.bucket_id = 'private-albums'
          and object.name = v_path
    ) then
        insert into private.private_album_cleanup_queue (object_path)
        values (v_path)
        on conflict on constraint private_album_cleanup_queue_pkey do nothing;
    else
        delete from private.private_album_items item
         where item.id = album_item_id;
    end if;

    return v_path;
end;
$$;

create or replace function public.begin_private_album_deletion()
returns table (object_path text)
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

    select album.id
      into v_album
      from private.private_albums album
     where album.owner_id = v_user
     for update;

    if v_album is null then
        return;
    end if;

    update private.private_albums album
       set status = 'deleting'
     where album.id = v_album;

    update private.private_album_grants album_grant
       set revoked_at = coalesce(album_grant.revoked_at, now()),
           revoked_by = coalesce(album_grant.revoked_by, v_user),
           revoke_reason = coalesce(album_grant.revoke_reason, 'album_deleted')
     where album_grant.album_id = v_album;

    update private.private_album_items item
       set status = 'deleting',
           position = null
     where item.album_id = v_album;

    insert into private.private_album_cleanup_queue (object_path)
    select item.object_path
    from private.private_album_items item
    where item.album_id = v_album
      and exists (
          select 1 from storage.objects object
          where object.bucket_id = 'private-albums'
            and object.name = item.object_path
      )
    on conflict on constraint private_album_cleanup_queue_pkey do nothing;

    delete from private.private_album_items item
     where item.album_id = v_album
       and not exists (
           select 1 from storage.objects object
           where object.bucket_id = 'private-albums'
             and object.name = item.object_path
       );

    if not exists (
        select 1 from private.private_album_items item where item.album_id = v_album
    ) then
        delete from private.private_albums album where album.id = v_album;
        return;
    end if;

    return query
    select item.object_path
    from private.private_album_items item
    where item.album_id = v_album
    order by item.created_at, item.id;
end;
$$;

create or replace function public.finalize_private_album_deletion()
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

    select album.id
      into v_album
      from private.private_albums album
     where album.owner_id = v_user
       and album.status = 'deleting'
     for update;

    if v_album is null then
        return true;
    end if;
    if exists (
        select 1
        from private.private_album_items item
        join storage.objects object
          on object.bucket_id = 'private-albums'
         and object.name = item.object_path
        where item.album_id = v_album
    ) then
        return false;
    end if;

    delete from private.private_album_items item where item.album_id = v_album;
    delete from private.private_albums album where album.id = v_album;
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

    update private.private_album_items item
       set status = 'removed_by_moderation',
           position = null
      from private.private_albums album
     where album.id = item.album_id
       and item.id = album_item_id
    returning album.owner_id, item.object_path into v_owner, v_path;

    if v_path is null then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_ITEM_NOT_FOUND';
    end if;

    insert into private.private_album_cleanup_queue (object_path)
    values (v_path)
    on conflict on constraint private_album_cleanup_queue_pkey do nothing;

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
begin
    if auth.role() <> 'service_role' then
        raise exception using errcode = '42501', message = 'SERVICE_ROLE_REQUIRED';
    end if;
    if decision <> 'removed_by_moderation' then
        raise exception using errcode = 'P0001', message = 'INVALID_PRIVATE_ALBUM_DECISION';
    end if;

    update private.private_albums album
       set status = 'removed_by_moderation'
     where album.id = moderate_private_album.album_id
    returning album.owner_id into v_owner;

    if v_owner is null then
        raise exception using errcode = 'P0001', message = 'PRIVATE_ALBUM_NOT_FOUND';
    end if;

    update private.private_album_grants album_grant
       set revoked_at = coalesce(album_grant.revoked_at, now()),
           revoke_reason = coalesce(album_grant.revoke_reason, 'moderation')
     where album_grant.album_id = moderate_private_album.album_id;

    update private.private_album_items item
       set status = 'removed_by_moderation',
           position = null
     where item.album_id = moderate_private_album.album_id;

    insert into private.private_album_cleanup_queue (object_path)
    select item.object_path
    from private.private_album_items item
    where item.album_id = moderate_private_album.album_id
    on conflict on constraint private_album_cleanup_queue_pkey do nothing;

    insert into public.audit_events (event_type, actor_id, subject_user_id)
    values ('private_album.removed_by_moderation', auth.uid(), v_owner);

    return decision;
end;
$$;

create or replace function public.get_private_album_cleanup_batch(batch_size integer default 100)
returns table (object_path text)
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
        order by queued.requested_at, queued.object_path
        limit batch_size
        for update skip locked
    )
    update private.private_album_cleanup_queue queued
       set attempts = queued.attempts + 1,
           last_attempt_at = now()
      from selected
     where queued.object_path = selected.object_path
    returning queued.object_path;
end;
$$;

create or replace function public.confirm_private_album_object_deleted(object_path text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
    if auth.role() <> 'service_role' then
        raise exception using errcode = '42501', message = 'SERVICE_ROLE_REQUIRED';
    end if;
    if exists (
        select 1 from storage.objects object
        where object.bucket_id = 'private-albums'
          and object.name = confirm_private_album_object_deleted.object_path
    ) then
        return false;
    end if;

    delete from private.private_album_cleanup_queue queued
     where queued.object_path = confirm_private_album_object_deleted.object_path;
    return true;
end;
$$;

revoke all on function public.complete_onboarding(
    integer, text, text, text, boolean, text[], text, boolean, text[], text, text
) from public;
grant execute on function public.complete_onboarding(
    integer, text, text, text, boolean, text[], text, boolean, text[], text, text
) to authenticated;

revoke all on function public.get_my_gender_settings() from public;
revoke all on function public.update_gender_settings(text[], text, boolean, text[]) from public;
revoke all on function public.get_discovery_profiles(uuid, integer, bigint) from public;
grant execute on function public.get_my_gender_settings() to authenticated;
grant execute on function public.update_gender_settings(text[], text, boolean, text[]) to authenticated;
grant execute on function public.get_discovery_profiles(uuid, integer, bigint) to authenticated;

revoke all on function public.create_private_album(text, boolean) from public;
revoke all on function public.reserve_private_album_item(text) from public;
revoke all on function public.finalize_private_album_item(uuid) from public;
revoke all on function public.get_my_private_album() from public;
revoke all on function public.list_my_private_album_items() from public;
revoke all on function public.get_private_album(uuid) from public;
revoke all on function public.list_private_albums_shared_with_me() from public;
revoke all on function public.authorize_private_album_item(uuid) from public;
revoke all on function public.grant_private_album_access(uuid) from public;
revoke all on function public.revoke_private_album_access(uuid) from public;
revoke all on function public.list_private_album_grants() from public;
revoke all on function public.report_private_album(uuid, public.report_reason, text, uuid) from public;
revoke all on function public.mark_private_album_item_for_deletion(uuid) from public;
revoke all on function public.begin_private_album_deletion() from public;
revoke all on function public.finalize_private_album_deletion() from public;

grant execute on function public.create_private_album(text, boolean) to authenticated;
grant execute on function public.reserve_private_album_item(text) to authenticated;
grant execute on function public.finalize_private_album_item(uuid) to authenticated;
grant execute on function public.get_my_private_album() to authenticated;
grant execute on function public.list_my_private_album_items() to authenticated;
grant execute on function public.get_private_album(uuid) to authenticated;
grant execute on function public.list_private_albums_shared_with_me() to authenticated;
grant execute on function public.authorize_private_album_item(uuid) to authenticated;
grant execute on function public.grant_private_album_access(uuid) to authenticated;
grant execute on function public.revoke_private_album_access(uuid) to authenticated;
grant execute on function public.list_private_album_grants() to authenticated;
grant execute on function public.report_private_album(uuid, public.report_reason, text, uuid) to authenticated;
grant execute on function public.mark_private_album_item_for_deletion(uuid) to authenticated;
grant execute on function public.begin_private_album_deletion() to authenticated;
grant execute on function public.finalize_private_album_deletion() to authenticated;

revoke all on function public.moderate_private_album_item(uuid, text) from public;
revoke all on function public.moderate_private_album(uuid, text) from public;
revoke all on function public.get_private_album_cleanup_batch(integer) from public;
revoke all on function public.confirm_private_album_object_deleted(text) from public;
grant execute on function public.moderate_private_album_item(uuid, text) to service_role;
grant execute on function public.moderate_private_album(uuid, text) to service_role;
grant execute on function public.get_private_album_cleanup_batch(integer) to service_role;
grant execute on function public.confirm_private_album_object_deleted(text) to service_role;

revoke all on function private.gender_ids_are_valid(text[], text) from public;
revoke all on function private.private_album_extension(text) from public;
revoke all on function private.private_album_path_is_valid(uuid, uuid, uuid, text, text) from public;
revoke all on function private.private_album_metadata_is_safe(text, text, jsonb) from public;
revoke all on function private.can_insert_private_album_object(text, uuid, jsonb) from public;
revoke all on function private.can_access_private_album(uuid, uuid) from public;

grant execute on function private.can_insert_private_album_object(text, uuid, jsonb) to authenticated;
grant execute on function private.can_access_private_album(uuid, uuid) to authenticated;
