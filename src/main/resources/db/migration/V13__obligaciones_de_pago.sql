-- V13: la obligacion de pago, la entidad central del sistema. Issue #23.
--
-- Todo lo que genera deuda --expensas, agua, multas, mora, reservas-- acaba aqui, y de aqui
-- cuelgan el QR, el pago y la conciliacion (analisis 5.1 y 5.3). El analisis lo resume en una
-- cadena: unit -> payment_obligation -> payment_intent_line -> payment_intent ->
-- provider_transaction -> payment -> payment_allocation.
--
-- DOS DECISIONES DEL ANALISIS QUE ESTA TABLA MATERIALIZA:
--
-- 1. "Origen por referencia, no por herencia" (5.1): no hay una tabla por clase de deuda. Cada
--    modulo guarda su entidad de negocio --el periodo de expensas, la lectura de agua, la multa,
--    la reserva-- y la obligacion apunta a ella con source_type + source_id. Asi el cobro no
--    conoce las reglas del agua.
-- 2. "La mora apunta a su obligacion padre" (5.2): parent_obligation_id. Es lo que hace que el
--    recargo de una expensa se cobre con esa expensa, porque hereda el grupo de pago de su
--    concepto.
--
-- EL SALDO NO ESTA, Y ES A PROPOSITO: en el MVP no hay pagos parciales (decidido el 19-oct-2026 y
-- escrito en 5.3 del analisis). Una obligacion se paga por su monto completo, asi que un saldo
-- seria siempre el monto o cero. El estado PARTIAL y el campo "admite pago parcial" del concepto
-- se quedan igualmente para Fase 2.

CREATE TABLE payment_obligation (
    id                   UUID          NOT NULL DEFAULT gen_random_uuid(),
    condominium_id       UUID          NOT NULL,
    unit_id              UUID          NOT NULL,
    -- Quien responde por esta deuda. Se congela al crearla: si la unidad se vende despues, lo
    -- emitido sigue siendo del vendedor, que es lo que pide una historia de cobros que no se
    -- reescribe. Quien responde HOY lo dice billing_responsibility, que es otra pregunta.
    responsible_person_id UUID         NOT NULL,
    concept_id           UUID          NOT NULL,
    -- Nulo cuando la deuda no es de un mes: una multa o una reserva no pertenecen a un periodo.
    period_id            UUID,
    -- El numero con el que una persona la nombra: "EXP-2026-09-A-302" (analisis 5.2). Se compone
    -- del codigo del concepto, el año y mes, y el codigo de la unidad; si ese nombre ya existe
    -- --dos multas del mismo concepto en el mismo mes y unidad-- lleva un correlativo detras.
    number               VARCHAR(80)   NOT NULL,
    -- El origen por referencia del analisis 5.1. VACIOS HASTA EL #24: hoy nada los escribe, y por
    -- eso source_type es texto y no una enumeracion --una enumeracion cuyos valores nadie escribe
    -- ya costo dos cabos sueltos en este proyecto (CondominiumStatus.SUSPENDED y
    -- AccountStatus.DEACTIVATED)--. El #24 crea la primera entidad de origen y decide entonces si
    -- se cierra el conjunto con un CHECK.
    source_type          VARCHAR(30),
    source_id            UUID,
    -- NUMERIC(14,2) y nunca coma flotante, y la moneda explicita al lado: las dos cosas las exige
    -- el analisis 12.2. La moneda se copia del condominio al crear la obligacion, no se pide:
    -- asi un monto no puede quedar sin moneda ni con una que su condominio no usa.
    amount               NUMERIC(14,2) NOT NULL,
    currency             VARCHAR(3)    NOT NULL,
    -- Cuando salio a cobro. Nula mientras esta en DRAFT, que es justo lo que distingue un borrador
    -- de una deuda que el propietario ya puede ver.
    issued_at            TIMESTAMPTZ,
    -- La fecha de vencimiento, no un instante: vence un dia en la zona del condominio, y guardarla
    -- como marca de tiempo obligaria a elegir una hora que nadie decidio (analisis 12.2).
    due_date             DATE          NOT NULL,
    status               VARCHAR(20)   NOT NULL,
    -- La mora apunta a la obligacion que la genero (analisis 5.2). Nulo en todas las demas.
    parent_obligation_id UUID,
    CONSTRAINT payment_obligation_pk PRIMARY KEY (id),
    CONSTRAINT payment_obligation_condominium_fk FOREIGN KEY (condominium_id) REFERENCES condominium (id),
    -- Las cuatro claves foraneas del condominio van COMPUESTAS, con el condominio dentro: es lo
    -- que hace imposible que una obligacion mezcle la unidad de un edificio con el concepto de
    -- otro. Es el patron que la V5 abrio con la unidad y la V11 cerro con el concepto.
    CONSTRAINT payment_obligation_unit_fk
        FOREIGN KEY (unit_id, condominium_id) REFERENCES unit (id, condominium_id),
    CONSTRAINT payment_obligation_concept_fk
        FOREIGN KEY (concept_id, condominium_id) REFERENCES charge_concept (id, condominium_id),
    CONSTRAINT payment_obligation_period_fk
        FOREIGN KEY (period_id, condominium_id) REFERENCES billing_period (id, condominium_id),
    CONSTRAINT payment_obligation_parent_fk
        FOREIGN KEY (parent_obligation_id, condominium_id) REFERENCES payment_obligation (id, condominium_id),
    CONSTRAINT payment_obligation_person_fk FOREIGN KEY (responsible_person_id) REFERENCES person (id),
    CONSTRAINT payment_obligation_number_uq UNIQUE (condominium_id, number),
    -- Para que payment_intent_line y payment_allocation la referencien por el par, igual que todo
    -- lo demas de este esquema.
    CONSTRAINT payment_obligation_id_condominium_uq UNIQUE (id, condominium_id),
    CONSTRAINT payment_obligation_status_ck
        CHECK (status IN ('DRAFT', 'ISSUED', 'PAID', 'CANCELLED', 'IN_AGREEMENT')),
    -- Una obligacion de cero o negativa no es una deuda. El monto cero tampoco vale como "nada que
    -- cobrar": eso es no emitir la obligacion.
    CONSTRAINT payment_obligation_amount_ck CHECK (amount > 0),
    -- Un borrador nunca tiene fecha de emision, y lo que salio a cobro la tiene siempre. Es la
    -- unica regla de la maquina de estados que la base puede comprobar sola, y vale la pena: una
    -- obligacion emitida sin fecha de emision no se puede reconstruir despues.
    --
    -- CANCELLED queda fuera de las dos ramas a proposito, porque se llega desde los dos lados: un
    -- borrador que se descarta se anula sin haber salido nunca --y sigue sin fecha-- y una emitida
    -- que se anula conserva la suya, que es el dato que dice cuanto tiempo estuvo cobrable.
    CONSTRAINT payment_obligation_issued_at_ck
        CHECK (
            (status = 'DRAFT' AND issued_at IS NULL)
            OR (status = 'CANCELLED')
            OR (status IN ('ISSUED', 'PAID', 'IN_AGREEMENT') AND issued_at IS NOT NULL)
        ),
    -- Nada puede ser su propia mora.
    CONSTRAINT payment_obligation_parent_ck CHECK (parent_obligation_id IS NULL OR parent_obligation_id <> id),
    -- El origen va entero o no va: un source_id sin tipo no se puede resolver.
    CONSTRAINT payment_obligation_source_ck
        CHECK ((source_type IS NULL AND source_id IS NULL) OR (source_type IS NOT NULL AND source_id IS NOT NULL))
);

COMMENT ON TABLE payment_obligation IS 'Lo que una unidad debe por un concepto (analisis 5.1-5.3, issue #23).';

COMMENT ON COLUMN payment_obligation.status IS
    'DRAFT -> ISSUED -> PAID, con salidas a CANCELLED e IN_AGREEMENT. Vencida se deriva de due_date.';

-- Lo que se consulta de verdad: la deuda de una unidad, y las obligaciones de un periodo al
-- emitir o al cerrar el mes. El unico por condominio y numero ya cubre la busqueda por numero.
CREATE INDEX payment_obligation_unit_idx ON payment_obligation (condominium_id, unit_id, status);

CREATE INDEX payment_obligation_period_idx ON payment_obligation (condominium_id, period_id);
