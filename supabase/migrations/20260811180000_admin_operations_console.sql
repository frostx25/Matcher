-- Operational metrics, prioritized moderation queue and privacy-minimized user dossier.

create or replace function public.get_moderation_console_overview()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_actor uuid := auth.uid();
begin
    if not private.is_active_moderator(v_actor) then
        raise exception using errcode = '42501', message = 'MODERATOR_REQUIRED';
    end if;
    return jsonb_build_object(
        'role', (select staff.staff_role from private.moderation_staff staff where staff.user_id = v_actor),
        'photo_reviews', (select count(*) from private.profile_photo_submissions submission where submission.status = 'pending' and submission.automation_state = 'review'),
        'open_reports', (select count(*) from public.moderation_cases moderation_case where moderation_case.state in ('pending_review', 'in_review')),
        'reported_albums', (select count(distinct marker.moderation_case_id) from private.private_album_report_markers marker join public.moderation_cases moderation_case on moderation_case.id = marker.moderation_case_id where moderation_case.state in ('pending_review', 'in_review')),
        'suspended_accounts', (select count(*) from public.accounts account where account.status = 'suspended'),
        'active_staff', (select count(*) from private.moderation_staff staff where staff.active),
        'oldest_report_at', (select min(moderation_case.created_at) from public.moderation_cases moderation_case where moderation_case.state in ('pending_review', 'in_review')),
        'decisions_24h', (select count(*) from public.audit_events event where event.actor_id is not null and event.created_at >= now() - interval '24 hours' and (event.event_type like 'moderation_console.%' or event.event_type = 'profile_photo.review_decided')),
        'total_active_accounts', (select count(*) from public.accounts account where account.status = 'active'),
        'online_now', (select count(*) from public.profiles profile join public.accounts account on account.id = profile.id where account.status = 'active' and profile.last_active_at >= now() - interval '15 minutes'),
        'active_24h', (select count(*) from public.profiles profile join public.accounts account on account.id = profile.id where account.status = 'active' and profile.last_active_at >= now() - interval '24 hours'),
        'new_accounts_7d', (select count(*) from public.accounts account where account.created_at >= now() - interval '7 days'),
        'messages_24h', (select count(*) from public.messages message where message.created_at >= now() - interval '24 hours' and message.removed_at is null),
        'conversations_24h', (select count(*) from public.conversations conversation where conversation.created_at >= now() - interval '24 hours'),
        'reports_24h', (select count(*) from public.reports report where report.created_at >= now() - interval '24 hours'),
        'blocks_24h', (select count(*) from public.blocks block_record where block_record.created_at >= now() - interval '24 hours'),
        'avg_resolution_hours_30d', (select round(avg(extract(epoch from (moderation_case.resolved_at - moderation_case.created_at)) / 3600)::numeric, 1) from public.moderation_cases moderation_case where moderation_case.resolved_at >= now() - interval '30 days')
    );
end;
$$;

create function public.list_moderation_cases_v2(
    page_size integer default 30,
    state_filter text default 'open',
    reason_filter text default 'all',
    evidence_filter text default 'all',
    priority_filter text default 'all',
    sort_order text default 'priority'
)
returns table (
    case_id uuid,
    report_id uuid,
    reported_user_id uuid,
    display_name text,
    account_status public.account_status,
    reason public.report_reason,
    details text,
    case_state public.moderation_state,
    created_at timestamptz,
    message_id uuid,
    message_body text,
    has_album_evidence boolean,
    repeat_report_count bigint,
    priority text,
    priority_score integer
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_actor uuid := auth.uid();
begin
    if not private.is_active_moderator(v_actor) then raise exception using errcode = '42501', message = 'MODERATOR_REQUIRED'; end if;
    if page_size not between 1 and 50
       or state_filter not in ('open','resolved','dismissed','all')
       or reason_filter not in ('all','spam','harassment','fake_profile','other')
       or evidence_filter not in ('all','album','message','any','none')
       or priority_filter not in ('all','critical','high','normal')
       or sort_order not in ('priority','oldest','newest') then
        raise exception using errcode = 'P0001', message = 'INVALID_MODERATION_FILTER';
    end if;
    return query
    with queue as (
        select moderation_case.id as q_case_id, report.id as q_report_id, report.reported_user_id as q_user_id,
               profile.display_name as q_name, account.status as q_status, report.reason as q_reason,
               report.details as q_details, moderation_case.state as q_state, moderation_case.created_at as q_created,
               report.message_id as q_message_id,
               case when report.message_id is not null then left(message.body,1000) else null end as q_body,
               exists(select 1 from private.private_album_report_markers marker where marker.moderation_case_id=moderation_case.id) as q_album,
               (select count(*) from public.reports repeated where repeated.reported_user_id=report.reported_user_id) as q_repeats
          from public.moderation_cases moderation_case
          join public.reports report on report.id=moderation_case.report_id
          join public.accounts account on account.id=report.reported_user_id
          left join public.profiles profile on profile.id=report.reported_user_id
          left join public.messages message on message.id=report.message_id
         where state_filter='all'
            or (state_filter='open' and moderation_case.state in ('pending_review','in_review'))
            or moderation_case.state::text=state_filter
    ), scored as (
        select queue.*,
               case when q_repeats>=3 or q_album then 'critical'
                    when q_reason='harassment' or q_message_id is not null or q_repeats=2 then 'high'
                    else 'normal' end as q_priority,
               ((least(q_repeats,5)*20) + case when q_album then 45 else 0 end + case when q_message_id is not null then 20 else 0 end + case when q_reason='harassment' then 25 else 0 end + least(floor(extract(epoch from (now()-q_created))/86400)::integer,20))::integer as q_score
          from queue
    )
    select q_case_id,q_report_id,q_user_id,q_name,q_status,q_reason,q_details,q_state,q_created,q_message_id,q_body,q_album,q_repeats,q_priority,q_score
      from scored
     where (reason_filter='all' or q_reason::text=reason_filter)
       and (evidence_filter='all' or (evidence_filter='album' and q_album) or (evidence_filter='message' and q_message_id is not null) or (evidence_filter='any' and (q_album or q_message_id is not null)) or (evidence_filter='none' and not q_album and q_message_id is null))
       and (priority_filter='all' or q_priority=priority_filter)
     order by case when sort_order='priority' then q_score end desc nulls last,
              case when sort_order='oldest' then q_created end asc nulls last,
              case when sort_order='newest' then q_created end desc nulls last,
              q_created asc
     limit page_size;
end;
$$;

create function public.get_moderation_user_detail(target_user_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_actor uuid := auth.uid();
    v_result jsonb;
begin
    if not private.is_active_moderator(v_actor) then raise exception using errcode = '42501', message = 'MODERATOR_REQUIRED'; end if;
    if target_user_id is null then raise exception using errcode = 'P0001', message = 'INVALID_USER'; end if;
    select jsonb_build_object(
        'user_id', account.id,
        'account_status', account.status,
        'created_at', account.created_at,
        'adult_verified_at', account.adult_verified_at,
        'profile', jsonb_build_object('display_name',profile.display_name,'age',profile.age,'bio',profile.bio,'intent',profile.intent,'region_code',profile.region_code,'verified',profile.verified,'discovery_visible',profile.discovery_visible,'has_photo',profile.avatar_path is not null,'last_active_at',profile.last_active_at,'interests',coalesce(profile.interests,array[]::text[])),
        'identity', jsonb_build_object('gender_ids',coalesce(identity.gender_identity_ids,array[]::text[]),'self_description',identity.self_description,'looking_for',coalesce(preference.looking_for_gender_ids,array[]::text[])),
        'risk', jsonb_build_object('reports_received',(select count(*) from public.reports report where report.reported_user_id=account.id),'open_cases',(select count(*) from public.reports report join public.moderation_cases moderation_case on moderation_case.report_id=report.id where report.reported_user_id=account.id and moderation_case.state in ('pending_review','in_review')),'blocks_involving_account',(select count(*) from public.blocks block_record where block_record.blocker_id=account.id or block_record.blocked_id=account.id)),
        'access', jsonb_build_object('active_devices',(select count(*) from private.push_devices device where device.user_id=account.id and device.active),'last_device_seen_at',(select max(device.last_seen_at) from private.push_devices device where device.user_id=account.id),'active_sessions',(select count(*) from auth.sessions session where session.user_id=account.id),'last_session_at',(select max(session.updated_at) from auth.sessions session where session.user_id=account.id)),
        'devices', coalesce((select jsonb_agg(jsonb_build_object('platform',device.platform,'active',device.active,'created_at',device.created_at,'last_seen_at',device.last_seen_at) order by device.last_seen_at desc) from (select * from private.push_devices item where item.user_id=account.id order by item.last_seen_at desc limit 10) device),'[]'::jsonb),
        'sanctions', coalesce((select jsonb_agg(jsonb_build_object('kind',sanction.sanction_kind,'reason',sanction.reason,'active',sanction.active,'expires_at',sanction.expires_at,'created_at',sanction.created_at,'ended_at',sanction.ended_at) order by sanction.created_at desc) from (select * from private.account_moderation_sanctions item where item.user_id=account.id order by item.created_at desc limit 20) sanction),'[]'::jsonb),
        'reports', coalesce((select jsonb_agg(jsonb_build_object('case_id',moderation_case.id,'reason',report.reason,'state',moderation_case.state,'has_message',report.message_id is not null,'created_at',report.created_at,'resolved_at',moderation_case.resolved_at) order by report.created_at desc) from (select * from public.reports item where item.reported_user_id=account.id order by item.created_at desc limit 20) report join public.moderation_cases moderation_case on moderation_case.report_id=report.id),'[]'::jsonb)
    ) into v_result
      from public.accounts account
      left join public.profiles profile on profile.id=account.id
      left join private.profile_identities identity on identity.user_id=account.id
      left join private.profile_preferences preference on preference.user_id=account.id
     where account.id=target_user_id;
    if v_result is null then raise exception using errcode = 'P0001', message = 'USER_NOT_FOUND'; end if;
    return v_result;
end;
$$;

revoke all on function public.list_moderation_cases_v2(integer,text,text,text,text,text) from public,anon;
revoke all on function public.get_moderation_user_detail(uuid) from public,anon;
grant execute on function public.list_moderation_cases_v2(integer,text,text,text,text,text) to authenticated;
grant execute on function public.get_moderation_user_detail(uuid) to authenticated;

comment on function public.get_moderation_user_detail(uuid) is 'Privacy-minimized operational dossier; excludes email, IP, tokens, exact location and device identifiers.';
