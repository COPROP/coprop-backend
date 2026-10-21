-- V12: el periodo de facturacion, el mes al que pertenece cada obligacion. Issue #101.
--
-- El analisis 5.2 lo define como entidad propia y con estado, y no como dos columnas sueltas
-- dentro de la obligacion: PaymentObligation (#23) apunta aqui con period_id, el worker de
-- emision programada (#28) necesita algo que cerrar, y las lecturas de agua (#26) se cuelgan del
-- periodo antes de que exista ninguna obligacion.
--
-- El periodo se abre ANTES de emitir y nace en DRAFT (decidido el 19-oct-2026, escrito en el
-- cuerpo del issue). La emision lo abre si falta, de forma idempotente, para que un mes que nadie
-- preparo no deje al worker sin emitir.

CREATE TABLE billing_period (
    id             UUID    NOT NULL DEFAULT gen_random_uuid(),
    condominium_id UUID    NOT NULL,
    -- El año y el mes en dos columnas enteras, no una fecha: un periodo es un mes entero y no un
    -- dia, y una columna DATE obligaria a elegir un dia que no significa nada --el 1, el 15-- que
    -- despues alguien leeria como si lo significara. Mismo tipo y misma forma de acotar que
    -- condominium_config.issue_day.
    year           INTEGER NOT NULL,
    month          INTEGER NOT NULL,
    status         VARCHAR(20) NOT NULL,
    CONSTRAINT billing_period_pk PRIMARY KEY (id),
    CONSTRAINT billing_period_condominium_fk FOREIGN KEY (condominium_id) REFERENCES condominium (id),
    -- Un solo periodo por condominio, año y mes. Es la restriccion que hace idempotente la
    -- apertura que el #28 necesita: dos aperturas del mismo mes no pueden dejar dos filas.
    CONSTRAINT billing_period_month_uq UNIQUE (condominium_id, year, month),
    -- Para que payment_obligation (#23) pueda referenciarlo por (id, condominium_id) y no solo por
    -- id, que es lo que hace imposible colgar una obligacion del periodo de otro condominio. Mismo
    -- patron que charge_concept_id_condominium_uq de la V11.
    CONSTRAINT billing_period_id_condominium_uq UNIQUE (id, condominium_id),
    CONSTRAINT billing_period_month_ck CHECK (month BETWEEN 1 AND 12),
    -- El año se acota para que un dedazo --un 202 o un 20266-- lo rechace la base y no quede una
    -- fila que nadie volvera a mirar. El limite alto no es una fecha de caducidad del sistema: es
    -- el rango en el que un periodo tiene sentido.
    CONSTRAINT billing_period_year_ck CHECK (year BETWEEN 2000 AND 2100),
    CONSTRAINT billing_period_status_ck CHECK (status IN ('DRAFT', 'ISSUED', 'CLOSED'))
);

COMMENT ON TABLE billing_period IS 'El mes de facturacion y su estado (analisis 5.2, issue #101).';

COMMENT ON COLUMN billing_period.status IS
    'DRAFT se prepara, ISSUED ya salio a cobro, CLOSED es el cierre contable. No vuelve atras.';

-- Sin indice propio por condominio: billing_period_month_uq ya es un indice que empieza por
-- condominium_id, y listar los periodos del condominio lo usa tal cual. Es el mismo criterio que
-- dejo charge_concept con un solo indice en la V11.
