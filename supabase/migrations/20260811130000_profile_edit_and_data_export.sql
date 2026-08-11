-- Self-service profile editing and authenticated LGPD portability export.

create or replace function public.update_my_profile(display_name text, bio text, intent text)
returns boolean
language plpgsql security definer set search_path = '' as $$
declare v_user uuid := auth.uid();
begin
    if v_user is null then raise exception using errcode = 'P0001', message = 'AUTH_REQUIRED'; end if;
    if not private.account_is_active(v_user) then raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_ACTIVE'; end if;
    if display_name is null or char_length(btrim(display_name)) not between 2 and 40 then
        raise exception using errcode = 'P0001', message = 'INVALID_DISPLAY_NAME';
    end if;
    if bio is null or char_length(btrim(bio)) > 500 then
        raise exception using errcode = 'P0001', message = 'INVALID_BIO';
    end if;
    if intent is null or char_length(btrim(intent)) not between 1 and 80 then
        raise exception using errcode = 'P0001', message = 'INVALID_INTENT';
    end if;
    update public.profiles profile set display_name = btrim(update_my_profile.display_name),
        bio = btrim(update_my_profile.bio), intent = btrim(update_my_profile.intent), updated_at = now()
     where profile.id = v_user;
    return found;
end;
$$;

create or replace function public.export_my_account_data()
returns jsonb
language sql stable security definer set search_path = '' as $$
    select jsonb_build_object(
        'exported_at', now(),
        'account', (select to_jsonb(account) - 'id' from public.accounts account where account.id = auth.uid()),
        'profile', (select to_jsonb(profile) - 'id' - 'avatar_path' - 'avatar_candidate_path' from public.profiles profile where profile.id = auth.uid()),
        'identity', (select to_jsonb(identity) - 'user_id' from private.profile_identities identity where identity.user_id = auth.uid()),
        'preferences', (select to_jsonb(preference) - 'user_id' from private.profile_preferences preference where preference.user_id = auth.uid()),
        'favorites', coalesce((select jsonb_agg(jsonb_build_object('profile_id', favorite.target_id, 'created_at', favorite.created_at)) from private.profile_favorites favorite where favorite.owner_id = auth.uid()), '[]'::jsonb),
        'hidden_profiles', coalesce((select jsonb_agg(jsonb_build_object('profile_id', hidden.target_id, 'created_at', hidden.created_at)) from private.profile_hides hidden where hidden.owner_id = auth.uid()), '[]'::jsonb),
        'blocked_profiles', coalesce((select jsonb_agg(jsonb_build_object('profile_id', block.blocked_id, 'created_at', block.created_at)) from public.blocks block where block.blocker_id = auth.uid()), '[]'::jsonb),
        'conversations', coalesce((select jsonb_agg(jsonb_build_object(
            'conversation_id', conversation.id, 'status', conversation.status, 'created_at', conversation.created_at,
            'messages', (select coalesce(jsonb_agg(jsonb_build_object('id', message.id, 'sent_by_me', message.sender_id = auth.uid(), 'body', message.body, 'kind', message.kind, 'created_at', message.created_at) order by message.created_at), '[]'::jsonb) from public.messages message where message.conversation_id = conversation.id)
        ) order by conversation.created_at) from public.conversations conversation where auth.uid() in (conversation.participant_a, conversation.participant_b)), '[]'::jsonb),
        'reports_submitted', coalesce((select jsonb_agg(jsonb_build_object('id', report.id, 'reason', report.reason, 'details', report.details, 'created_at', report.created_at)) from public.reports report where report.reporter_id = auth.uid()), '[]'::jsonb)
    );
$$;

revoke all on function public.update_my_profile(text, text, text), public.export_my_account_data() from public, anon;
grant execute on function public.update_my_profile(text, text, text), public.export_my_account_data() to authenticated;
