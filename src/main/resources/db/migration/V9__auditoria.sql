-- V9: la bitacora. Append-only de verdad, no por convencion (analisis 5.2 y 9.2, Seguridad 8).
--
-- Guarda lo que 5.2 le pide: actor, accion, entidad, antes, despues, fecha e IP; mas el trace_id
-- que resolvio 13.2, para poder cruzar una fila de la bitacora con la peticion que la origino en
-- los logs.
--
-- QUE SIGNIFICA APPEND-ONLY AQUI. El issue #21 lo pide de dos formas: "sin UPDATE ni DELETE
-- concedidos a la aplicacion" y "un intento de modificar un registro falla a nivel de base de
-- datos". No son lo mismo, y la primera NO SE PUEDE CUMPLIR HOY: la aplicacion se conecta como
-- `coprop`, que la imagen de Postgres crea como SUPERUSUARIO, y un superusuario se salta cualquier
-- REVOKE. Es exactamente lo que se descubrio al evaluar RLS para el issue #17.
--
-- Asi que lo que lo hace cumplir es un DISPARADOR, que si muerde a un superusuario. El REVOKE queda
-- escrito abajo, comentado, para el dia que exista el rol sin superusuario que RLS tambien necesita:
-- entonces son las dos barreras, y la de privilegios es la que no se puede desactivar desde una
-- sesion normal.

CREATE TABLE audit_log (
    id             UUID NOT NULL DEFAULT gen_random_uuid(),
    -- Quien. Es el identificador de la cuenta en texto y no una clave foranea a user_account: la
    -- bitacora tiene que sobrevivir a la baja de la cuenta --su retencion es de un año-- y una FK
    -- la volveria imborrable o dejaria la fila huerfana. Tambien hay actores que no son cuentas,
    -- como el arranque de la plataforma.
    actor          VARCHAR(100) NOT NULL,
    -- Que. Nombre estable, en mayusculas: MEMBRESIA_CONCEDIDA, INVITACION_EMITIDA. Lo declara cada
    -- evento y no se cambia nunca, porque la historia de ayer lo lleva escrito.
    accion         VARCHAR(60) NOT NULL,
    -- Sobre que, en singular y minusculas: membresia, invitacion, configuracion.
    entidad        VARCHAR(40) NOT NULL,
    entidad_id     UUID NOT NULL,
    -- En que condominio. NULO cuando la accion es de plataforma y no cuelga de ninguno: conceder el
    -- rol de superadministrador, o el primer login de una cuenta.
    condominium_id UUID,
    -- Los valores de antes y de despues, como objeto JSON de texto. Nulos cuando no aplican: antes
    -- es nulo en toda alta, y despues en una revocacion.
    --
    -- Texto y no JSONB, aunque el contenido sea JSON. Lo que se guarda es una FOTO opaca: valores
    -- en cadena para que la bitacora siga siendo legible dentro de un año, cuando la forma de la
    -- entidad ya no sea la de hoy, y nada la consulta por dentro --los filtros del issue son actor,
    -- entidad y fechas--. JSONB prometeria una consultabilidad en la que no conviene apoyarse, y
    -- ademas obligaria a que Hibernate mapeara un jsonb: su mapeador de JSON busca el ObjectMapper
    -- de Jackson 2 y aqui manda Jackson 3, que vive en otro paquete. No es un sitio donde valga la
    -- pena pelear.
    antes          VARCHAR(4000),
    despues        VARCHAR(4000),
    -- Desde donde. La pone el filtro de trazas en el contexto de log y la lee el oyente; nula
    -- cuando el evento se reentrega tras un reinicio, porque entonces no hay peticion de la que
    -- sacarla.
    --
    -- VARCHAR y no INET. INET validaria la forma y sabria comparar rangos, pero Hibernate no lo
    -- mapea de serie y `ddl-auto: validate` tumbaria el arranque, que es la misma trampa que ya
    -- costo un CHAR(64) en la V8. 45 caracteres: lo que ocupa un IPv6 con su zona.
    ip             VARCHAR(45),
    -- La traza de la peticion, para cruzar esta fila con sus lineas de log.
    trace_id       VARCHAR(100),
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT audit_log_pk PRIMARY KEY (id)
);

COMMENT ON TABLE audit_log IS 'Bitacora append-only, retencion de un año (Seguridad 8 y 10.1). No se modifica: lo impide un disparador.';

-- Los tres filtros que pide el segundo criterio del issue: actor, entidad y rango de fechas. Y el
-- condominio primero en los dos indices de consulta, porque toda lectura esta acotada a uno: la
-- bitacora de un condominio no la ve el vecino.
CREATE INDEX audit_log_por_condominio_idx ON audit_log (condominium_id, occurred_at DESC);
CREATE INDEX audit_log_por_actor_idx ON audit_log (condominium_id, actor, occurred_at DESC);
CREATE INDEX audit_log_por_entidad_idx ON audit_log (condominium_id, entidad, entidad_id, occurred_at DESC);

-- Para la purga a los doce meses, que recorre por fecha y sin condominio.
CREATE INDEX audit_log_por_fecha_idx ON audit_log (occurred_at);


-- El primer criterio de aceptacion: un intento de modificar un registro falla EN LA BASE.
--
-- Un disparador y no solo un REVOKE, por lo dicho arriba: la aplicacion se conecta como
-- superusuario y los privilegios no le aplican. Esto si: un UPDATE o un DELETE sobre la tabla
-- revienta con un error, venga de la aplicacion, de un psql o de una migracion escrita por descuido.
--
-- Deja pasar el TRUNCATE a proposito: es lo que necesitaria un archivado, y un TRUNCATE no puede
-- alterar una fila sin que se note, al contrario que un UPDATE puntual. La purga de los doce meses
-- NO usa DELETE por eso mismo; usa la funcion de abajo, que es el unico camino autorizado.
CREATE OR REPLACE FUNCTION audit_log_es_inmutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_log es append-only: no se puede % una fila de la bitacora.', lower(TG_OP)
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_sin_update
    BEFORE UPDATE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_es_inmutable();

CREATE TRIGGER audit_log_sin_delete
    BEFORE DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_es_inmutable();


-- La purga de los doce meses (Seguridad 10.1). Es una funcion y no un DELETE de la aplicacion
-- porque el disparador de arriba rechaza cualquier DELETE: el unico camino para borrar es este, que
-- desactiva el disparador dentro de su propia transaccion y lo vuelve a dejar como estaba.
--
-- SECURITY DEFINER no hace falta mientras la aplicacion sea superusuario; cuando exista el rol sin
-- superusuario, es lo que le permitira ejecutarla sin tener permiso de DELETE directo. Queda
-- anotado en ARCHITECTURE.md con el resto de ese cambio.
CREATE OR REPLACE FUNCTION audit_log_purgar(hasta TIMESTAMP WITH TIME ZONE) RETURNS BIGINT AS $$
DECLARE
    borradas BIGINT;
BEGIN
    ALTER TABLE audit_log DISABLE TRIGGER audit_log_sin_delete;
    DELETE FROM audit_log WHERE occurred_at < hasta;
    GET DIAGNOSTICS borradas = ROW_COUNT;
    ALTER TABLE audit_log ENABLE TRIGGER audit_log_sin_delete;
    RETURN borradas;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION audit_log_purgar IS 'Borra la bitacora anterior a esa fecha. Unico camino autorizado: el disparador rechaza cualquier otro DELETE.';


-- Para el dia que la aplicacion deje de conectarse como superusuario, que es el mismo cambio que
-- necesita RLS (ver ARCHITECTURE.md). Entonces esto se descomenta en su propia migracion y pasan a
-- ser dos barreras:
--
--   REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM coprop_app;
--   ALTER FUNCTION audit_log_purgar(TIMESTAMP WITH TIME ZONE) SECURITY DEFINER;
--
-- No se deja puesto hoy porque un REVOKE sobre un superusuario no hace nada, y una barrera que no
-- muerde es peor que ninguna: la primera vez que alguien la vea, creera que esta protegido.
