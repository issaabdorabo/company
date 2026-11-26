-- Run this once in Supabase SQL Editor with a database-owner role.
-- Realtime topics are private and scoped to one registered installation:
-- workspace:<workspace_id>:device:<device_id>
-- A device may read only its own topic. The OWNER can read/write all device topics.
-- Other active workspace members may publish only to the OWNER's device topic so the
-- main device can receive their operations; they cannot read that topic.

alter table realtime.messages enable row level security;

drop policy if exists "workspace members receive inventory collaboration broadcasts" on realtime.messages;
drop policy if exists "workspace members send inventory collaboration broadcasts" on realtime.messages;
drop policy if exists "device owners and workspace owner receive device broadcasts" on realtime.messages;
drop policy if exists "workspace members send to their device or owner device topics" on realtime.messages;

create policy "device owners and workspace owner receive device broadcasts"
on realtime.messages
for select
to authenticated
using (
  extension = 'broadcast'
  and exists (
    select 1
    from public.workspace_members wm
    join public.devices channel_device
      on channel_device.workspace_id = wm.workspace_id
     and channel_device.id::text = split_part(realtime.topic(), ':', 4)
     and not channel_device.is_revoked
    where wm.user_id = (select auth.uid())
      and wm.workspace_id::text = split_part(realtime.topic(), ':', 2)
      and coalesce(wm.is_active, true) = true
      and (channel_device.user_id = (select auth.uid()) or wm.role = 'OWNER')
  )
);

create policy "workspace members send to their device or owner device topics"
on realtime.messages
for insert
to authenticated
with check (
  extension = 'broadcast'
  and exists (
    select 1
    from public.workspace_members wm
    join public.devices channel_device
      on channel_device.workspace_id = wm.workspace_id
     and channel_device.id::text = split_part(realtime.topic(), ':', 4)
     and not channel_device.is_revoked
    where wm.user_id = (select auth.uid())
      and wm.workspace_id::text = split_part(realtime.topic(), ':', 2)
      and coalesce(wm.is_active, true) = true
      and (
        channel_device.user_id = (select auth.uid())
        or wm.role = 'OWNER'
        or channel_device.user_id = (
          select owner_member.user_id
          from public.workspace_members owner_member
          where owner_member.workspace_id = wm.workspace_id
            and owner_member.role = 'OWNER'
            and owner_member.is_active
          limit 1
        )
      )
  )
);
