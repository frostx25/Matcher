-- Operational moderation workflow: SLA, assignment, notes, appeals, templates,
-- metrics and privacy-minimized audit exports.

create table private.moderation_case_operations (
    case_id uuid primary key references public.moderation_cases(id) on delete cascade,
    priority text not null default 'normal' check (priority in ('critical','high','normal','low')),
    due_at timestamptz not null default (now() + interval '24 hours'),
    assigned_to uuid references auth.users(id) on delete set null,
    assigned_at timestamptz,
    second_review_required boolean not null default false,
    first_decision text,
    first_decided_by uuid references auth.users(id) on delete set null,
    first_decided_at timestamptz,
    response_template_key text,
    updated_at timestamptz not null default now()
);

create table private.moderation_case_notes (
    id bigint generated always as identity primary key,
    case_id uuid not null references public.moderation_cases(id) on delete cascade,
    author_id uuid not null references auth.users(id) on delete restrict,
    note text not null check (char_length(btrim(note)) between 3 and 2000),
    created_at timestamptz not null default now()
);

create table private.moderation_response_templates (
    template_key text primary key check (template_key ~ '^[a-z0-9_]{3,60}$'),
    title text not null check (char_length(title) between 3 and 100),
    body text not null check (char_length(body) between 10 and 2000),
    active boolean not null default true,
    updated_at timestamptz not null default now(),
    updated_by uuid references auth.users(id) on delete set null
);

alter table private.moderation_appeals
    add column if not exists review_note text
    check (review_note is null or char_length(review_note) between 3 and 2000);

insert into private.moderation_response_templates(template_key,title,body) values
('report_resolved','Denúncia analisada','Analisamos sua denúncia e aplicamos as medidas previstas nas regras da comunidade.'),
('report_dismissed','Denúncia encerrada','Analisamos a denúncia e não encontramos elementos suficientes para aplicar uma medida neste momento.'),
('appeal_upheld','Recurso aceito','Seu recurso foi revisado por uma segunda pessoa e a medida foi revertida.'),
('appeal_denied','Recurso não aceito','Seu recurso passou por uma segunda revisão e a medida foi mantida.')
on conflict (template_key) do nothing;

alter table private.moderation_case_operations enable row level security;
alter table private.moderation_case_notes enable row level security;
alter table private.moderation_response_templates enable row level security;
revoke all on private.moderation_case_operations, private.moderation_case_notes, private.moderation_response_templates from public,anon,authenticated;

insert into private.moderation_case_operations(case_id,priority,due_at)
select c.id,
       case when count(r2.id)>=3 or r.reason='harassment' then 'critical'
            when r.message_id is not null then 'high' else 'normal' end,
       c.created_at + case when count(r2.id)>=3 or r.reason='harassment' then interval '2 hours'
                           when r.message_id is not null then interval '8 hours' else interval '24 hours' end
from public.moderation_cases c
join public.reports r on r.id=c.report_id
left join public.reports r2 on r2.reported_user_id=r.reported_user_id
group by c.id,r.reason,r.message_id,c.created_at
on conflict (case_id) do nothing;

create or replace function private.initialize_moderation_case_operation()
returns trigger language plpgsql security definer set search_path='' as $$
declare case_priority text := 'normal'; case_due interval := interval '24 hours';
begin
  select case when count(r2.id) >= 3 or r.reason = 'harassment' then 'critical'
              when r.message_id is not null then 'high' else 'normal' end,
         case when count(r2.id) >= 3 or r.reason = 'harassment' then interval '2 hours'
              when r.message_id is not null then interval '8 hours' else interval '24 hours' end
    into case_priority, case_due
    from public.reports r
    left join public.reports r2 on r2.reported_user_id = r.reported_user_id
   where r.id = new.report_id
   group by r.reason, r.message_id;
  insert into private.moderation_case_operations(case_id, priority, due_at)
  values (new.id, coalesce(case_priority, 'normal'), new.created_at + coalesce(case_due, interval '24 hours'))
  on conflict (case_id) do nothing;
  return new;
end $$;

drop trigger if exists initialize_moderation_case_operation on public.moderation_cases;
create trigger initialize_moderation_case_operation
after insert on public.moderation_cases
for each row execute function private.initialize_moderation_case_operation();

create or replace function public.list_moderation_operations(page_size integer default 50, state_filter text default 'open', priority_filter text default 'all', assignment_filter text default 'all')
returns table(case_id uuid,reported_user_id uuid,display_name text,reason public.report_reason,case_state public.moderation_state,created_at timestamptz,priority text,due_at timestamptz,assigned_to uuid,assigned_name text,sla_state text,second_review_required boolean,note_count bigint)
language plpgsql security definer set search_path=''
as $$ begin
 if not private.is_active_moderator(auth.uid()) then raise exception using errcode='42501',message='MODERATOR_REQUIRED'; end if;
 return query
 select c.id,r.reported_user_id,p.display_name,r.reason,c.state,c.created_at,o.priority,o.due_at,o.assigned_to,assignee.display_name,
        case when c.state not in ('pending_review','in_review') then 'closed' when o.due_at<now() then 'overdue' when o.due_at<now()+interval '2 hours' then 'due_soon' else 'on_time' end,
        o.second_review_required,(select count(*) from private.moderation_case_notes n where n.case_id=c.id)
 from public.moderation_cases c join public.reports r on r.id=c.report_id
 join private.moderation_case_operations o on o.case_id=c.id
 left join public.profiles p on p.id=r.reported_user_id left join public.profiles assignee on assignee.id=o.assigned_to
 where (state_filter='all' or (state_filter='open' and c.state in ('pending_review','in_review')) or c.state::text=state_filter)
 and (priority_filter='all' or o.priority=priority_filter)
 and (assignment_filter='all' or (assignment_filter='mine' and o.assigned_to=auth.uid()) or (assignment_filter='unassigned' and o.assigned_to is null))
 order by case o.priority when 'critical' then 0 when 'high' then 1 when 'normal' then 2 else 3 end,o.due_at
 limit greatest(1,least(page_size,100));
end $$;

create or replace function public.get_moderation_case_workspace(target_case_id uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare result jsonb;
begin
 if not private.is_active_moderator(auth.uid()) then raise exception using errcode='42501',message='MODERATOR_REQUIRED'; end if;
 select jsonb_build_object(
  'operation',jsonb_build_object('priority',o.priority,'due_at',o.due_at,'assigned_to',o.assigned_to,'second_review_required',o.second_review_required,'first_decision',o.first_decision,'response_template_key',o.response_template_key),
  'notes',coalesce((select jsonb_agg(jsonb_build_object('id',n.id,'author_id',n.author_id,'author_name',p.display_name,'note',n.note,'created_at',n.created_at) order by n.created_at desc) from private.moderation_case_notes n left join public.profiles p on p.id=n.author_id where n.case_id=target_case_id),'[]'::jsonb),
  'templates',coalesce((select jsonb_agg(jsonb_build_object('key',t.template_key,'title',t.title,'body',t.body) order by t.title) from private.moderation_response_templates t where t.active),'[]'::jsonb)
 ) into result from private.moderation_case_operations o where o.case_id=target_case_id;
 return coalesce(result,'{}'::jsonb);
end $$;

create or replace function public.moderation_workflow_action(action text,target_case_id uuid,priority_value text default null,note_value text default null,template_key_value text default null)
returns text language plpgsql security definer set search_path='' as $$
declare actor uuid:=auth.uid(); current_assignee uuid;
begin
 if not private.is_active_moderator(actor) then raise exception using errcode='42501',message='MODERATOR_REQUIRED'; end if;
 perform private.check_moderation_action_rate(actor);
 if action='assign_self' then
   update private.moderation_case_operations set assigned_to=actor,assigned_at=now(),updated_at=now() where case_id=target_case_id and (assigned_to is null or assigned_to=actor);
 elsif action='release' then
   update private.moderation_case_operations set assigned_to=null,assigned_at=null,updated_at=now() where case_id=target_case_id and assigned_to=actor;
 elsif action='set_priority' then
   if priority_value not in ('critical','high','normal','low') then raise exception using errcode='P0001',message='INVALID_PRIORITY'; end if;
   update private.moderation_case_operations set priority=priority_value,due_at=now()+case priority_value when 'critical' then interval '2 hours' when 'high' then interval '8 hours' when 'normal' then interval '24 hours' else interval '72 hours' end,updated_at=now() where case_id=target_case_id;
 elsif action='add_note' then
   insert into private.moderation_case_notes(case_id,author_id,note) values(target_case_id,actor,btrim(note_value));
 elsif action='require_second_review' then
   update private.moderation_case_operations set second_review_required=true,updated_at=now() where case_id=target_case_id;
 elsif action='select_template' then
   if not exists(select 1 from private.moderation_response_templates where template_key=template_key_value and active) then raise exception using errcode='P0001',message='INVALID_TEMPLATE'; end if;
   update private.moderation_case_operations set response_template_key=template_key_value,updated_at=now() where case_id=target_case_id;
 else raise exception using errcode='P0001',message='INVALID_WORKFLOW_ACTION'; end if;
 if not found then raise exception using errcode='P0001',message='CASE_WORKFLOW_STALE'; end if;
 insert into public.audit_events(event_type,actor_id,moderation_case_id,metadata) values('moderation_console.workflow_'||action,actor,target_case_id,jsonb_build_object('action',action,'priority',priority_value,'template_key',template_key_value));
 return action;
end $$;

create or replace function public.list_moderation_appeals_for_review(page_size integer default 50)
returns table(appeal_id uuid,user_id uuid,sanction_id uuid,statement text,state text,created_at timestamptz,original_reviewer uuid)
language plpgsql security definer set search_path='' as $$ begin
 if not private.is_active_moderator(auth.uid()) then raise exception using errcode='42501',message='MODERATOR_REQUIRED'; end if;
 return query select a.id,a.user_id,a.sanction_id,a.statement,a.state,a.created_at,s.created_by from private.moderation_appeals a join private.account_moderation_sanctions s on s.id=a.sanction_id where a.state='pending' order by a.created_at limit greatest(1,least(page_size,100));
end $$;

create or replace function public.review_moderation_appeal(target_appeal_id uuid,decision text,note_value text)
returns text language plpgsql security definer set search_path='' as $$
declare actor uuid:=auth.uid(); appeal_record record;
begin
 if not private.is_active_moderator(actor) then raise exception using errcode='42501',message='MODERATOR_REQUIRED'; end if;
 if decision not in ('accepted','rejected') or char_length(btrim(note_value)) not between 3 and 2000 then raise exception using errcode='P0001',message='INVALID_APPEAL_DECISION'; end if;
 select a.*,s.created_by into appeal_record from private.moderation_appeals a join private.account_moderation_sanctions s on s.id=a.sanction_id where a.id=target_appeal_id and a.state='pending' for update;
 if appeal_record.id is null then raise exception using errcode='P0001',message='APPEAL_STALE'; end if;
 if appeal_record.created_by=actor then raise exception using errcode='42501',message='SECOND_REVIEWER_REQUIRED'; end if;
 update private.moderation_appeals set state=decision,decided_at=now(),decided_by=actor,review_note=btrim(note_value) where id=target_appeal_id;
 if decision='accepted' then update private.account_moderation_sanctions set active=false,ended_at=now(),ended_by=actor where id=appeal_record.sanction_id and active; update public.accounts set status='active' where id=appeal_record.user_id and status='suspended'; end if;
 insert into public.audit_events(event_type,actor_id,subject_user_id,metadata) values('moderation_console.appeal_'||decision,actor,appeal_record.user_id,jsonb_build_object('appeal_id',target_appeal_id));
 return decision;
end $$;

create or replace function public.get_moderation_metrics(days integer default 30)
returns jsonb language plpgsql security definer set search_path='' as $$ begin
 if not private.is_active_moderator(auth.uid()) then raise exception using errcode='42501',message='MODERATOR_REQUIRED'; end if;
 return jsonb_build_object('window_days',greatest(1,least(days,90)),'open_cases',(select count(*) from public.moderation_cases where state in ('pending_review','in_review')),'overdue_cases',(select count(*) from private.moderation_case_operations o join public.moderation_cases c on c.id=o.case_id where c.state in ('pending_review','in_review') and o.due_at<now()),'resolved',(select count(*) from public.moderation_cases where resolved_at>=now()-make_interval(days=>greatest(1,least(days,90)))),'median_resolution_hours',(select round(percentile_cont(.5) within group(order by extract(epoch from(resolved_at-created_at))/3600)::numeric,1) from public.moderation_cases where resolved_at>=now()-make_interval(days=>greatest(1,least(days,90)))),'appeals_pending',(select count(*) from private.moderation_appeals where state='pending'),'appeals_reversed',(select count(*) from private.moderation_appeals where state='accepted' and decided_at>=now()-make_interval(days=>greatest(1,least(days,90)))));
end $$;

create or replace function public.export_moderation_audit(start_at timestamptz,end_at timestamptz)
returns jsonb language plpgsql security definer set search_path='' as $$ begin
 if not private.is_active_moderation_admin(auth.uid()) then raise exception using errcode='42501',message='ADMIN_REQUIRED'; end if;
 if end_at<=start_at or end_at-start_at>interval '90 days' then raise exception using errcode='P0001',message='INVALID_EXPORT_WINDOW'; end if;
 insert into public.audit_events(event_type,actor_id,metadata) values('moderation_console.audit_exported',auth.uid(),jsonb_build_object('start_at',start_at,'end_at',end_at));
 return coalesce((select jsonb_agg(jsonb_build_object('event_id',e.id,'event_type',e.event_type,'actor_id',e.actor_id,'subject_user_id',e.subject_user_id,'moderation_case_id',e.moderation_case_id,'decision',e.decision,'created_at',e.created_at) order by e.created_at) from public.audit_events e where e.created_at>=start_at and e.created_at<end_at),'[]'::jsonb);
end $$;

revoke all on function public.list_moderation_operations(integer,text,text,text),public.get_moderation_case_workspace(uuid),public.moderation_workflow_action(text,uuid,text,text,text),public.list_moderation_appeals_for_review(integer),public.review_moderation_appeal(uuid,text,text),public.get_moderation_metrics(integer),public.export_moderation_audit(timestamptz,timestamptz) from public,anon;
grant execute on function public.list_moderation_operations(integer,text,text,text),public.get_moderation_case_workspace(uuid),public.moderation_workflow_action(text,uuid,text,text,text),public.list_moderation_appeals_for_review(integer),public.review_moderation_appeal(uuid,text,text),public.get_moderation_metrics(integer),public.export_moderation_audit(timestamptz,timestamptz) to authenticated;
