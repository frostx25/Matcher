-- Read-only entitlement snapshot and server-side favorite quota enforcement.

create or replace function private.current_plan_catalog(target_user uuid)
returns public.subscription_plan_catalog
language sql stable security definer set search_path = '' as $$
  select catalog
    from public.entitlements entitlement
    join public.subscription_plan_catalog catalog on catalog.plan = entitlement.plan and catalog.active
   where entitlement.user_id = target_user;
$$;

create or replace function public.get_my_subscription_plan()
returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare actor uuid := auth.uid(); catalog public.subscription_plan_catalog;
begin
  if actor is null then raise exception using errcode='P0001', message='AUTH_REQUIRED'; end if;
  select * into catalog from private.current_plan_catalog(actor);
  if catalog.plan is null then raise exception using errcode='P0001', message='ENTITLEMENT_NOT_FOUND'; end if;
  return jsonb_build_object(
    'plan', catalog.plan, 'display_name', catalog.display_name,
    'new_conversation_limit', catalog.new_conversation_limit,
    'favorite_limit', catalog.favorite_limit,
    'recent_profile_limit', catalog.recent_profile_limit,
    'advanced_filters', catalog.advanced_filters,
    'see_favorited_by', catalog.see_favorited_by,
    'hide_activity', catalog.hide_activity,
    'hide_read_receipts', catalog.hide_read_receipts,
    'incognito', catalog.incognito,
    'profile_view_history_days', catalog.profile_view_history_days,
    'highlights_per_week', catalog.highlights_per_week,
    'private_album_count', catalog.private_album_count,
    'private_album_photo_limit', catalog.private_album_photo_limit,
    'priority_support', catalog.priority_support
  );
end $$;

create or replace function public.set_profile_favorite(target_user_id uuid, should_favorite boolean)
returns boolean language plpgsql volatile security definer set search_path = '' as $$
declare actor uuid := auth.uid(); favorite_cap integer;
begin
  if actor is null then raise exception using errcode='P0001', message='AUTH_REQUIRED'; end if;
  if target_user_id is null or target_user_id=actor then raise exception using errcode='P0001', message='INVALID_PROFILE_TARGET'; end if;
  if not private.account_is_active(actor) or not private.account_is_active(target_user_id) or private.is_blocked_pair(actor,target_user_id) then
    raise exception using errcode='P0001', message='PROFILE_NOT_AVAILABLE';
  end if;
  if should_favorite then
    select catalog.favorite_limit into favorite_cap from private.current_plan_catalog(actor) catalog;
    if favorite_cap is not null and not exists(select 1 from private.profile_favorites where owner_id=actor and target_id=target_user_id)
       and (select count(*) from private.profile_favorites where owner_id=actor) >= favorite_cap then
      raise exception using errcode='P0001', message='FAVORITE_LIMIT_REACHED';
    end if;
    delete from private.profile_hides where owner_id=actor and target_id=target_user_id;
    insert into private.profile_favorites(owner_id,target_id) values(actor,target_user_id) on conflict do nothing;
  else
    delete from private.profile_favorites where owner_id=actor and target_id=target_user_id;
  end if;
  return should_favorite;
end $$;

revoke all on function private.current_plan_catalog(uuid) from public,anon,authenticated;
revoke all on function public.get_my_subscription_plan() from public,anon;
grant execute on function public.get_my_subscription_plan() to authenticated;
