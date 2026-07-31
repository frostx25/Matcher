alter table public.accounts
    add column birth_year smallint;

update public.accounts
   set birth_year = extract(year from birth_date)::smallint
 where birth_date is not null;

alter table public.accounts
    drop constraint accounts_active_state;

create or replace function private.account_is_active(user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.accounts a
        where a.id = user_id
          and a.status = 'active'
          and a.adult_verified_at is not null
          and a.birth_year <= extract(year from current_date)::integer - 18
    );
$$;

revoke all on function public.complete_onboarding(
    date,
    text,
    text,
    text,
    boolean,
    text,
    text
) from public;

drop function public.complete_onboarding(
    date,
    text,
    text,
    text,
    boolean,
    text,
    text
);

alter table public.accounts
    drop column birth_date;

alter table public.accounts
    add constraint accounts_birth_year_range check (
        birth_year is null or birth_year between 1900 and 9999
    ),
    add constraint accounts_active_state check (
        status <> 'active'
        or (
            birth_year is not null
            and adult_verified_at is not null
            and terms_accepted_at is not null
            and terms_version is not null
            and char_length(btrim(terms_version)) between 1 and 40
        )
    );

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
declare
    v_user uuid := auth.uid();
    v_existing_birth_year smallint;
    v_age integer;
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

    select a.birth_year
      into v_existing_birth_year
      from public.accounts a
     where a.id = v_user
     for update;

    if not found then
        raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_FOUND';
    end if;
    if v_existing_birth_year is not null and v_existing_birth_year <> birth_year then
        raise exception using errcode = 'P0001', message = 'BIRTH_YEAR_LOCKED';
    end if;

    update public.accounts
       set status = 'active',
           birth_year = complete_onboarding.birth_year,
           adult_verified_at = coalesce(adult_verified_at, now()),
           terms_accepted_at = now(),
           terms_version = btrim(complete_onboarding.terms_version)
     where id = v_user;

    insert into public.profiles (
        id,
        display_name,
        age,
        bio,
        intent,
        region_code,
        discovery_visible
    )
    values (
        v_user,
        btrim(display_name),
        v_age::smallint,
        btrim(bio),
        btrim(intent),
        btrim(region_code),
        true
    )
    on conflict (id) do update
       set display_name = excluded.display_name,
           age = excluded.age,
           bio = excluded.bio,
           intent = excluded.intent,
           region_code = excluded.region_code,
           discovery_visible = true;

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

revoke all on function public.complete_onboarding(
    integer,
    text,
    text,
    text,
    boolean,
    text,
    text
) from public;

grant execute on function public.complete_onboarding(
    integer,
    text,
    text,
    text,
    boolean,
    text,
    text
) to authenticated;
