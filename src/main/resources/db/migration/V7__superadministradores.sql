-- V7: quien administra la plataforma, que no es lo mismo que administrar un condominio.
--
-- PLATFORM_ADMIN es el unico rol con alcance de plataforma (Seguridad 3): no depende de ningun
-- condominio, asi que no cabe en membership, que existe justamente para atar una cuenta a uno.
--
-- Seguridad 4.2 lo deja abierto --"puede ser un rol de realm, porque no depende de un condominio"--
-- y se eligio la tabla, no el realm, por tres razones:
--
--   1. Los roles se resuelven contra la base en CADA peticion, que es lo que hace que quitar uno
--      surta efecto de inmediato. Un rol en el token sigue valiendo hasta que el token expire, y
--      el superadministrador es justo el que no conviene tardar en revocar.
--   2. El token no lleva roles a proposito (4.2), y meter uno obligaria a devolverle el scope
--      "roles" que el issue #12 le quito.
--   3. El realm se reconstruye desde un archivo versionado. Otorgar el rol a alguien nuevo
--      exigiria editar ese archivo o llamar a la Admin API; aqui es un INSERT.
--
-- El primero no se siembra a mano: lo crea la aplicacion al arrancar si no hay ninguno y hay un
-- correo configurado en coprop.superadministrador-inicial, igual que Keycloak con su
-- KC_BOOTSTRAP_ADMIN.

CREATE TABLE platform_admin (
    user_id    UUID NOT NULL,
    granted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Quien lo concedio. Nulo solo en el primero, que no lo concede nadie: lo abre el arranque.
    -- Es la misma forma que invited_by en membership, y por la misma razon.
    granted_by UUID,
    CONSTRAINT platform_admin_pk PRIMARY KEY (user_id),
    CONSTRAINT platform_admin_user_fk FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT platform_admin_granted_by_fk FOREIGN KEY (granted_by) REFERENCES user_account (id),
    -- Nadie se concede a si mismo el rol. El primero lo abre el arranque, con granted_by nulo.
    CONSTRAINT platform_admin_granted_by_ck CHECK (granted_by IS NULL OR granted_by <> user_id)
);

COMMENT ON TABLE platform_admin IS 'Cuentas con alcance de plataforma (Seguridad 3). No es una membresia: no cuelga de ningun condominio.';
