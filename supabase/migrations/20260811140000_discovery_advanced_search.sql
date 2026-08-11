-- Server-authoritative discovery search. Exact coordinates and exact distance are absent.

create or replace function public.search_discovery_profiles(
    search_text text default null,
    minimum_age integer default 18,
    maximum_age integer default 99,
    verified_only boolean default false,
    has_photo_only boolean default false,
    page_size integer default 50
)
returns setof public.discovery_profile_result
language plpgsql stable security definer set search_path = '' as $$
declare v_user uuid := auth.uid(); v_looking_for text[]; v_cursor_version bigint; v_query text := lower(btrim(coalesce(search_text,'')));
begin
    if v_user is null then raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED'; end if;
    if not private.account_is_active(v_user) then raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE'; end if;
    if minimum_age not between 18 and 99 or maximum_age not between minimum_age and 99 or page_size not between 1 and 50 then
        raise exception using errcode = 'P0001', message = 'INVALID_DISCOVERY_FILTER';
    end if;
    select preference.looking_for_gender_ids, preference.cursor_version into v_looking_for, v_cursor_version
      from private.profile_preferences preference where preference.user_id = v_user;
    if not found then v_looking_for := array['everyone']::text[]; v_cursor_version := 1; end if;
    return query
    select profile.id, profile.display_name, profile.age, profile.bio, profile.intent,
           profile.region_code, profile.verified, profile.avatar_path,
           case when coalesce(identity.gender_visible,false) then coalesce(identity.gender_identity_ids,array[]::text[]) else array[]::text[] end,
           case when coalesce(identity.gender_visible,false) and 'self_described'=any(coalesce(identity.gender_identity_ids,array[]::text[])) then identity.self_description else null::text end,
           false, v_cursor_version, favorite.target_id is not null,
           case when not profile.show_activity_status then null::text when profile.last_active_at >= now()-interval '15 minutes' then 'online' when profile.last_active_at >= now()-interval '1 hour' then 'recent' else null::text end
      from public.profiles profile
      join public.accounts account on account.id=profile.id
      left join private.profile_identities identity on identity.user_id=profile.id
      left join private.profile_favorites favorite on favorite.owner_id=v_user and favorite.target_id=profile.id
     where profile.id<>v_user and profile.discovery_visible and account.status='active'
       and profile.age between minimum_age and maximum_age
       and (not verified_only or profile.verified)
       and (not has_photo_only or profile.avatar_path is not null)
       and (v_query='' or lower(profile.display_name) like '%'||v_query||'%' or lower(profile.intent) like '%'||v_query||'%')
       and not private.is_blocked_pair(v_user,profile.id)
       and not exists(select 1 from private.profile_hides hidden where (hidden.owner_id=v_user and hidden.target_id=profile.id) or (hidden.owner_id=profile.id and hidden.target_id=v_user))
       and ('everyone'=any(v_looking_for) or (coalesce(identity.gender_visible,false) and identity.gender_identity_ids && v_looking_for))
     order by profile.last_active_at desc, profile.id limit page_size;
end;
$$;

revoke all on function public.search_discovery_profiles(text,integer,integer,boolean,boolean,integer) from public, anon;
grant execute on function public.search_discovery_profiles(text,integer,integer,boolean,boolean,integer) to authenticated;
