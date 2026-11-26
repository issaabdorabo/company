DO $migration$
DECLARE
  v_definition text;
BEGIN
  SELECT pg_get_functiondef('public.seed_cloud_workspace_snapshot(uuid,uuid,jsonb)'::regprocedure)
  INTO v_definition;
  v_definition := replace(
    v_definition,
    '''voucher_item'',''serial''',
    '''voucher_item'',''serial'',''serial_sub_unit'''
  );
  EXECUTE v_definition;

  SELECT pg_get_functiondef('public.push_cloud_entity_snapshot(uuid,uuid,uuid,text,uuid[],text,text,jsonb)'::regprocedure)
  INTO v_definition;
  v_definition := replace(
    v_definition,
    '''voucher_item'',''serial''',
    '''voucher_item'',''serial'',''serial_sub_unit'''
  );
  EXECUTE v_definition;
END;
$migration$;
