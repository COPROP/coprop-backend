-- V4: la unidad y la jerarquia opcional que la organiza. Analisis 5.2, issue #10.
--
-- La jerarquia es opcional de verdad: un condominio de doce casas no crea ningun bloque ni
-- ningun piso, y sus unidades cuelgan directamente del condominio.

CREATE TABLE block (
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    condominium_id UUID        NOT NULL,
    name           VARCHAR(80) NOT NULL,
    CONSTRAINT block_pk PRIMARY KEY (id),
    CONSTRAINT block_condominium_fk FOREIGN KEY (condominium_id) REFERENCES condominium (id),
    CONSTRAINT block_name_uq UNIQUE (condominium_id, name)
);

COMMENT ON TABLE block IS 'Torre o bloque. Opcional (analisis 5.2).';

-- El piso cuelga del bloque, no del condominio, tal como lo modela el analisis 5.2. La
-- consecuencia es que para usar pisos hay que crear al menos un bloque, aunque el edificio sea
-- uno solo: no se desvia del analisis por evitar esa incomodidad.
CREATE TABLE floor (
    id       UUID    NOT NULL DEFAULT gen_random_uuid(),
    block_id UUID    NOT NULL,
    number   INTEGER NOT NULL,
    CONSTRAINT floor_pk PRIMARY KEY (id),
    CONSTRAINT floor_block_fk FOREIGN KEY (block_id) REFERENCES block (id),
    CONSTRAINT floor_number_uq UNIQUE (block_id, number)
);

CREATE TABLE unit (
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    -- NOT NULL mas clave foranea: una unidad no puede existir sin condominio, que es el segundo
    -- criterio de aceptacion del issue #10.
    condominium_id UUID        NOT NULL,
    -- El identificador visible, el que usa la gente: "302", "Casa 7".
    code           VARCHAR(32) NOT NULL,
    type           VARCHAR(20) NOT NULL,
    -- Porcentaje de copropiedad. NUMERIC y nunca un tipo de coma flotante (analisis 12.2). Seis
    -- decimales porque en un edificio de 200 unidades redondear a dos deja varios bolivianos sin
    -- repartir cada mes.
    aliquot        NUMERIC(9, 6) NOT NULL,
    -- Opcional: de un parqueo o una baulera no siempre se conoce.
    area_m2        NUMERIC(10, 2),
    block_id       UUID,
    floor_id       UUID,
    status         VARCHAR(20) NOT NULL,
    CONSTRAINT unit_pk PRIMARY KEY (id),
    CONSTRAINT unit_condominium_fk FOREIGN KEY (condominium_id) REFERENCES condominium (id),
    CONSTRAINT unit_block_fk FOREIGN KEY (block_id) REFERENCES block (id),
    CONSTRAINT unit_floor_fk FOREIGN KEY (floor_id) REFERENCES floor (id),
    -- El primer criterio de aceptacion del issue #10. Unico dentro del condominio, no global:
    -- el "302" de Las Palmas y el "302" de Villa Sol son unidades distintas.
    CONSTRAINT unit_code_uq UNIQUE (condominium_id, code),
    CONSTRAINT unit_type_ck CHECK (type IN ('DEPARTAMENTO', 'CASA', 'LOCAL', 'PARQUEO', 'DEPOSITO')),
    CONSTRAINT unit_status_ck CHECK (status IN ('ACTIVA', 'INACTIVA')),
    CONSTRAINT unit_aliquot_ck CHECK (aliquot >= 0 AND aliquot <= 100),
    CONSTRAINT unit_area_ck CHECK (area_m2 IS NULL OR area_m2 > 0),
    -- Un piso siempre cuelga de un bloque, asi que indicar piso sin bloque no significa nada.
    CONSTRAINT unit_floor_necesita_bloque_ck CHECK (floor_id IS NULL OR block_id IS NOT NULL)
);

COMMENT ON TABLE unit IS 'Unidad de cobro: las obligaciones se emiten contra ella (analisis 5.2).';

-- Listar las unidades de un condominio es la consulta de cabecera de casi toda pantalla de
-- administracion.
CREATE INDEX unit_by_condominium_idx ON unit (condominium_id, code);
