-- Inicio de sesion con Google.
--
-- google_subject es el "sub" del ID token: el identificador estable de la
-- cuenta de Google. El correo puede cambiar; el sub no. Una cuenta de
-- Cash-Out se enlaza a una sola de Google.
--
-- Una cuenta creada con Google no tiene contrasena: Google no la comparte y
-- inventarle una aleatoria haria creer que se puede entrar con ella. Por eso
-- password_hash pasa a admitir NULL, que significa "solo entra con Google".

ALTER TABLE public.users ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE public.users ADD COLUMN google_subject character varying(255);
ALTER TABLE ONLY public.users ADD CONSTRAINT uk_users_google_subject UNIQUE (google_subject);
