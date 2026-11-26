-- Run this once in the Supabase SQL Editor with a database-owner role.
-- Needed for private Realtime Broadcast topic: workspace:<workspace_id>:inventory.
-- This authorizes only authenticated users who are active members of the workspace.

alter table realtime.messages enable row level security;

drop policy if exists "workspace members receive inventory collaboration broadcasts" on realtime.messages;
create policy "workspace members receive inventory collaboration broadcasts"
on realtime.messages
for select
to authenticated
using (
  extension = 'broadcast'
  and exists (
    select 1
    from public.workspace_members wm
    where wm.user_id = (select auth.uid())
      and wm.workspace_id::text = split_part(realtime.topic(), ':', 2)
      and coalesce(wm.is_active, true) = true
  )
);

drop policy if exists "workspace members send inventory collaboration broadcasts" on realtime.messages;
create policy "workspace members send inventory collaboration broadcasts"
on realtime.messages
for insert
to authenticated
with check (
  extension = 'broadcast'
  and exists (
    select 1
    from public.workspace_members wm
    where wm.user_id = (select auth.uid())
      and wm.workspace_id::text = split_part(realtime.topic(), ':', 2)
      and coalesce(wm.is_active, true) = true
  )
);
