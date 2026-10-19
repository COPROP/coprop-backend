-- Semilla de desarrollo: dos condominios con identificadores fijos, para que la coleccion de
-- Postman funcione de entrada.
--
-- NO corre sola y NO es una migracion: Flyway no la ve, y por eso no puede acabar en produccion. Se
-- invoca a mano, y se puede repetir cuantas veces haga falta --es idempotente--.
--
--     docker exec -i coprop-postgres psql -U coprop -d coprop < docker/postgres/semilla-de-desarrollo.sql
--
-- **Ya no hace falta para empezar.** Desde el issue #16, la aplicacion abre sola el primer
-- superadministrador al arrancar --el correo sale de coprop.superadministrador-inicial, que en el
-- perfil local es dev@coprop.local-- y con el se pueden crear condominios y poner su primer
-- administrador desde la API. Esto solo ahorra esos pasos y fija los identificadores.
--
-- Deja:
--   * un condominio OWN, con la cuenta de dev@coprop.local como CONDO_ADMIN y FINANCE_ADMIN;
--   * un condominio AJENO, sin membresia, para ver el aislamiento del issue #17 funcionando.

BEGIN;

-- El condominio propio.
INSERT INTO condominium (id, name, nit, type, time_zone, currency, status, created_at)
VALUES ('aaaaaaaa-0000-4000-8000-000000000001', 'Torre Sucre (desarrollo)', '1023456789',
        'BUILDING', 'America/La_Paz', 'BOB', 'ACTIVE', now())
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
        'HOUSES', 'America/La_Paz', 'BOB', 'ACTIVE', now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO condominium_config (condominium_id, issue_day, due_day, valid_from)
SELECT 'bbbbbbbb-0000-4000-8000-000000000002', 5, 10, now()
WHERE NOT EXISTS (
    SELECT 1 FROM condominium_config
    WHERE condominium_id = 'bbbbbbbb-0000-4000-8000-000000000002' AND valid_to IS NULL
);

-- La membresia administrativa en el condominio propio.
--
-- La cuenta se busca por correo y no por un identificador fijo: la crea el arranque de la
-- aplicacion, con el suyo. Si la base esta limpia y la aplicacion no arranco todavia, aqui no hay
-- cuenta y esta parte no hace nada: se corre la semilla otra vez new_values de levantarla.
--
-- Dos roles en la misma persona esta permitido y es una decision explicita del analisis (10.1): en
-- condominios pequenos, una sola persona administra y maneja el dinero. La concentracion queda en
-- auditoria.
INSERT INTO membership (id, user_id, condominium_id, status, valid_from, invited_by)
SELECT 'cccccccc-0000-4000-8000-000000000003', c.id, 'aaaaaaaa-0000-4000-8000-000000000001',
       'ACTIVE', now(), NULL
FROM user_account c
WHERE lower(c.email) = lower('dev@coprop.local')
ON CONFLICT (id) DO NOTHING;

INSERT INTO membership_role (membership_id, role)
SELECT 'cccccccc-0000-4000-8000-000000000003', rol
FROM (VALUES ('CONDO_ADMIN'), ('FINANCE_ADMIN')) AS roles(rol)
WHERE EXISTS (SELECT 1 FROM membership WHERE id = 'cccccccc-0000-4000-8000-000000000003')
ON CONFLICT (membership_id, role) DO NOTHING;

-- Suelta el vinculo con el proveedor de identidad.
--
-- Arregla el caso que mas desconcierta en local: Keycloak en start-dev NO persiste su base, asi que
-- al recrear el contenedor dev@coprop.local nace con un "sub" nuevo mientras la cuenta sigue
-- apuntando al viejo. Como una cuenta ya vinculada no se revincula a otro sujeto --la proteccion
-- del issue #13-- el login responde SIN_CUENTA y parece que la cuenta esta rota.
UPDATE user_account
SET idp_subject = NULL, status = 'INVITED'
WHERE lower(email) = lower('dev@coprop.local');

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
      AND m.valid_to IS NULL
      AND m.user_id IN (SELECT id FROM user_account WHERE lower(email) = lower('dev@coprop.local'))
LEFT JOIN membership_role r ON r.membership_id = m.id
WHERE c.id IN ('aaaaaaaa-0000-4000-8000-000000000001', 'bbbbbbbb-0000-4000-8000-000000000002')
GROUP BY c.name, c.id
ORDER BY c.name;
