-- One-time, time-limited cloud pairing invitations.
-- Only a SHA-256 digest is persisted; invite codes are never stored in plaintext.
create table if not exists public.device_pairing_invites (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  main_device_id uuid not null references public.devices(id) on delete cascade,
  device_slot_id text not null,
  code_hash bytea not null unique,
  expires_at timestamptz not null,
  created_by uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  consumed_by_user_id uuid references auth.users(id) on delete set null,
  consumed_device_fingerprint text,
  consumed_at timestamptz,
  revoked_at timestamptz
);

create index if not exists device_pairing_invites_slot_idx
  on public.device_pairing_invites (workspace_id, main_device_id, device_slot_id, expires_at);

alter table public.device_pairing_invites enable row level security;
revoke all on table public.device_pairing_invites from public, anon, authenticated;

create or replace function public.create_device_pairing_invite(
  p_workspace uuid,
  p_main_device uuid,
  p_slot text,
  p_code text,
  p_ttl_seconds integer default 600
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_code text;
  v_expires_at timestamptz;
begin
  if auth.uid() is null then
    raise exception 'AUTH_REQUIRED';
  end if;

  v_code := pg_catalog.upper(pg_catalog.regexp_replace(coalesce(p_code, ''), '[^A-Za-z0-9]', '', 'g'));
  if v_code !~ '^[A-HJ-NP-Z2-9]{20}$' then
    raise exception 'PAIRING_CODE_FORMAT_INVALID';
  end if;

  if p_ttl_seconds is null or p_ttl_seconds < 60 or p_ttl_seconds > 1800 then
    raise exception 'PAIRING_TTL_OUT_OF_RANGE';
  end if;

  if nullif(pg_catalog.btrim(coalesce(p_slot, '')), '') is null then
    raise exception 'PAIRING_SLOT_REQUIRED';
  end if;

  if not exists (
    select 1
    from public.workspaces w
    join public.devices d on d.id = p_main_device
      and d.workspace_id = w.id
      and d.user_id = auth.uid()
      and d.is_revoked = false
    where w.id = p_workspace
      and w.owner_user_id = auth.uid()
  ) then
    raise exception 'MAIN_DEVICE_NOT_AUTHORIZED';
  end if;

  update public.device_pairing_invites
     set revoked_at = pg_catalog.now()
   where workspace_id = p_workspace
     and main_device_id = p_main_device
     and device_slot_id = p_slot
     and consumed_at is null
     and revoked_at is null;

  v_expires_at := pg_catalog.now() + pg_catalog.make_interval(secs => p_ttl_seconds);

  insert into public.device_pairing_invites (
    workspace_id, main_device_id, device_slot_id, code_hash, expires_at, created_by
  )
  values (
    p_workspace,
    p_main_device,
    p_slot,
    extensions.digest(pg_catalog.convert_to('smart-inventory-pair-v1:' || v_code, 'UTF8'), 'sha256'),
    v_expires_at,
    auth.uid()
  );

  return pg_catalog.jsonb_build_object('expires_at', v_expires_at);
end;
$function$;

create or replace function public.redeem_device_pairing_invite(
  p_code text,
  p_device_name text,
  p_device_fingerprint text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_code text;
  v_hash bytea;
  v_invite public.device_pairing_invites%rowtype;
  v_owner_id uuid;
  v_workspace_name text;
  v_email text;
  v_display_name text;
  v_member_active boolean;
  v_member_role text;
  v_device_id uuid;
begin
  if auth.uid() is null then
    raise exception 'AUTH_REQUIRED';
  end if;

  v_code := pg_catalog.upper(pg_catalog.regexp_replace(coalesce(p_code, ''), '[^A-Za-z0-9]', '', 'g'));
  if v_code !~ '^[A-HJ-NP-Z2-9]{20}$' then
    raise exception 'PAIRING_CODE_FORMAT_INVALID';
  end if;

  if nullif(pg_catalog.btrim(coalesce(p_device_name, '')), '') is null
     or pg_catalog.length(pg_catalog.btrim(p_device_name)) > 100 then
    raise exception 'DEVICE_NAME_INVALID';
  end if;

  if nullif(pg_catalog.btrim(coalesce(p_device_fingerprint, '')), '') is null
     or pg_catalog.length(p_device_fingerprint) > 200 then
    raise exception 'DEVICE_FINGERPRINT_INVALID';
  end if;

  v_hash := extensions.digest(pg_catalog.convert_to('smart-inventory-pair-v1:' || v_code, 'UTF8'), 'sha256');

  select *
    into v_invite
    from public.device_pairing_invites
   where code_hash = v_hash
   for update;

  if not found then
    raise exception 'PAIRING_CODE_INVALID';
  end if;

  if v_invite.revoked_at is not null then
    raise exception 'PAIRING_CODE_REVOKED';
  end if;

  if v_invite.consumed_at is not null then
    if v_invite.consumed_by_user_id <> auth.uid()
       or v_invite.consumed_device_fingerprint <> pg_catalog.btrim(p_device_fingerprint) then
      raise exception 'PAIRING_CODE_ALREADY_USED';
    end if;
    -- Same signed-in account and same installation may resume a previous timeout.
  elsif v_invite.expires_at <= pg_catalog.now() then
    raise exception 'PAIRING_CODE_EXPIRED';
  end if;

  select w.owner_user_id, w.name
    into v_owner_id, v_workspace_name
    from public.workspaces w
    join public.devices d
      on d.id = v_invite.main_device_id
     and d.workspace_id = w.id
     and d.user_id = w.owner_user_id
     and d.is_revoked = false
   where w.id = v_invite.workspace_id
     and w.owner_user_id = v_invite.created_by;

  if v_owner_id is null then
    raise exception 'PAIRING_MAIN_DEVICE_INVALID';
  end if;

  if v_owner_id = auth.uid() then
    raise exception 'PAIRING_SAME_ACCOUNT_NOT_ALLOWED';
  end if;

  select m.is_active, m.role
    into v_member_active, v_member_role
    from public.workspace_members m
   where m.workspace_id = v_invite.workspace_id
     and m.user_id = auth.uid();

  if found and not coalesce(v_member_active, false) then
    raise exception 'WORKSPACE_MEMBERSHIP_REVOKED';
  end if;

  if found and v_member_role = 'OWNER' then
    raise exception 'PAIRING_SAME_ACCOUNT_NOT_ALLOWED';
  end if;

  if not found then
    select lower(coalesce(u.email, '')),
           coalesce(u.raw_user_meta_data ->> 'full_name',
                    u.raw_user_meta_data ->> 'name', '')
      into v_email, v_display_name
      from auth.users u
     where u.id = auth.uid();

    insert into public.workspace_members (
      workspace_id, user_id, role, display_name, email, is_active
    )
    values (
      v_invite.workspace_id, auth.uid(), 'SUBUSER',
      coalesce(v_display_name, ''), coalesce(v_email, ''), true
    );
  end if;

  v_device_id := public.register_cloud_device(
    v_invite.workspace_id,
    pg_catalog.btrim(p_device_name),
    pg_catalog.btrim(p_device_fingerprint)
  );

  if v_invite.consumed_at is null then
    update public.device_pairing_invites
       set consumed_by_user_id = auth.uid(),
           consumed_device_fingerprint = pg_catalog.btrim(p_device_fingerprint),
           consumed_at = pg_catalog.now()
     where id = v_invite.id;
  end if;

  return pg_catalog.jsonb_build_object(
    'workspace_id', v_invite.workspace_id,
    'workspace_name', v_workspace_name,
    'main_device_id', v_invite.main_device_id,
    'device_slot_id', v_invite.device_slot_id,
    'session_token', v_code,
    'device_id', v_device_id,
    'expires_at', v_invite.expires_at
  );
end;
$function$;

create or replace function public.revoke_device_pairing_invite(
  p_workspace uuid,
  p_main_device uuid,
  p_slot text
)
returns integer
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_count integer;
begin
  if auth.uid() is null then
    raise exception 'AUTH_REQUIRED';
  end if;

  if not exists (
    select 1
      from public.workspaces w
      join public.devices d on d.id = p_main_device
       and d.workspace_id = w.id
       and d.user_id = auth.uid()
       and d.is_revoked = false
     where w.id = p_workspace
       and w.owner_user_id = auth.uid()
  ) then
    raise exception 'MAIN_DEVICE_NOT_AUTHORIZED';
  end if;

  update public.device_pairing_invites
     set revoked_at = pg_catalog.now()
   where workspace_id = p_workspace
     and main_device_id = p_main_device
     and device_slot_id = p_slot
     and consumed_at is null
     and revoked_at is null;

  get diagnostics v_count = row_count;
  return v_count;
end;
$function$;

revoke all on function public.create_device_pairing_invite(uuid, uuid, text, text, integer) from public, anon;
revoke all on function public.redeem_device_pairing_invite(text, text, text) from public, anon;
revoke all on function public.revoke_device_pairing_invite(uuid, uuid, text) from public, anon;
grant execute on function public.create_device_pairing_invite(uuid, uuid, text, text, integer) to authenticated;
grant execute on function public.redeem_device_pairing_invite(text, text, text) to authenticated;
grant execute on function public.revoke_device_pairing_invite(uuid, uuid, text) to authenticated;
