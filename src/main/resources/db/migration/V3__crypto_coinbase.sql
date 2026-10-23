-- Cripto de Coinbase en el catalogo.
--
-- Los simbolos son pares como BTC-USD: SOL o LTC solos chocarian con acciones
-- que ya usan ese ticker, y el simbolo es UNIQUE.

ALTER TABLE public.assets DROP CONSTRAINT assets_type_check;
ALTER TABLE public.assets ADD CONSTRAINT assets_type_check
    CHECK (((type)::text = ANY ((ARRAY['ETF'::character varying, 'FUND'::character varying, 'STOCK'::character varying, 'CRYPTO'::character varying])::text[])));

ALTER TABLE public.assets DROP CONSTRAINT assets_source_check;
ALTER TABLE public.assets ADD CONSTRAINT assets_source_check
    CHECK (((source)::text = ANY ((ARRAY['MANUAL'::character varying, 'ALPACA'::character varying, 'COINBASE'::character varying])::text[])));
