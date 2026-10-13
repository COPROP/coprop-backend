-- Semilla de desarrollo: deja la base lista para probar la API sin pelearse con el arranque.
--
-- NO corre sola y NO es una migracion: Flyway no la ve, y por eso no puede acabar en produccion.
-- Se invoca a mano, y se puede repetir cuantas veces haga falta --es idempotente--.
--
--     docker exec -i coprop-postgres psql -U coprop -d coprop < docker/postgres/semilla-de-desarrollo.sql
--
-- Hace falta porque toda la API exige identidad, y la identidad exige una cuenta que alguien dio de
-- alta. El arranque de la plataforma --quien crea la primera cuenta y la primera membresia-- es el
-- issue #16; mientras tanto, esto.
--
-- Deja:
--   * la persona y la cuenta de dev@coprop.local, que es el usuario del realm;
--   * un condominio PROPIO con la cuenta como CONDO_ADMIN y FINANCE_ADMIN;
--   * un condominio AJENO, sin membresia, para ver el aislamiento del issue #17 funcionando.
--
-- Los identificadores son fijos a proposito: la coleccion de Postman los trae puestos.

BEGIN;

-- La persona. ON CONFLICT para poder repetir la semilla sin limpiar nada.
INSERT INTO person (id, first_names, last_names, national_id, created_at)
VALUES ('11111111-1111-4111-8111-111111111111', 'Emma', 'Tola', '8123456', now())
ON CONFLICT (id) DO NOTHING;

-- La cuenta con la que se entra. El correo tiene que ser el MISMO que el del usuario del realm:
-- el primer login vincula por correo, y es lo que hace que entrar con contrasena y entrar con
-- Google caigan en la misma cuenta.
INSERT INTO user_account (id, person_id, email, status, mfa_enabled, created_at)
VALUES ('22222222-2222-4222-8222-222222222222', '11111111-1111-4111-8111-111111111111',
        'dev@coprop.local', 'INVITADO', FALSE, now())
ON CONFLICT (id) DO NOTHING;

-- Suelta el vinculo con el proveedor de identidad.
--
-- Esto es lo que arregla el caso que mas desconcierta en local: Keycloak en start-dev NO persiste su
-- base, asi que al recrear el contenedor dev@coprop.local nace con un "sub" nuevo mientras la cuenta
-- sigue apuntando al viejo. Como una cuenta ya vinculada no se revincula a otro sujeto --la
-- proteccion del issue #13, que impide que una segunda identidad con el mismo correo se quede con la
-- cuenta ajena-- el login responde SIN_CUENTA y parece que la cuenta esta rota. Volver a correr esta
-- semilla la deja lista para el siguiente login.
UPDATE user_account
SET idp_subject = NULL, status = 'INVITADO'
WHERE id = '22222222-2222-4222-8222-222222222222';

-- El condominio propio, donde la cuenta si es miembro.
INSERT INTO condominium (id, name, nit, type, time_zone, currency, status, created_at)
VALUES ('aaaaaaaa-0000-4000-8000-000000000001', 'Torre Sucre (desarrollo)', '1023456789',
        'EDIFICIO', 'America/La_Paz', 'BOB', 'ACTIVO', now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO condominium_config (condominium_id, issue_day, due_day, valid_from)
SELECT 'aaaaaaaa-0000-4000-8000-000000000001', 5, 10, now()
WHERE NOT EXISTS (
    SELECT 1 FROM condominium_config
    WHERE condominium_id = 'aaaaaaaa-0000-4000-8000-000000000001' AND valid_to IS NULL
);

-- El condominio del vecino. Existe, y la cuenta no es miembro: pedirlo tiene que responder 404 igual
-- que uno inventado. Si alguna vez respondiera 403, o devolviera datos, el aislamiento estaria roto.
INSERT INTO condominium (id, name, nit, type, time_zone, currency, status, created_at)
VALUES ('bbbbbbbb-0000-4000-8000-000000000002', 'Condominio del vecino (desarrollo)', '9876543210',
        'CASAS', 'America/La_Paz', 'BOB', 'ACTIVO', now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO condominium_config (condominium_id, issue_day, due_day, valid_from)
SELECT 'bbbbbbbb-0000-4000-8000-000000000002', 5, 10, now()
WHERE NOT EXISTS (
    SELECT 1 FROM condominium_config
    WHERE condominium_id = 'bbbbbbbb-0000-4000-8000-000000000002' AND valid_to IS NULL
);

-- La membresia administrativa en el condominio propio.
--
-- Dos roles en la misma persona esta permitido y es una decision explicita del analisis (10.1): en
-- condominios pequenos, una sola persona administra y maneja el dinero. La concentracion queda en
-- auditoria.
--
-- invited_by va nulo porque a la membresia del primer administrador no la invita nadie. Es justo el
-- hueco que cierra el issue #16.
INSERT INTO membership (id, user_id, condominium_id, status, valid_from, invited_by)
VALUES ('cccccccc-0000-4000-8000-000000000003', '22222222-2222-4222-8222-222222222222',
        'aaaaaaaa-0000-4000-8000-000000000001', 'ACTIVA', now(), NULL)
ON CONFLICT (id) DO NOTHING;

INSERT INTO membership_role (membership_id, role)
VALUES ('cccccccc-0000-4000-8000-000000000003', 'CONDO_ADMIN'),
       ('cccccccc-0000-4000-8000-000000000003', 'FINANCE_ADMIN')
ON CONFLICT (membership_id, role) DO NOTHING;

COMMIT;

\echo ''
\echo 'Semilla aplicada. Para entrar: dev@coprop.local / dev-local-coprop'
\echo ''

SELECT c.name AS condominio,
       c.id,
       COALESCE(string_agg(r.role, ', ' ORDER BY r.role), '(sin membresia: tiene que dar 404)') AS roles
FROM condominium c
LEFT JOIN membership m
       ON m.condominium_id = c.id
      AND m.user_id = '22222222-2222-4222-8222-222222222222'
      AND m.valid_to IS NULL
LEFT JOIN membership_role r ON r.membership_id = m.id
WHERE c.id IN ('aaaaaaaa-0000-4000-8000-000000000001', 'bbbbbbbb-0000-4000-8000-000000000002')
GROUP BY c.name, c.id
ORDER BY c.name;
