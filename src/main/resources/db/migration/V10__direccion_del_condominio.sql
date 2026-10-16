-- V10: la direccion del condominio, que el alta nunca pidio.
--
-- Issue #90. El analisis 5.2 no le da ubicacion al condominio y el alta (V3) tampoco, asi que hoy
-- un condominio se identifica por nombre y NIT y no hay donde decir donde esta. La pestaña de
-- superadministrador la necesita como columna del listado y como dato del detalle.
--
-- NULA y no obligatoria, por dos razones: los condominios que ya existen no tienen una que poner,
-- y el alta sigue sin pedirla --cambiar el cuerpo de CU-01 es otra decision, fuera de este ticket--.
-- Un NOT NULL con cadena vacia por defecto seria peor: no se distinguiria "no tiene direccion" de
-- "nadie la ha escrito todavia", que son cosas distintas para quien revisa altas incompletas.
--
-- 200 y no 160 como el nombre: una direccion boliviana completa lleva calle, numero, zona y
-- referencia, y la referencia es la mitad del texto.
ALTER TABLE condominium
    ADD COLUMN address VARCHAR(200);

COMMENT ON COLUMN condominium.address IS 'Donde esta el condominio. Nula mientras nadie la escriba (issue #90).';
