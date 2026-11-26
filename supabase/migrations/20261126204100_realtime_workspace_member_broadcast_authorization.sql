-- Restrict private inventory Realtime channels to active members of their workspace.
-- The topic format is workspace:<workspace_uuid>:device:<device_uuid>:pair:<opaque_hash>.
-- Broadcast bodies remain AES-256-GCM encrypted end-to-end by the Android clients.

create policy "active_workspace_members_can_read_inventory_broadcast"
on realtime.messages
for select
to authenticated
using (
    extension = 'broadcast'
    and exists (
        select 1
        from public.workspace_members wm
        where wm.workspace_id::text = split_part(realtime.topic(), ':', 2)
          and wm.user_id = (select auth.uid())
          and wm.is_active = true
    )
);

create policy "active_workspace_members_can_send_inventory_broadcast"
on realtime.messages
for insert
to authenticated
with check (
    extension = 'broadcast'
    and exists (
        select 1
        from public.workspace_members wm
        where wm.workspace_id::text = split_part(realtime.topic(), ':', 2)
          and wm.user_id = (select auth.uid())
          and wm.is_active = true
    )
);
