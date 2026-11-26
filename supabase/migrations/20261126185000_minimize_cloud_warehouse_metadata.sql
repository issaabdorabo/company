-- Retain only the warehouse identity needed for permission scoping and chat grouping.
-- Detailed warehouse information stays in the local Room database on each device.
BEGIN;

UPDATE public.warehouses
SET data = jsonb_build_object(
    'id', local_id,
    'name', name
)
WHERE local_id IS NOT NULL
  AND data IS DISTINCT FROM jsonb_build_object(
      'id', local_id,
      'name', name
  );

COMMENT ON COLUMN public.warehouses.data
IS 'Minimal identity metadata only (local id and name). Location, manager, phone, notes and inventory details remain local.';

COMMIT;
