-- Server-authoritative plan catalog. Google Play purchase verification will be
-- added separately; authenticated clients cannot mutate entitlements.

alter table public.entitlements
    drop constraint if exists entitlements_plan_check;

alter table public.entitlements
    add constraint entitlements_plan_check
    check (plan in ('free', 'extra', 'pro', 'unlimited'));

create table public.subscription_plan_catalog (
    plan text primary key check (plan in ('free', 'extra', 'pro', 'unlimited')),
    display_name text not null,
    rank smallint not null unique check (rank between 0 and 3),
    new_conversation_limit integer check (new_conversation_limit between 1 and 1000),
    favorite_limit integer check (favorite_limit between 1 and 100000),
    recent_profile_limit integer check (recent_profile_limit between 0 and 100000),
    advanced_filters boolean not null default false,
    see_favorited_by boolean not null default false,
    hide_activity boolean not null default false,
    hide_read_receipts boolean not null default false,
    incognito boolean not null default false,
    profile_view_history_days smallint not null default 0,
    highlights_per_week smallint not null default 0,
    private_album_count smallint not null default 1,
    private_album_photo_limit smallint not null default 10,
    priority_support boolean not null default false,
    active boolean not null default true,
    constraint unlimited_commercial_quota check (
        (plan = 'unlimited' and new_conversation_limit is null)
        or (plan <> 'unlimited' and new_conversation_limit is not null)
    )
);

insert into public.subscription_plan_catalog (
    plan, display_name, rank, new_conversation_limit, favorite_limit,
    recent_profile_limit, advanced_filters, see_favorited_by, hide_activity,
    hide_read_receipts, incognito, profile_view_history_days,
    highlights_per_week, private_album_count, private_album_photo_limit,
    priority_support
) values
    ('free', 'Free', 0, 5, 20, 0, false, false, false, false, false, 0, 0, 1, 10, false),
    ('extra', 'Extra', 1, 20, 200, 50, true, false, true, false, false, 0, 0, 1, 10, false),
    ('pro', 'Pro', 2, 50, null, 200, true, true, true, true, true, 7, 1, 1, 20, false),
    ('unlimited', 'Ilimitado', 3, null, null, null, true, true, true, true, true, 30, 7, 3, 30, true);

alter table public.subscription_plan_catalog enable row level security;

create policy subscription_plan_catalog_read_active
on public.subscription_plan_catalog for select to authenticated
using (active);

revoke all on public.subscription_plan_catalog from anon, authenticated;
grant select on public.subscription_plan_catalog to authenticated;

-- Unlimited uses a high internal ceiling only for the existing quota function;
-- anti-spam limits continue to apply independently.
create or replace function private.plan_conversation_limit(target_plan text)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
    select coalesce(
        (select c.new_conversation_limit
           from public.subscription_plan_catalog c
          where c.plan = target_plan and c.active),
        case when target_plan = 'unlimited' then 1000 else 5 end
    );
$$;

update public.entitlements e
set new_conversation_limit = private.plan_conversation_limit(e.plan);

revoke all on function private.plan_conversation_limit(text) from public;
grant execute on function private.plan_conversation_limit(text) to service_role;
