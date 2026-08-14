-- Reconfigure worker cron endpoints per environment after migrations are applied.
-- Existing environments are unchanged until an operator calls this function.

create or replace function private.configure_worker_schedules(project_url text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    normalized_url text := rtrim(project_url, '/');
    existing_job bigint;
begin
    if current_user not in ('postgres', 'service_role', 'supabase_admin') then
        raise exception using errcode = '42501', message = 'ADMIN_REQUIRED';
    end if;
    if normalized_url !~ '^https://[a-z]{20}\.supabase\.co$' then
        raise exception using errcode = 'P0001', message = 'INVALID_PROJECT_URL';
    end if;

    for existing_job in
        select jobid from cron.job
         where jobname in ('matcher-notification-worker', 'matcher-profile-photo-moderation')
    loop
        perform cron.unschedule(existing_job);
    end loop;

    perform cron.schedule(
        'matcher-notification-worker', '* * * * *',
        format($schedule$
          select net.http_post(
            url := %L,
            headers := jsonb_build_object(
              'Content-Type','application/json',
              'Authorization','Bearer ' || (select decrypted_secret from vault.decrypted_secrets where name='matcher_worker_shared_secret')
            ),
            body := '{"batch_size":100}'::jsonb,
            timeout_milliseconds := 15000
          ) as request_id;
        $schedule$, normalized_url || '/functions/v1/notification-worker')
    );

    perform cron.schedule(
        'matcher-profile-photo-moderation', '* * * * *',
        format($schedule$
          select net.http_post(
            url := %L,
            headers := jsonb_build_object(
              'Content-Type','application/json',
              'Authorization','Bearer ' || (select decrypted_secret from vault.decrypted_secrets where name='matcher_worker_shared_secret')
            ),
            body := '{"batch_size":25}'::jsonb,
            timeout_milliseconds := 15000
          ) as request_id;
        $schedule$, normalized_url || '/functions/v1/profile-photo-moderation')
    );
end;
$$;

revoke all on function private.configure_worker_schedules(text) from public, anon, authenticated;
grant execute on function private.configure_worker_schedules(text) to service_role;
