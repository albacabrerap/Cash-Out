-- Foto de perfil.
--
-- Los bytes van en su propia tabla: la cuenta se carga en cada peticion
-- autenticada y no tiene por que traer la foto. En users queda solo cuando
-- cambio, que la web usa para saber si hay foto y si la que tiene sigue vigente.

ALTER TABLE public.users ADD COLUMN avatar_updated_at timestamp(6) without time zone;

CREATE TABLE public.user_avatars (
    user_id bigint NOT NULL,
    content_type character varying(20) NOT NULL,
    data bytea NOT NULL,
    CONSTRAINT user_avatars_content_type_check CHECK (((content_type)::text = ANY ((ARRAY['image/jpeg'::character varying, 'image/png'::character varying, 'image/gif'::character varying])::text[])))
);

ALTER TABLE ONLY public.user_avatars ADD CONSTRAINT user_avatars_pkey PRIMARY KEY (user_id);
ALTER TABLE ONLY public.user_avatars
    ADD CONSTRAINT fk_user_avatars_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;
