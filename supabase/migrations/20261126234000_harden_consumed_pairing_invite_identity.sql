-- Keep already-consumed invitations bound to the exact original account and installation,
-- even if the original account row is deleted and its FK becomes NULL.

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
  v_has_member boolean := false;
  v_device_id uuid;
begin
  if auth.uid() is null then raise exception 'AUTH_REQUIRED'; end if;

  v_code := pg_catalog.upper(pg_catalog.regexp_replace(coalesce(p_code, ''), '[^A-Za-z0-9]', '', 'g'));
  if v_code !~ '^[A-HJ-NP-Z2-9]{20}$' then raise exception 'PAIRING_CODE_FORMAT_INVALID'; end if;

  if nullif(pg_catalog.btrim(coalesce(p_device_name, '')), '') is null
     or pg_catalog.length(pg_catalog.btrim(p_device_name)) > 100 then
    raise exception 'DEVICE_NAME_INVALID';
  end if;
  if nullif(pg_catalog.btrim(coalesce(p_device_fingerprint, '')), '') is null
     or pg_catalog.length(p_device_fingerprint) > 200 then
    raise exception 'DEVICE_FINGERPRINT_INVALID';
  end if;

  v_hash := extensions.digest(pg_catalog.convert_to('smart-inventory-pair-v1:' || v_code, 'UTF8'), 'sha256');

  select * into v_invite
    from public.device_pairing_invites
   where code_hash = v_hash
   for update;

  if not found then raise exception 'PAIRING_CODE_INVALID'; end if;
  if v_invite.revoked_at is not null then raise exception 'PAIRING_CODE_REVOKED'; end if;

  if v_invite.consumed_at is not null then
    if v_invite.consumed_by_user_id is distinct from auth.uid()
       or v_invite.consumed_device_fingerprint is distinct from pg_catalog.btrim(p_device_fingerprint) then
      raise exception 'PAIRING_CODE_ALREADY_USED';
    end if;
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

  if v_owner_id is null then raise exception 'PAIRING_MAIN_DEVICE_INVALID'; end if;
  if v_owner_id = auth.uid() then raise exception 'PAIRING_SAME_ACCOUNT_NOT_ALLOWED'; end if;

  select m.is_active, m.role into v_member_active, v_member_role
    from public.workspace_members m
   where m.workspace_id = v_invite.workspace_id
     and m.user_id = auth.uid();
  v_has_member := found;

  if v_has_member and not coalesce(v_member_active, false) then
    raise exception 'WORKSPACE_MEMBERSHIP_REVOKED';
  end if;
  if v_has_member and v_member_role = 'OWNER' then
    raise exception 'PAIRING_SAME_ACCOUNT_NOT_ALLOWED';
  end if;

  if not v_has_member then
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
