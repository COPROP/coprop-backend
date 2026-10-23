-- V14: la configuracion de expensas, con vigencia. Issue #24.
--
-- El analisis 5.2 deja el "metodo de calculo" como atributo del concepto de cobro, pero el #100 lo
-- saco de charge_concept a proposito y lo dejo escrito: el concepto dice QUE se cobra y el calculo
-- es de este ticket y del #25. La razon de que sea tabla aparte y no dos columnas mas en
-- charge_concept es la VIGENCIA: el criterio del #24 pide poder consultar la configuracion vigente
-- "con su historial de vigencias", y una fila que se actualiza en sitio no tiene historia.
--
-- Es el mismo patron que condominium_config (V3) y el que el analisis 5.2 reserva para
-- water_tariff y late_fee_policy: nunca se actualiza, se cierra la vigente y se abre otra.
--
-- Y es lo que por fin llena source_type/source_id de payment_obligation (V13): una obligacion de
-- expensas apunta con ('EXPENSE_SETTING', id) a la fila con la que se calculo su monto, asi que
-- "por que debo 350" se contesta sin reconstruir nada. Sigue sin enumeracion ni clave foranea
-- --el origen es polimorfico-- y con una sola clase de origen escrita no se cierra todavia con un
-- CHECK: el #25 añade la segunda y el #27 la tercera.

CREATE TABLE expense_setting (
    id             UUID          NOT NULL DEFAULT gen_random_uuid(),
    condominium_id UUID          NOT NULL,
    -- Que concepto se emite con esta configuracion. Hay una por concepto y no una por condominio:
    -- un edificio cobra "expensas ordinarias" por alicuota y "fondo de reserva" a monto fijo, y
    -- son dos conceptos con dos reglas.
    concept_id     UUID          NOT NULL,
    -- FIXED_PER_UNIT o BY_ALIQUOT, las dos que el ticket nombra. RF-10 enumera tambien por m2 y
    -- por tipo de unidad; no entran en el MVP y por eso el CHECK se queda en dos valores: una
    -- enumeracion cuyos valores nadie escribe ya costo dos cabos sueltos en este proyecto.
    method         VARCHAR(20)   NOT NULL,
    -- UN SOLO MONTO, y su significado lo da el metodo: con FIXED_PER_UNIT es lo que paga cada
    -- unidad, con BY_ALIQUOT es el total a repartir entre todas. Dos columnas nulas alternas
    -- --amount_per_unit y total_amount con un CHECK cruzado-- dirian lo mismo con el doble de
    -- reglas, y la pregunta "cuanto dice esta configuracion" dejaria de tener una respuesta.
    amount         NUMERIC(14,2) NOT NULL,
    -- La moneda explicita al lado del monto, que el analisis 12.2 exige en cada monto. Se copia
    -- del condominio al configurar, igual que en payment_obligation.
    currency       VARCHAR(3)    NOT NULL,
    valid_from     TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Nulo mientras sea la vigente.
    valid_to       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT expense_setting_pk PRIMARY KEY (id),
    CONSTRAINT expense_setting_condominium_fk FOREIGN KEY (condominium_id) REFERENCES condominium (id),
    -- Compuesta, con el condominio dentro, como las cuatro de payment_obligation: asi la
    -- configuracion de un edificio no puede apuntar al concepto de otro.
    CONSTRAINT expense_setting_concept_fk
        FOREIGN KEY (concept_id, condominium_id) REFERENCES charge_concept (id, condominium_id),
    CONSTRAINT expense_setting_method_ck CHECK (method IN ('FIXED_PER_UNIT', 'BY_ALIQUOT')),
    -- Una expensa de cero no es un cobro: lo que corresponde ahi es no emitir el mes.
    CONSTRAINT expense_setting_amount_ck CHECK (amount > 0),
    CONSTRAINT expense_setting_validity_ck CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

COMMENT ON TABLE expense_setting IS 'Como se calculan las expensas de un concepto, con vigencia (analisis 2.3 y 7.1, issue #24).';

COMMENT ON COLUMN expense_setting.amount IS
    'Con FIXED_PER_UNIT, lo que paga cada unidad. Con BY_ALIQUOT, el total a repartir.';

-- Una sola vigente por condominio y concepto. Es la restriccion que hace que "la configuracion
-- vigente" sea una fila y no una consulta con criterio, y la que obliga a cerrar la anterior antes
-- de abrir la siguiente. Mismo patron que condominium_config_current_uq de la V3.
CREATE UNIQUE INDEX expense_setting_current_uq
    ON expense_setting (condominium_id, concept_id)
    WHERE valid_to IS NULL;

-- Se consulta la historia del condominio ordenada por vigencia, igual que la del config.
CREATE INDEX expense_setting_by_condominium_idx
    ON expense_setting (condominium_id, valid_from DESC);
