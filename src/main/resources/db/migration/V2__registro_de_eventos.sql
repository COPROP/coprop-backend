-- V2: tabla del registro de publicaciones de eventos de Spring Modulith.
--
-- El starter de JPA mapea una entidad a event_publication y Modulith no crea la tabla por su
-- cuenta cuando el esquema lo gobierna Flyway. Sin esto, ddl-auto: validate tumba el arranque con
-- "missing table [event_publication]".
--
-- El DDL es el esquema v2 que publica la propia libreria para PostgreSQL
-- (spring-modulith-events-jdbc, schemas/v2/schema-postgresql.sql), que coincide columna por
-- columna con lo que la entidad de JPA espera. Si se sube de version mayor de Modulith, hay que
-- comparar ese archivo con esta migracion.
--
-- No se crea event_publication_archive: solo hace falta con CompletionMode.ARCHIVE y el modo por
-- defecto actualiza la fila en sitio.

CREATE TABLE event_publication (
    id                     UUID NOT NULL,
    listener_id            TEXT NOT NULL,
    event_type             TEXT NOT NULL,
    serialized_event       TEXT NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    -- La entidad lo mapea a un int primitivo, nunca escribe null.
    completion_attempts    INTEGER NOT NULL DEFAULT 0,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

-- Se completa una publicacion buscandola por su evento serializado: igualdad exacta sobre un
-- texto que puede ser largo, que es justo para lo que sirve un indice hash.
CREATE INDEX event_publication_serialized_event_hash_idx
    ON event_publication USING hash (serialized_event);

-- El reintento al arrancar y el actuator de modulith barren las publicaciones sin completar.
CREATE INDEX event_publication_by_completion_date_idx
    ON event_publication (completion_date);
