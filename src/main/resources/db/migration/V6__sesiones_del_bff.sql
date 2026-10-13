-- V6: tablas de Spring Session para el BFF web.
--
-- Seguridad 5.1 pide que la sesion de la web viva en el servidor y no en el navegador: ahi se
-- guardan el access, el refresh y el id token, y el navegador solo recibe una cookie. Guardarla en
-- la base --y no en memoria-- es lo que la hace sobrevivir a un reinicio y, sobre todo, lo que
-- permite revocarla: "cerrar sesion en todos los dispositivos" (5.3) es un DELETE por
-- PRINCIPAL_NAME, y con sesiones en memoria no habria donde borrarlas.
--
-- El DDL es el que publica la propia libreria para PostgreSQL
-- (spring-session-jdbc, org/springframework/session/jdbc/schema-postgresql.sql), copiado sin
-- cambios salvo el formato. Se copia en vez de dejar que Spring Session lo cree
-- (spring.session.jdbc.initialize-schema: never) porque aqui el esquema lo gobierna Flyway y nadie
-- mas. Si se sube de version mayor de Spring Session, hay que comparar ese archivo con esta
-- migracion.
--
-- Los nombres van en mayusculas y sin comillas, tal como los escribe la libreria: PostgreSQL los
-- pliega a minusculas y las consultas de Spring Session, que tampoco citan, los encuentran igual.

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36) NOT NULL,
    SESSION_ID            CHAR(36) NOT NULL,
    CREATION_TIME         BIGINT   NOT NULL,
    LAST_ACCESS_TIME      BIGINT   NOT NULL,
    MAX_INACTIVE_INTERVAL INT      NOT NULL,
    EXPIRY_TIME           BIGINT   NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

COMMENT ON TABLE SPRING_SESSION IS 'Sesiones del BFF web (Seguridad 5.1). Las crea y las borra Spring Session, no la aplicacion.';

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
-- Por PRINCIPAL_NAME se buscan todas las sesiones de una misma cuenta, que es lo que necesita
-- "cerrar sesion en todos los dispositivos".
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BYTEA        NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);

COMMENT ON TABLE SPRING_SESSION_ATTRIBUTES IS 'Contenido de la sesion, tokens incluidos. Nunca se consulta a mano.';
