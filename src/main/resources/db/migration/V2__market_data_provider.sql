-- Catalogo de activos alimentado por Alpaca.
--
-- Hasta ahora el catalogo lo cargaba un ADMIN a mano. Con el proveedor entra el
-- universo de acciones de EE. UU. (unas 14 mil), y cada fila tiene que decir de
-- donde viene: el precio de un activo de Alpaca no se edita a mano.

-- Los nombres del proveedor llegan a 229 caracteres.
ALTER TABLE public.assets ALTER COLUMN name TYPE character varying(255);

ALTER TABLE public.assets ADD COLUMN source character varying(20) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE public.assets ADD CONSTRAINT assets_source_check
    CHECK (((source)::text = ANY ((ARRAY['MANUAL'::character varying, 'ALPACA'::character varying])::text[])));

ALTER TABLE public.assets ADD COLUMN exchange character varying(20);

-- Ultima vez que se pidio el historico diario al proveedor.
ALTER TABLE public.assets ADD COLUMN history_synced_at timestamp(6) without time zone;

-- El listado pagina los activos activos por simbolo.
CREATE INDEX idx_assets_active_symbol ON public.assets USING btree (active, symbol);
