-- Invoke private, idempotent workers without committing their bearer secret.
-- The shared bearer is encrypted in Vault under matcher_worker_shared_secret.

create extension if not exists pg_cron with schema pg_catalog;
create extension if not exists pg_net with schema extensions;

select cron.schedule(
    'matcher-notification-worker',
    '* * * * *',
    $schedule$
    select net.http_post(
        url := 'https://gevdssaambgivxiqilad.supabase.co/functions/v1/notification-worker',
        headers := jsonb_build_object(
            'Content-Type', 'application/json',
            'Authorization', 'Bearer ' || (
                select decrypted_secret
                  from vault.decrypted_secrets
                 where name = 'matcher_worker_shared_secret'
            )
        ),
        body := '{"batch_size":100}'::jsonb,
        timeout_milliseconds := 15000
    ) as request_id;
    $schedule$
);

select cron.schedule(
    'matcher-profile-photo-moderation',
    '* * * * *',
    $schedule$
    select net.http_post(
        url := 'https://gevdssaambgivxiqilad.supabase.co/functions/v1/profile-photo-moderation',
        headers := jsonb_build_object(
            'Content-Type', 'application/json',
            'Authorization', 'Bearer ' || (
                select decrypted_secret
                  from vault.decrypted_secrets
                 where name = 'matcher_worker_shared_secret'
            )
        ),
        body := '{"batch_size":25}'::jsonb,
        timeout_milliseconds := 15000
    ) as request_id;
    $schedule$
);
