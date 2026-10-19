package bo.coprop.audit.internal;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * Las filas de un condominio, con los tres filtros del issue y todos opcionales.
     *
     * <p>Una sola consulta en vez de ocho metodos derivados --tres filtros opcionales son ocho
     * combinaciones-- o una {@code Specification}, que para esto serian treinta lineas de
     * constructor de criterios que hacen lo que aqui se lee de un vistazo. El plan que elige
     * PostgreSQL sigue usando el indice del condominio, que es el que acota de verdad.
     *
     * <p><strong>Los filtros de texto van con {@code coalesce} y no con {@code :x is null or
     * ...}</strong>, que es lo primero que se escribe y no funciona: en {@code ? is null} PostgreSQL
     * no tiene de donde deducir el tipo del parametro y responde <em>could not determine data type
     * of parameter</em>. Dentro de un {@code coalesce} el parametro esta al lado de una columna
     * tipada, asi que lo deduce de ella; y comparar una columna consigo misma cuando el filtro es
     * nulo es cierto siempre, que es justo "no filtrar". Costo una corrida de tests descubrirlo.
     *
     * <p>Las fechas no llegan nunca nulas: el servicio las sustituye por los extremos del tiempo
     * antes de llamar, porque un {@code <} no se puede escribir con el truco del {@code coalesce}
     * --comparar una columna consigo misma con {@code <} es falso siempre, no cierto--.
     *
     * <p>{@code hasta} es exclusivo y {@code desde} inclusivo, para que dos consultas consecutivas
     * de rangos contiguos no devuelvan la misma fila dos veces.
     *
     * <p>El orden lo pone el {@link Pageable} de quien llama, y es siempre por fecha descendente:
     * una bitacora se lee empezando por lo ultimo.
     */
    @Query(
            """
            select a from AuditLog a
            where a.condominiumId = :condominio
              and a.actor = coalesce(:actor, a.actor)
              and a.entity = coalesce(:entity, a.entity)
              and a.occurredAt >= :desde
              and a.occurredAt < :hasta
            """)
    Page<AuditLog> find(
            @Param("condominio") UUID condominium,
            @Param("actor") @Nullable String actor,
            @Param("entity") @Nullable String entity,
            @Param("desde") Instant from,
            @Param("hasta") Instant until,
            Pageable page);

    /**
     * Las filas que no cuelgan de ningun condominio: la bitacora de plataforma del issue #20.
     *
     * <p>Es la misma consulta con {@code is null} en vez de la igualdad, y va aparte en vez de
     * aceptar un condominio nulo en la anterior: en {@code a.condominiumId = :condominio} un
     * parametro nulo no empareja con las filas nulas --en SQL {@code null = null} no es cierto-- asi
     * que una sola consulta no puede servir para las dos cosas ni queriendo.
     */
    @Query(
            """
            select a from AuditLog a
            where a.condominiumId is null
              and a.actor = coalesce(:actor, a.actor)
              and a.entity = coalesce(:entity, a.entity)
              and a.occurredAt >= :desde
              and a.occurredAt < :hasta
            """)
    Page<AuditLog> findPlatformEntries(
            @Param("actor") @Nullable String actor,
            @Param("entity") @Nullable String entity,
            @Param("desde") Instant from,
            @Param("hasta") Instant until,
            Pageable page);
}
