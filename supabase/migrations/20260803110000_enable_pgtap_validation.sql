-- Keep linked development validation reproducible. Supabase may normalize
-- extension placement into `extensions`; local runtimes can honor `testing`.
-- The application API exposes neither schema, and the dedicated schema stays
-- inaccessible to runtime roles when the local runtime uses it.
create schema if not exists testing;
revoke all privileges on schema testing from public, anon, authenticated, service_role;

create extension if not exists pgtap with schema testing;

revoke all privileges on schema testing from public, anon, authenticated, service_role;
