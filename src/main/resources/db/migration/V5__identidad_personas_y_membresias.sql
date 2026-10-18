-- V5: personas, cuentas, membresias por condominio y las relaciones de negocio con la unidad.
--
-- Seguridad seccion 2 y RF-03, issue #11. El resumen del issue describia un modelo mas pobre que
-- el analisis (una sola entidad con el correo encima, un rol por membresia y una sola relacion
-- persona-unidad con columna de tipo); manda el analisis. Las nueve decisiones que lo zanjaron
-- estan en la seccion 13.2.
--
-- Reparto del resto de la seccion 2: Invitation es el issue #18, UserSession el #22, el catalogo
-- Role y la matriz de permisos el #16, y la resolucion de identidad contra Keycloak el #13.


-- La persona fisica, separada de la cuenta con la que entra. La cuenta es una credencial que se
-- bloquea, se desactiva o todavia no existe; la persona es un hecho del mundo y no deja de serlo
-- porque nunca se registre. Esa separacion es lo que permite cargar el edificio entero antes de
-- invitar a nadie: el propietario existe como person con su unit_ownership desde el primer dia.
--
-- NO lleva condominium_id, y no es un olvido: RF-03 pide que la misma persona sea propietaria en
-- un condominio e inquilina en otro, asi que la fila se comparte. Lo que esta acotado por
-- condominio son la membresia y las relaciones de negocio, mas abajo.
CREATE TABLE person (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    first_names VARCHAR(120) NOT NULL,
    last_names  VARCHAR(120) NOT NULL,
    -- Cedula de identidad. Opcional porque de un copropietario heredado no siempre se tiene, y
    -- exigirla bloquearia el alta masiva del edificio, que es justo para lo que sirve.
    national_id VARCHAR(20),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT person_pk PRIMARY KEY (id),
    CONSTRAINT person_first_names_ck CHECK (length(trim(first_names)) > 0),
    CONSTRAINT person_last_names_ck CHECK (length(trim(last_names)) > 0)
);

COMMENT ON TABLE person IS 'La persona fisica, global y sin condominio (Seguridad 2, RF-03).';

-- Unica cuando viene. Indice parcial y no UNIQUE normal: PostgreSQL no considera iguales dos
-- nulos, asi que un UNIQUE sobre una columna opcional no impide nada, pero tampoco hace falta
-- que lo impida -- lo que hay que evitar son dos personas con el MISMO carnet.
CREATE UNIQUE INDEX person_national_id_uq
    ON person (national_id)
    WHERE national_id IS NOT NULL;


-- La cuenta con la que se entra a la plataforma. Global y sin condominio (Seguridad 2).
--
-- No guarda contraseña: eso es del proveedor de identidad. Lo que guarda es el puente hacia el,
-- idp_subject, que es el "sub" del token.
CREATE TABLE user_account (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    person_id      UUID         NOT NULL,
    -- Nulo mientras la persona esta invitada y no ha entrado nunca: el "sub" lo asigna Keycloak
    -- en el primer login, y hasta entonces no existe. Lo rellena el issue #13.
    idp_subject    VARCHAR(64),
    email          VARCHAR(160) NOT NULL,
    phone          VARCHAR(32),
    status         VARCHAR(20)  NOT NULL,
    mfa_enabled    BOOLEAN      NOT NULL DEFAULT FALSE,
    last_access_at TIMESTAMP WITH TIME ZONE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT user_account_pk PRIMARY KEY (id),
    CONSTRAINT user_account_person_fk FOREIGN KEY (person_id) REFERENCES person (id),
    -- "Una persona tiene una sola cuenta en la plataforma", que es el titulo del issue #11 y la
    -- relacion ||--|| del diagrama de la seccion 2. person_id es NOT NULL, asi que un UNIQUE
    -- normal basta: aqui no hay nulos que PostgreSQL pueda considerar distintos.
    CONSTRAINT user_account_person_uq UNIQUE (person_id),
    -- El correo identifica la cuenta de cara al login y a las invitaciones del #18.
    CONSTRAINT user_account_email_uq UNIQUE (email),
    CONSTRAINT user_account_status_ck
        CHECK (status IN ('INVITED', 'ACTIVE', 'BLOCKED', 'DEACTIVATED')),
    CONSTRAINT user_account_email_ck CHECK (position('@' IN email) > 1)
);

COMMENT ON TABLE user_account IS 'Credencial de acceso. Global, sin condominio y sin contraseña (Seguridad 2).';

-- Dos cuentas no pueden apuntar al mismo sujeto de Keycloak. Parcial porque la columna es nula
-- hasta el primer login.
CREATE UNIQUE INDEX user_account_idp_subject_uq
    ON user_account (idp_subject)
    WHERE idp_subject IS NOT NULL;


-- La membresia: lo que ata una cuenta a un condominio. Cuelga de user_account y no de person,
-- tal como la modela la seccion 2, y por eso aparece cuando se acepta la invitacion del #18 y no
-- en el alta del edificio.
--
-- Lleva vigencia en vez de borrarse: cerrar la fila con valid_to deja la historia consultable, que
-- es lo que necesita el #19 (baja de membresia y cambio de propietario).
CREATE TABLE membership (
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL,
    condominium_id UUID        NOT NULL,
    status         VARCHAR(20) NOT NULL,
    valid_from     TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Nulo mientras este vigente.
    valid_to       TIMESTAMP WITH TIME ZONE,
    -- Quien invito. Nulo en la membresia del primer administrador del condominio, que no la
    -- invita nadie. Apunta a una cuenta, no a una persona, porque invitar es un acto de alguien
    -- que entro al sistema.
    invited_by     UUID,
    CONSTRAINT membership_pk PRIMARY KEY (id),
    CONSTRAINT membership_user_fk FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT membership_condominium_fk FOREIGN KEY (condominium_id) REFERENCES condominium (id),
    CONSTRAINT membership_invited_by_fk FOREIGN KEY (invited_by) REFERENCES user_account (id),
    CONSTRAINT membership_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT membership_vigencia_ck CHECK (valid_to IS NULL OR valid_to >= valid_from),
    -- Nadie se invita a si mismo.
    CONSTRAINT membership_invited_by_ck CHECK (invited_by IS NULL OR invited_by <> user_id)
);

COMMENT ON TABLE membership IS 'Vinculo entre una cuenta y un condominio, con vigencia (Seguridad 2).';

-- El segundo criterio de aceptacion del issue #11: no dos membresias vigentes de la misma cuenta
-- en el mismo condominio. Parcial sobre valid_to IS NULL por la misma razon que en
-- condominium_config: sin el WHERE, un UNIQUE normal dejaria acumular filas abiertas, porque para
-- PostgreSQL dos nulos no son iguales.
--
-- Deja pasar a proposito la misma cuenta en condominios distintos, que es RF-03, y volver a entrar
-- al mismo condominio despues de una baja, que es el #19.
CREATE UNIQUE INDEX membership_current_uq
    ON membership (user_id, condominium_id)
    WHERE valid_to IS NULL;

CREATE INDEX membership_by_condominium_idx ON membership (condominium_id, status);


-- Una membresia puede tener varios roles: un propietario que tambien esta en el directorio
-- (Seguridad 2). Por eso es tabla y no una columna en membership.
--
-- Los siete valores son los del catalogo de Seguridad seccion 3. Aqui solo esta el enum; el
-- catalogo Role con su alcance (PLATAFORMA o CONDOMINIO) y la matriz de permisos son el issue
-- #16. Mientras ese alcance no exista, PLATFORM_ADMIN se admite como cualquier otro: acotarlo
-- ahora seria adelantarse a la decision del #16, que es tambien quien desbloquea la restriccion
-- de superadministrador que arrastran el #9 y el #10.
CREATE TABLE membership_role (
    membership_id UUID        NOT NULL,
    role          VARCHAR(20) NOT NULL,
    CONSTRAINT membership_role_pk PRIMARY KEY (membership_id, role),
    CONSTRAINT membership_role_membership_fk
        FOREIGN KEY (membership_id) REFERENCES membership (id) ON DELETE CASCADE,
    CONSTRAINT membership_role_ck CHECK (role IN (
        'PLATFORM_ADMIN', 'CONDO_ADMIN', 'FINANCE_ADMIN', 'OWNER', 'TENANT', 'MAINTENANCE', 'BOARD'))
);

COMMENT ON TABLE membership_role IS 'Roles de una membresia. El catalogo y los permisos son el issue #16.';


-- Las unidades se referencian por (id, condominium_id) y no solo por id, para que una fila no
-- pueda decir que pertenece a un condominio distinto del de su unidad. La clave foranea compuesta
-- de las tres tablas de abajo necesita esta unicidad; sin ella, el condominium_id denormalizado
-- que pide la seccion 6 seria un dato que hay que recordar mantener a mano.
ALTER TABLE unit ADD CONSTRAINT unit_id_condominium_uq UNIQUE (id, condominium_id);


-- Quien es propietario de que. Varias filas vigentes por unidad: la copropiedad existe, y de ahi
-- el porcentaje.
--
-- La seccion 2 lo dice asi: "un propietario ve solo las unidades que figuran en UnitOwnership
-- vigente". La autorizacion del #15 y el aislamiento del #17 leen de aqui, no de la membresia:
-- el rol dice que tipo de cosas puede hacer, y estas tablas sobre que unidades.
CREATE TABLE unit_ownership (
    id             UUID NOT NULL DEFAULT gen_random_uuid(),
    condominium_id UUID NOT NULL,
    unit_id        UUID NOT NULL,
    person_id      UUID NOT NULL,
    -- Porcentaje de copropiedad sobre la unidad. Seis decimales y NUMERIC, igual que la alicuota
    -- de unit y por la misma razon (analisis 12.2): con dos, lo que no cuadra son bolivianos.
    share          NUMERIC(9, 6) NOT NULL,
    valid_from     TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_to       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT unit_ownership_pk PRIMARY KEY (id),
    CONSTRAINT unit_ownership_unit_fk
        FOREIGN KEY (unit_id, condominium_id) REFERENCES unit (id, condominium_id),
    CONSTRAINT unit_ownership_person_fk FOREIGN KEY (person_id) REFERENCES person (id),
    CONSTRAINT unit_ownership_share_ck CHECK (share > 0 AND share <= 100),
    CONSTRAINT unit_ownership_vigencia_ck CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

COMMENT ON TABLE unit_ownership IS 'Titularidad de una unidad, con vigencia (analisis 5.2, Seguridad 2).';

-- Una sola titularidad vigente por persona y unidad. No limita cuantos copropietarios tiene la
-- unidad, solo que la misma persona no figure dos veces.
CREATE UNIQUE INDEX unit_ownership_current_uq
    ON unit_ownership (unit_id, person_id)
    WHERE valid_to IS NULL;

CREATE INDEX unit_ownership_by_person_idx ON unit_ownership (person_id, valid_to);


-- Quien vive en que. Separada de la titularidad porque son cosas distintas: el propietario que
-- alquila no reside, y el inquilino reside sin ser propietario.
CREATE TABLE unit_occupancy (
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    condominium_id UUID        NOT NULL,
    unit_id        UUID        NOT NULL,
    person_id      UUID        NOT NULL,
    -- Residente o inquilino, tal como lo nombra el analisis 5.2.
    role           VARCHAR(20) NOT NULL,
    valid_from     TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_to       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT unit_occupancy_pk PRIMARY KEY (id),
    CONSTRAINT unit_occupancy_unit_fk
        FOREIGN KEY (unit_id, condominium_id) REFERENCES unit (id, condominium_id),
    CONSTRAINT unit_occupancy_person_fk FOREIGN KEY (person_id) REFERENCES person (id),
    CONSTRAINT unit_occupancy_role_ck CHECK (role IN ('RESIDENT', 'TENANT')),
    CONSTRAINT unit_occupancy_vigencia_ck CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

COMMENT ON TABLE unit_occupancy IS 'Quien reside en una unidad, con vigencia (analisis 5.2).';

CREATE UNIQUE INDEX unit_occupancy_current_uq
    ON unit_occupancy (unit_id, person_id)
    WHERE valid_to IS NULL;

CREATE INDEX unit_occupancy_by_person_idx ON unit_occupancy (person_id, valid_to);


-- Quien paga que. Es la tercera relacion y la razon de que no haya una sola tabla con columna de
-- tipo: en un alquiler corriente el inquilino reside y el propietario sigue siendo el responsable
-- de pago, y eso no se puede decir con un unico "tipo" por persona y unidad.
--
-- La seccion 2 la usa asi: "un inquilino, solo los servicios de los que figura como responsable en
-- BillingResponsibility vigente". Cuando la fila vence, el acceso a esa unidad se pierde sin
-- tocar la cuenta.
CREATE TABLE billing_responsibility (
    id             UUID NOT NULL DEFAULT gen_random_uuid(),
    condominium_id UUID NOT NULL,
    unit_id        UUID NOT NULL,
    person_id      UUID NOT NULL,
    -- Nulo = responsable de todo lo que se cobre a la unidad. Con valor = solo de ese concepto,
    -- que es como se reparte "el inquilino paga el agua y el propietario las expensas".
    --
    -- Sin clave foranea TODAVIA: charge_concept nace en M2 (analisis 5.2), y la restriccion entra
    -- con el. La columna esta desde ahora porque el analisis la nombra y asi M2 no necesita
    -- migrar la tabla, solo añadirle la clave.
    concept_id     UUID,
    valid_from     TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_to       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT billing_responsibility_pk PRIMARY KEY (id),
    CONSTRAINT billing_responsibility_unit_fk
        FOREIGN KEY (unit_id, condominium_id) REFERENCES unit (id, condominium_id),
    CONSTRAINT billing_responsibility_person_fk FOREIGN KEY (person_id) REFERENCES person (id),
    CONSTRAINT billing_responsibility_vigencia_ck CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

COMMENT ON TABLE billing_responsibility IS 'Quien responde por los cobros de una unidad (analisis 5.2, Seguridad 2).';

-- Un responsable vigente por unidad, concepto y persona. NULLS NOT DISTINCT porque concept_id es
-- opcional y aqui los nulos SI tienen que compararse iguales: sin eso, la misma persona podria
-- figurar dos veces como responsable general de la misma unidad. Requiere PostgreSQL 15 o
-- superior y la imagen es la 16.
CREATE UNIQUE INDEX billing_responsibility_current_uq
    ON billing_responsibility (unit_id, person_id, concept_id) NULLS NOT DISTINCT
    WHERE valid_to IS NULL;

CREATE INDEX billing_responsibility_by_person_idx ON billing_responsibility (person_id, valid_to);


-- ---------------------------------------------------------------------------------------------
-- Deuda del issue #10: condominium_id en floor.
--
-- La seccion 6 exige condominium_id en toda tabla de negocio y la 12.2 contempla Row-Level
-- Security, que no puede filtrar floor sin unir contra block. Era la decision pendiente de la
-- seccion 13.2 y se resolvio el 12-oct-2026: la columna va. Se hace aqui, con el edificio todavia
-- vacio, y no cuando duela.
--
-- Las otras tablas de negocio ya la llevaban: block y unit desde la V4, condominium_config desde
-- la V3.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE floor ADD COLUMN condominium_id UUID;

UPDATE floor SET condominium_id = block.condominium_id
    FROM block
    WHERE floor.block_id = block.id;

ALTER TABLE floor ALTER COLUMN condominium_id SET NOT NULL;

-- Igual que arriba: referenciar el bloque por (id, condominium_id) hace imposible que un piso
-- diga que es de un condominio distinto del de su bloque. Esa incoherencia hoy la evita el
-- servicio (lo corrigio el PR #72); a partir de aqui no depende de que nadie se acuerde.
ALTER TABLE block ADD CONSTRAINT block_id_condominium_uq UNIQUE (id, condominium_id);

ALTER TABLE floor ADD CONSTRAINT floor_condominium_fk
    FOREIGN KEY (block_id, condominium_id) REFERENCES block (id, condominium_id);

-- La clave simple de la V4 sobra: la compuesta de arriba ya garantiza que el bloque exista, y
-- ademas que sea del mismo condominio. Dejar las dos solo duplica la comprobacion en cada insert.
ALTER TABLE floor DROP CONSTRAINT floor_block_fk;

CREATE INDEX floor_by_condominium_idx ON floor (condominium_id);
