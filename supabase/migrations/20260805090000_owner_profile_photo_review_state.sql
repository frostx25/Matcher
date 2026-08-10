-- The owner needs a clear status for a private candidate that needs human
-- review. This state is intentionally available only through the owner RPC.

drop function if exists public.get_my_profile_photo_state();

create function public.get_my_profile_photo_state()
returns table (
    candidate_path text,
    moderation_status public.profile_photo_moderation_status,
    automation_state text,
    approved_path text
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
        submission.candidate_path,
        coalesce(
            submission.status,
            'none'::public.profile_photo_moderation_status
        ),
        coalesce(submission.automation_state, 'completed'),
        profile.avatar_path
    from public.profiles profile
    left join private.profile_photo_submissions submission
      on submission.user_id = profile.id
    where profile.id = v_user;
end;
$$;

revoke all on function public.get_my_profile_photo_state() from public;
grant execute on function public.get_my_profile_photo_state() to authenticated;
