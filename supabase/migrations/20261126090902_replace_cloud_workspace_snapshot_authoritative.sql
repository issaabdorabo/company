CREATE OR REPLACE FUNCTION public.replace_cloud_workspace_snapshot(
  p_workspace uuid,
  p_device uuid,
  p_records jsonb
)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'AUTH_REQUIRED';
  END IF;
  IF NOT private.is_workspace_owner(p_workspace) THEN
    RAISE EXCEPTION 'OWNER_REQUIRED';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.devices d
    WHERE d.id = p_device
      AND d.workspace_id = p_workspace
      AND d.user_id = auth.uid()
      AND NOT d.is_revoked
  ) THEN
    RAISE EXCEPTION 'DEVICE_NOT_AUTHORIZED';
  END IF;
  IF jsonb_typeof(COALESCE(p_records, '[]'::jsonb)) <> 'array' THEN
    RAISE EXCEPTION 'INVALID_RECORD_LIST';
  END IF;

  DELETE FROM public.cloud_entity_records
  WHERE workspace_id = p_workspace;

  RETURN public.seed_cloud_workspace_snapshot(p_workspace, p_device, p_records);
END;
$function$;

REVOKE ALL ON FUNCTION public.replace_cloud_workspace_snapshot(uuid, uuid, jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.replace_cloud_workspace_snapshot(uuid, uuid, jsonb) TO authenticated;
