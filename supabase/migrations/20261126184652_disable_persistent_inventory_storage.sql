-- Keep the mobile collaboration backend focused on memberships, warehouse permissions,
-- device presence and chat. Inventory and operation content stay in local Room databases.
-- Existing inventory rows are retained as a protected rollback archive; this migration
-- deliberately does not DELETE or TRUNCATE user data.

BEGIN;

CREATE OR REPLACE FUNCTION public.ensure_cloud_warehouse(
    p_workspace uuid,
    p_local_id bigint,
    p_name text,
    p_data jsonb DEFAULT '{}'::jsonb
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
    v_id uuid;
    v_minimal_data jsonb;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF NOT private.is_workspace_owner(p_workspace) THEN
        RAISE EXCEPTION 'OWNER_REQUIRED';
    END IF;

    IF p_local_id IS NULL OR p_local_id <= 0
       OR NULLIF(TRIM(COALESCE(p_name, '')), '') IS NULL THEN
        RAISE EXCEPTION 'INVALID_WAREHOUSE_DETAILS';
    END IF;

    -- Retain only the identifiers needed for permission mapping and chat grouping.
    -- The prior p_data parameter stays for backward-compatible RPC signatures but
    -- location, manager, contact details, stock and operational data are not persisted.
    v_minimal_data := jsonb_build_object(
        'id', p_local_id,
        'name', TRIM(p_name)
    );

    INSERT INTO public.warehouses(workspace_id, local_id, name, data, updated_at)
    VALUES (p_workspace, p_local_id, TRIM(p_name), v_minimal_data, now())
    ON CONFLICT (workspace_id, local_id) WHERE local_id IS NOT NULL
    DO UPDATE SET
        name = EXCLUDED.name,
        data = EXCLUDED.data,
        updated_at = now()
    RETURNING id INTO v_id;

    -- Intentionally do not write a warehouse entity into cloud_entity_records.
    RETURN v_id;
END;
$function$;

COMMENT ON FUNCTION public.ensure_cloud_warehouse(uuid, bigint, text, jsonb)
IS 'Stores only warehouse id/name for permission mapping and chat grouping; inventory and warehouse details remain local.';

-- Disable RPCs that persist inventory snapshots, stock-operation payloads, audit rows,
-- and delivery receipts. Realtime Broadcast remains the transient operation transport.
REVOKE EXECUTE ON FUNCTION public.push_cloud_entity_snapshot(
    uuid, uuid, uuid, text, uuid[], text, text, jsonb
) FROM PUBLIC, anon, authenticated;

REVOKE EXECUTE ON FUNCTION public.replace_cloud_workspace_snapshot(
    uuid, uuid, jsonb
) FROM PUBLIC, anon, authenticated;

REVOKE EXECUTE ON FUNCTION public.seed_cloud_workspace_snapshot(
    uuid, uuid, jsonb
) FROM PUBLIC, anon, authenticated;

REVOKE EXECUTE ON FUNCTION public.submit_stock_operation(
    uuid, uuid, uuid, text, uuid[], jsonb, text, text
) FROM PUBLIC, anon, authenticated;

REVOKE EXECUTE ON FUNCTION public.acknowledge_cloud_operation_received(
    uuid, uuid
) FROM PUBLIC, anon, authenticated;

-- Keep legacy rows for rollback/audit review but prevent mobile clients from reading
-- or writing the old inventory-sync tables. Service-side administration remains possible.
REVOKE ALL PRIVILEGES ON TABLE
    public.cloud_entity_records,
    public.stock_operations,
    public.operation_audit_logs,
    public.operation_device_receipts
FROM PUBLIC, anon, authenticated;

COMMENT ON TABLE public.cloud_entity_records
IS 'Legacy inventory snapshot archive. Mobile client access is revoked; Room is the inventory source of truth.';

COMMENT ON TABLE public.stock_operations
IS 'Legacy persisted stock-operation archive. New operations are relayed transiently through Realtime Broadcast and kept in Room.';

COMMENT ON TABLE public.operation_audit_logs
IS 'Legacy cloud operation audit archive. Current operation history is kept in the local Room database.';

COMMENT ON TABLE public.operation_device_receipts
IS 'Legacy durable operation-receipt archive. Current delivery ACKs use transient device-scoped Realtime Broadcast.';

COMMIT;
