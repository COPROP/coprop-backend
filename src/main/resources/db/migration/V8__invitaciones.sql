-- V8: invitaciones. El acceso a un condominio nace siempre de una invitacion (Seguridad 6).
--
-- "No hay registro abierto: solo se entra por invitacion." La razon esta en el analisis y no es
-- comodidad: si cualquiera pudiera registrarse y declararse propietario del Dpto. 302, veria
-- deudas ajenas. La invitacion nace de un dato que el administrador ya valido --la persona y su
-- relacion con la unidad-- y es lo que ata una cuenta a un condominio con unos roles.
--
-- La invitacion apunta a una CUENTA que ya existe, no a un correo suelto. Es el orden de 6.1: el
-- administrador registra primero a la persona y su relacion con la unidad --endpoints del #10 y
-- del #11-- y despues invita. Invitar por correo suelto obligaria a crear la persona en la
-- aceptacion, sin nombres ni carnet, que es justo lo que 6.1 evita.
--
-- Las unidades NO viajan en la invitacion, aunque el alcance del issue las nombre. Las relaciones
-- con la unidad viven en el modulo condominium (regla 2 de ARCHITECTURE.md: identity no depende de
-- condominium), y se registran antes con sus propios endpoints. La invitacion lleva lo que es de
-- identidad: a quien y con que roles.

CREATE TABLE invitation (
    id             UUID NOT NULL DEFAULT gen_random_uuid(),
    condominium_id UUID NOT NULL,
    -- La cuenta invitada. Nace en INVITED (V5) y pasa a ACTIVE en su primer login, que es del #13.
    user_id        UUID NOT NULL,
    -- SOLO el hash del token, nunca el token. Lo exige 6.1 y el motivo es que esta base guarda
    -- tambien el registro de eventos: un token en claro aqui seria un acceso reutilizable por
    -- quien pueda leer una fila. Es SHA-256 en hexadecimal, 64 caracteres.
    --
    -- VARCHAR y no CHAR, aunque el largo sea fijo: CHAR rellena con espacios a la derecha, asi que
    -- un valor leido no es igual al que se escribio y la comparacion del hash depende de como
    -- recorte cada capa. Ademas ddl-auto: validate espera VARCHAR para un String y el arranque
    -- falla con CHAR, que es como se descubrio.
    token_hash     VARCHAR(64) NOT NULL,
    -- Siete dias, que es lo que fija 6.1. Se calcula con el Clock inyectado, no con now().
    expires_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    -- De un solo uso: en cuanto se acepta queda sellada y no vuelve a servir.
    accepted_at    TIMESTAMP WITH TIME ZONE,
    -- Revocada por el administrador, o por un reenvio, que revoca la anterior y emite otra.
    revoked_at     TIMESTAMP WITH TIME ZONE,
    -- Quien invita. NOT NULL, al contrario que en membership: una invitacion siempre la emite
    -- alguien que entro al sistema. La membresia admite nulo porque la primera de un condominio
    -- puede nacer sin invitacion, concedida directamente por el superadministrador.
    invited_by     UUID NOT NULL,
    CONSTRAINT invitation_pk PRIMARY KEY (id),
    CONSTRAINT invitation_condominium_fk FOREIGN KEY (condominium_id) REFERENCES condominium (id),
    CONSTRAINT invitation_user_fk FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT invitation_invited_by_fk FOREIGN KEY (invited_by) REFERENCES user_account (id),
    CONSTRAINT invitation_invited_by_ck CHECK (invited_by <> user_id),
    CONSTRAINT invitation_vigencia_ck CHECK (expires_at > created_at),
    -- Aceptada y revocada a la vez no significa nada, y seria el estado desde el que se cuela un
    -- segundo uso: la consulta de "sigue viva" mira las dos columnas y una fila con ambas puestas
    -- dependeria del orden en que se miren.
    CONSTRAINT invitation_cierre_ck CHECK (accepted_at IS NULL OR revoked_at IS NULL)
);

COMMENT ON TABLE invitation IS 'Invitacion a un condominio: de un solo uso, siete dias, y solo el hash del token (Seguridad 6.1).';

-- El token identifica la invitacion y es lo que llega en el enlace, asi que se busca por el hash.
-- UNIQUE y no solo indice: dos invitaciones con el mismo hash harian ambigua esa busqueda, y
-- aunque la probabilidad de un choque de 256 bits sea nula, lo que protege de verdad es contra un
-- error de programacion que reutilice un token.
CREATE UNIQUE INDEX invitation_token_uq ON invitation (token_hash);

-- Una sola invitacion VIVA por cuenta y condominio. Parcial, como membership_current_uq y por lo
-- mismo: para PostgreSQL dos nulos no son iguales, asi que un UNIQUE normal sobre las dos columnas
-- de cierre dejaria acumular invitaciones abiertas y habria varias sirviendo a la vez.
--
-- Deja pasar a proposito las ya cerradas: un reenvio revoca la viva y emite otra, y la historia de
-- las anteriores se queda, que es lo que el #21 querra en la bitacora.
CREATE UNIQUE INDEX invitation_viva_uq
    ON invitation (user_id, condominium_id)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

-- Para listar las de un condominio, que es lo que mira el administrador.
CREATE INDEX invitation_by_condominium_idx ON invitation (condominium_id, expires_at);


-- Los roles que la invitacion concedera al aceptarse. Misma forma que membership_role, porque es
-- lo mismo diferido: la membresia no existe hasta que alguien acepta.
--
-- PLATFORM_ADMIN no entra: su alcance es la plataforma y vive en platform_admin (V7). Concederlo
-- como membresia esta prohibido desde el #16, asi que tampoco se puede invitar a el.
CREATE TABLE invitation_role (
    invitation_id UUID        NOT NULL,
    role          VARCHAR(20) NOT NULL,
    CONSTRAINT invitation_role_pk PRIMARY KEY (invitation_id, role),
    CONSTRAINT invitation_role_invitation_fk
        FOREIGN KEY (invitation_id) REFERENCES invitation (id) ON DELETE CASCADE,
    CONSTRAINT invitation_role_ck CHECK (role IN (
        'CONDO_ADMIN', 'FINANCE_ADMIN', 'OWNER', 'TENANT', 'MAINTENANCE', 'BOARD'))
);

COMMENT ON TABLE invitation_role IS 'Roles que concedera la invitacion al aceptarse. Sin PLATFORM_ADMIN: no es una membresia.';
