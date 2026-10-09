-- V3: el condominio, raiz del modelo multi-tenant, y su configuracion con vigencia.
--
-- Analisis 5.2 (entidades) y RF-01. Todo dato del sistema colgara de condominium.id, directa o
-- indirectamente; el aislamiento de verdad lo impone el issue #17.

CREATE TABLE condominium (
    id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    name       VARCHAR(160) NOT NULL,
    -- Identifica legalmente al condominio. Dos con el mismo NIT serian el mismo, y a partir de
    -- ahi los pagos de uno podrian aplicarse al otro.
    nit        VARCHAR(20)  NOT NULL,
    type       VARCHAR(20)  NOT NULL,
    -- Zona horaria IANA. El analisis 12.2 guarda las fechas en UTC y calcula los vencimientos y
    -- la mora en la zona del condominio, asi que cada uno trae la suya.
    time_zone  VARCHAR(64)  NOT NULL,
    -- Hoy siempre BOB, pero explicito: el analisis 12.2 exige moneda en cada monto, y un sistema
    -- de cobros que la asume es el que un dia suma bolivianos con dolares sin avisar.
    currency   VARCHAR(3)   NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT condominium_pk PRIMARY KEY (id),
    CONSTRAINT condominium_nit_uq UNIQUE (nit),
    CONSTRAINT condominium_type_ck CHECK (type IN ('EDIFICIO', 'CASAS', 'MIXTO')),
    CONSTRAINT condominium_status_ck CHECK (status IN ('ACTIVO', 'SUSPENDIDO'))
);

COMMENT ON TABLE condominium IS 'Raiz del modelo multi-tenant (analisis 5.2).';

-- La configuracion NO se actualiza en sitio: cambiarla cierra la fila vigente y abre otra. Es lo
-- que hace cierto el segundo criterio del issue #9 -- cambiar la configuracion no altera
-- obligaciones ya emitidas -- porque la configuracion con la que se emitieron sigue existiendo.
--
-- Solo contiene lo que no tiene entidad propia. RF-01 habla tambien de mora, agua y reservas,
-- pero el analisis 5.2 las modela aparte y con su propia vigencia: late_fee_policy y water_tariff
-- en M2, common_area en M3.
CREATE TABLE condominium_config (
    id             UUID NOT NULL DEFAULT gen_random_uuid(),
    condominium_id UUID NOT NULL,
    -- Limitados a 28: un condominio que emitiera el 30 no emitiria en febrero, y ese agujero solo
    -- se descubre en produccion y en febrero.
    issue_day      INTEGER NOT NULL,
    due_day        INTEGER NOT NULL,
    valid_from     TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Nulo mientras sea la vigente.
    valid_to       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT condominium_config_pk PRIMARY KEY (id),
    CONSTRAINT condominium_config_condominium_fk
        FOREIGN KEY (condominium_id) REFERENCES condominium (id),
    CONSTRAINT condominium_config_issue_day_ck CHECK (issue_day BETWEEN 1 AND 28),
    CONSTRAINT condominium_config_due_day_ck CHECK (due_day BETWEEN 1 AND 28),
    CONSTRAINT condominium_config_vigencia_ck CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

-- Una sola configuracion vigente por condominio. Es un indice parcial y no una restriccion UNIQUE
-- normal porque UNIQUE en PostgreSQL no considera iguales dos nulos: sin el WHERE, un condominio
-- podria acumular varias filas abiertas sin que nada lo impidiera.
CREATE UNIQUE INDEX condominium_config_vigente_uq
    ON condominium_config (condominium_id)
    WHERE valid_to IS NULL;

-- Se consulta la historia de un condominio ordenada por vigencia.
CREATE INDEX condominium_config_by_condominium_idx
    ON condominium_config (condominium_id, valid_from DESC);
