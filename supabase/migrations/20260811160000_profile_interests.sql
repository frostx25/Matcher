-- Curated public interests. Free-form labels are intentionally not accepted.

create or replace function private.profile_interests_are_valid(candidate text[])
returns boolean
language sql
immutable
set search_path = ''
as $$
    select candidate is not null
       and cardinality(candidate) <= 8
       and candidate <@ array[
            'amizade','conversa','cinema','música','viagens','games',
            'academia','gastronomia','pets','natureza','arte','tecnologia'
       ]::text[]
       and cardinality(candidate) = (select count(distinct value) from unnest(candidate) value);
$$;

alter table public.profiles
    add column if not exists interests text[] not null default array[]::text[],
    add constraint profiles_interests_valid check (private.profile_interests_are_valid(interests));

create or replace function public.update_my_interests(selected_interests text[])
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
    v_interests text[] := coalesce(selected_interests, array[]::text[]);
begin
    if v_user is null then raise exception 'AUTH_REQUIRED' using errcode = '42501'; end if;
    if not private.account_is_active(v_user) then raise exception 'ACCOUNT_NOT_ACTIVE' using errcode = '42501'; end if;
    if cardinality(v_interests) > 8
       or not v_interests <@ array['amizade','conversa','cinema','música','viagens','games','academia','gastronomia','pets','natureza','arte','tecnologia']::text[]
       or cardinality(v_interests) <> cardinality(array(select distinct unnest(v_interests))) then
        raise exception 'INVALID_INTERESTS' using errcode = '22023';
    end if;
    update public.profiles profile
       set interests = v_interests, updated_at = now()
     where profile.id = v_user;
    return found;
end;
$$;

create or replace function public.get_public_profile_interests(target_ids uuid[])
returns table(profile_id uuid, interests text[])
language sql
stable
security definer
set search_path = ''
as $$
    select profile.id, profile.interests
      from public.profiles profile
      join public.accounts account on account.id = profile.id
     where auth.uid() is not null
       and cardinality(coalesce(target_ids, array[]::uuid[])) between 1 and 100
       and profile.id = any(target_ids)
       and profile.id <> auth.uid()
       and account.status = 'active'
       and profile.discovery_visible
       and not private.is_blocked_pair(auth.uid(), profile.id)
       and not exists (
           select 1 from private.profile_hides hidden
            where (hidden.owner_id = auth.uid() and hidden.target_id = profile.id)
               or (hidden.owner_id = profile.id and hidden.target_id = auth.uid())
       );
$$;

create or replace function public.get_my_interests()
returns text[]
language sql
stable
security definer
set search_path = ''
as $$
    select profile.interests from public.profiles profile where profile.id = auth.uid();
$$;

revoke all on function public.update_my_interests(text[]),
    public.get_public_profile_interests(uuid[]), public.get_my_interests() from public, anon;
revoke all on function private.profile_interests_are_valid(text[]) from public, anon, authenticated;
grant execute on function public.update_my_interests(text[]),
    public.get_public_profile_interests(uuid[]), public.get_my_interests() to authenticated;
