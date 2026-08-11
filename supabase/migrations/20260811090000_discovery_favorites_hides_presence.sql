-- Private discovery controls: favorites, reciprocal hides and coarse presence.

alter table public.profiles
    add column if not exists last_active_at timestamptz not null default now(),
    add column if not exists show_activity_status boolean not null default true;

create index if not exists profiles_last_active_idx
    on public.profiles (last_active_at desc)
    where discovery_visible and show_activity_status;

create table if not exists private.profile_favorites (
    owner_id uuid not null references public.accounts (id) on delete cascade,
    target_id uuid not null references public.accounts (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (owner_id, target_id),
    constraint profile_favorite_not_self check (owner_id <> target_id)
);

create index if not exists profile_favorites_target_idx
    on private.profile_favorites (target_id, owner_id);

create table if not exists private.profile_hides (
    owner_id uuid not null references public.accounts (id) on delete cascade,
    target_id uuid not null references public.accounts (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (owner_id, target_id),
    constraint profile_hide_not_self check (owner_id <> target_id)
);

create index if not exists profile_hides_target_idx
    on private.profile_hides (target_id, owner_id);

revoke all on table private.profile_favorites from public, anon, authenticated;
revoke all on table private.profile_hides from public, anon, authenticated;

alter type public.discovery_profile_result
    add attribute is_favorite boolean;

alter type public.discovery_profile_result
    add attribute activity_status text;

create or replace function public.touch_profile_presence()
returns boolean
language plpgsql
volatile
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

    update public.profiles
       set last_active_at = now(), updated_at = now()
     where id = v_user;
    return found;
end;
$$;

create or replace function public.set_profile_favorite(target_user_id uuid, should_favorite boolean)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if target_user_id is null or target_user_id = v_user then
        raise exception using errcode = 'P0001', message = 'INVALID_PROFILE_TARGET';
    end if;
    if not private.account_is_active(v_user) or not private.account_is_active(target_user_id) then
        raise exception using errcode = 'P0001', message = 'PROFILE_NOT_AVAILABLE';
    end if;
    if private.is_blocked_pair(v_user, target_user_id) then
        raise exception using errcode = 'P0001', message = 'PROFILE_NOT_AVAILABLE';
    end if;

    if should_favorite then
        delete from private.profile_hides
         where owner_id = v_user and target_id = target_user_id;
        insert into private.profile_favorites (owner_id, target_id)
        values (v_user, target_user_id)
        on conflict (owner_id, target_id) do nothing;
    else
        delete from private.profile_favorites
         where owner_id = v_user and target_id = target_user_id;
    end if;
    return should_favorite;
end;
$$;

create or replace function public.hide_profile(target_user_id uuid)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    if target_user_id is null or target_user_id = v_user then
        raise exception using errcode = 'P0001', message = 'INVALID_PROFILE_TARGET';
    end if;
    if not private.account_is_active(v_user) then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE';
    end if;

    delete from private.profile_favorites
     where owner_id = v_user and target_id = target_user_id;
    insert into private.profile_hides (owner_id, target_id)
    values (v_user, target_user_id)
    on conflict (owner_id, target_id) do nothing;
    return true;
end;
$$;

create or replace function public.unhide_profile(target_user_id uuid)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    delete from private.profile_hides
     where owner_id = v_user and target_id = target_user_id;
    return found;
end;
$$;

create or replace function public.set_activity_visibility(visible boolean)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then
        raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED';
    end if;
    update public.profiles
       set show_activity_status = visible, updated_at = now()
     where id = v_user;
    if not found then
        raise exception using errcode = 'P0001', message = 'PROFILE_NOT_FOUND';
    end if;
    return visible;
end;
$$;

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
    if v_user is null then raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED'; end if;
    if not private.account_is_active(v_user) then raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE'; end if;
    if page_size not between 1 and 50 then raise exception using errcode = 'P0001', message = 'INVALID_PAGE_SIZE'; end if;

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
    if preference_cursor_version is not null and preference_cursor_version <> v_cursor_version then
        raise exception using errcode = 'P0001', message = 'DISCOVERY_CURSOR_STALE';
    end if;

    return query
    with eligible as (
        select profile.id, profile.display_name, profile.age, profile.bio, profile.intent,
               profile.region_code, profile.verified, profile.avatar_path,
               case when coalesce(identity.gender_visible, false)
                    and not ('prefer_not_to_say' = any(coalesce(identity.gender_identity_ids, array['prefer_not_to_say']::text[])))
                    then identity.gender_identity_ids else array[]::text[] end as visible_gender_identity_ids,
               case when coalesce(identity.gender_visible, false)
                    and 'self_described' = any(coalesce(identity.gender_identity_ids, array[]::text[]))
                    then identity.self_description else null::text end as visible_gender_self_description,
               (favorite.target_id is not null) as is_favorite,
               case
                   when not profile.show_activity_status then null::text
                   when profile.last_active_at >= now() - interval '15 minutes' then 'online'
                   when profile.last_active_at >= now() - interval '1 hour' then 'recent'
                   else null::text
               end as activity_status
          from public.profiles profile
          join public.accounts account on account.id = profile.id
          left join private.profile_identities identity on identity.user_id = profile.id
          left join private.profile_favorites favorite
            on favorite.owner_id = v_user and favorite.target_id = profile.id
         where profile.id <> v_user
           and profile.discovery_visible and account.status = 'active'
           and account.birth_year <= extract(year from current_date)::integer - 18
           and account.terms_accepted_at is not null and account.terms_version is not null
           and char_length(btrim(account.terms_version)) between 1 and 40
           and (cursor_profile_id is null or profile.id > cursor_profile_id)
           and not private.is_blocked_pair(v_user, profile.id)
           and not exists (
               select 1 from private.profile_hides hidden
                where (hidden.owner_id = v_user and hidden.target_id = profile.id)
                   or (hidden.owner_id = profile.id and hidden.target_id = v_user)
           )
           and ('everyone' = any(v_looking_for) or (
               coalesce(identity.gender_visible, false)
               and not ('prefer_not_to_say' = any(coalesce(identity.gender_identity_ids, array['prefer_not_to_say']::text[])))
               and identity.gender_identity_ids && v_looking_for
           ))
         order by profile.id
         limit page_size + 1
    ), page_rows as (
        select * from eligible order by id limit page_size
    )
    select page_rows.id, page_rows.display_name, page_rows.age, page_rows.bio,
           page_rows.intent, page_rows.region_code, page_rows.verified, page_rows.avatar_path,
           page_rows.visible_gender_identity_ids, page_rows.visible_gender_self_description,
           (select count(*) from eligible) > page_size, v_cursor_version,
           page_rows.is_favorite, page_rows.activity_status
      from page_rows order by page_rows.id;
end;
$$;

create or replace function public.get_favorite_profiles(page_size integer default 50)
returns setof public.discovery_profile_result
language sql
stable
security definer
set search_path = ''
as $$
    select profile.id, profile.display_name, profile.age, profile.bio, profile.intent,
           profile.region_code, profile.verified, profile.avatar_path,
           case when coalesce(identity.gender_visible, false)
                then coalesce(identity.gender_identity_ids, array[]::text[]) else array[]::text[] end,
           case when coalesce(identity.gender_visible, false)
                and 'self_described' = any(coalesce(identity.gender_identity_ids, array[]::text[]))
                then identity.self_description else null::text end,
           false, coalesce(preference.cursor_version, 1), true,
           case when not profile.show_activity_status then null::text
                when profile.last_active_at >= now() - interval '15 minutes' then 'online'
                when profile.last_active_at >= now() - interval '1 hour' then 'recent'
                else null::text end
      from private.profile_favorites favorite
      join public.profiles profile on profile.id = favorite.target_id
      join public.accounts account on account.id = profile.id
      left join private.profile_identities identity on identity.user_id = profile.id
      left join private.profile_preferences preference on preference.user_id = favorite.owner_id
     where favorite.owner_id = auth.uid()
       and private.account_is_active(auth.uid())
       and account.status = 'active' and profile.discovery_visible
       and not private.is_blocked_pair(auth.uid(), profile.id)
       and not exists (
           select 1 from private.profile_hides hidden
            where (hidden.owner_id = auth.uid() and hidden.target_id = profile.id)
               or (hidden.owner_id = profile.id and hidden.target_id = auth.uid())
       )
     order by favorite.created_at desc, profile.id
     limit greatest(1, least(page_size, 100));
$$;

revoke all on function public.touch_profile_presence() from public;
revoke all on function public.set_profile_favorite(uuid, boolean) from public;
revoke all on function public.hide_profile(uuid) from public;
revoke all on function public.unhide_profile(uuid) from public;
revoke all on function public.set_activity_visibility(boolean) from public;
revoke all on function public.get_favorite_profiles(integer) from public;

grant execute on function public.touch_profile_presence() to authenticated;
grant execute on function public.set_profile_favorite(uuid, boolean) to authenticated;
grant execute on function public.hide_profile(uuid) to authenticated;
grant execute on function public.unhide_profile(uuid) to authenticated;
grant execute on function public.set_activity_visibility(boolean) to authenticated;
grant execute on function public.get_favorite_profiles(integer) to authenticated;

comment on table private.profile_favorites is 'Private one-way favorites; never exposed to the target profile.';
comment on table private.profile_hides is 'Private reciprocal discovery hides, distinct from safety blocks.';
comment on column public.profiles.last_active_at is 'Server-maintained presence input; clients receive only coarse buckets when allowed.';
