-- New Supabase projects may not grant DML privileges to service_role through
-- default privileges. Edge Functions still need only these narrow operations;
-- RLS remains enabled and service_role is never exposed to the Android client.
grant select, update on table public.accounts to service_role;
grant select on table public.profiles to service_role;
