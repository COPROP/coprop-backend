-- V11: el concepto de cobro, el catalogo de que se cobra en cada condominio. Issue #100.
--
-- El analisis 5.2 lo define como un catalogo configurable por condominio y no como una
-- enumeracion fija: lo que un edificio llama "expensas" otro lo cobra en dos conceptos, y el
-- nombre y el codigo los pone el administrador. PaymentObligation (#23) apunta aqui para decir
-- que se cobra.
--
-- Esta tabla cierra ademas la deuda que la V5 dejo escrita en billing_responsibility.concept_id:
-- la columna nacio sin clave foranea porque charge_concept no existia, y la restriccion entra al
-- final de este archivo.

CREATE TABLE charge_concept (
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    condominium_id UUID        NOT NULL,
    -- El codigo con el que el administrador lo nombra en sus planillas: "EXP", "AGUA", "MORA-EXP".
    code           VARCHAR(32) NOT NULL,
    name           VARCHAR(80) NOT NULL,
    -- Que clase de cobro es (analisis 5.2).
    type           VARCHAR(20) NOT NULL,
    -- Con que otros conceptos comparte QR (analisis 5.3). NO se deriva del tipo, y por eso es una
    -- columna aparte: un LATE_FEE existe una vez por grupo --la mora de expensas con las expensas
    -- y la del agua con el agua-- y las dos filas tienen el mismo tipo y distinto grupo.
    payment_group  VARCHAR(20) NOT NULL,
    CONSTRAINT charge_concept_pk PRIMARY KEY (id),
    CONSTRAINT charge_concept_condominium_fk FOREIGN KEY (condominium_id) REFERENCES condominium (id),
    -- Unico dentro del condominio, no global: el "AGUA" de Las Palmas y el de Villa Sol son
    -- conceptos distintos, con tarifas distintas. Es el mismo criterio que el codigo de la unidad.
    CONSTRAINT charge_concept_code_uq UNIQUE (condominium_id, code),
    -- Para que otras tablas puedan referenciarlo por (id, condominium_id) y no solo por id, que es
    -- lo que hace imposible apuntar al concepto de otro condominio. Mismo patron que
    -- block_id_condominium_uq de la V5.
    CONSTRAINT charge_concept_id_condominium_uq UNIQUE (id, condominium_id),
    CONSTRAINT charge_concept_type_ck
        CHECK (type IN ('DUES', 'WATER', 'FINE', 'LATE_FEE', 'RESERVATION', 'OTHER')),
    CONSTRAINT charge_concept_payment_group_ck
        CHECK (payment_group IN ('DUES', 'WATER', 'RESERVATIONS', 'FINES'))
);

COMMENT ON TABLE charge_concept IS 'Que se cobra en este condominio (analisis 5.2, issue #100).';

COMMENT ON COLUMN charge_concept.payment_group IS
    'Que obligaciones caben en un mismo QR (analisis 5.3). Independiente del tipo.';

-- Sin indice propio por condominio: charge_concept_code_uq ya es un indice que empieza por
-- condominium_id, y listar el catalogo del condominio lo usa tal cual. (unit tiene los dos, y el
-- segundo no aporta nada; aqui no se repite.)


-- ---------------------------------------------------------------------------------------------
-- La deuda de la V5: billing_responsibility.concept_id sin clave foranea.
--
-- Se referencia por (id, condominium_id) y no por id, igual que la tabla hace ya con la unidad:
-- asi es imposible que el responsable del agua de una unidad apunte al concepto "AGUA" de otro
-- edificio. Con una clave simple esa incoherencia solo la evitaria el servicio, y el servicio no
-- puede: UnitRelations es de condominium, y condominium no depende de billing --la flecha va al
-- contrario--. Por eso lo garantiza la base y no el codigo, y es el criterio del issue #100.
--
-- MATCH SIMPLE, que es el que PostgreSQL aplica por omision, y aqui importa: con una columna nula
-- la restriccion no se comprueba, asi que el responsable general --concept_id nulo, el que responde
-- por todo lo que se cobre a la unidad-- sigue admitido. Con MATCH FULL dejaria de serlo.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE billing_responsibility
    ADD CONSTRAINT billing_responsibility_concept_fk
    FOREIGN KEY (concept_id, condominium_id) REFERENCES charge_concept (id, condominium_id);

-- Sin ON DELETE: borrar un concepto al que alguien responde queda rechazado por la base, que es el
-- comportamiento por omision (NO ACTION) y el que se quiere. La misma proteccion la tendra
-- payment_obligation cuando el #23 la referencie, y es lo que cumple el criterio de que un concepto
-- en uso no se pueda borrar. Hoy, ademas, la API no ofrece borrado ninguno.
