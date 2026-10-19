package bo.coprop.audit.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de la bitacora. {@code AuditLog} del analisis 5.2.
 *
 * <p><strong>No tiene ni un metodo que cambie nada.</strong> No es estilo: es append-only, y lo que
 * lo hace cumplir es un disparador en la base (migracion V9). Que la entidad tampoco tenga forma de
 * modificarse evita que alguien lo intente y descubra la barrera en produccion.
 *
 * <p><strong>Las columnas van en ingles</strong> como el resto del proyecto, aunque el analisis
 * nombre esta entidad por sus datos y en español: actor, accion, entidad, antes, despues, fecha e
 * IP. La correspondencia es directa --{@code action}, {@code entity}, {@code oldValues},
 * {@code newValues}, {@code occurredAt}, {@code ip}-- y los dos valores guardados dejaron de
 * llamarse "antes" y "despues" para decir que llevan un objeto dentro.
 */
@Entity
@Table(name = "audit_log")
class AuditLog {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String actor;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(nullable = false, length = 40)
    private String entity;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    /** Nulo en las acciones de plataforma, que no cuelgan de ningun condominio. */
    @Column(name = "condominium_id")
    private @Nullable UUID condominiumId;

    /** JSON de texto. Nulo en toda alta: no habia nada antes. */
    @Column(length = 4000)
    private @Nullable String oldValues;

    /** JSON de texto. Nulo cuando la accion no deja valores que mirar. */
    @Column(length = 4000)
    private @Nullable String newValues;

    /** Nula cuando el evento se reentrego y no habia peticion de la que sacarla. */
    @Column(length = 45)
    private @Nullable String ip;

    @Column(name = "trace_id", length = 100)
    private @Nullable String traceId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditLog() {}

    AuditLog(
            String actor,
            String action,
            String entity,
            UUID entityId,
            @Nullable UUID condominiumId,
            @Nullable String oldValues,
            @Nullable String newValues,
            @Nullable String ip,
            @Nullable String traceId,
            Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.actor = actor;
        this.action = action;
        this.entity = entity;
        this.entityId = entityId;
        this.condominiumId = condominiumId;
        this.oldValues = oldValues;
        this.newValues = newValues;
        this.ip = ip;
        this.traceId = traceId;
        this.occurredAt = occurredAt;
    }

    UUID getId() {
        return id;
    }

    String getActor() {
        return actor;
    }

    String getAction() {
        return action;
    }

    String getEntity() {
        return entity;
    }

    UUID getEntidadId() {
        return entityId;
    }

    @Nullable
    UUID getCondominiumId() {
        return condominiumId;
    }

    @Nullable
    String getAntes() {
        return oldValues;
    }

    @Nullable
    String getDespues() {
        return newValues;
    }

    @Nullable
    String getIp() {
        return ip;
    }

    @Nullable
    String getTraceId() {
        return traceId;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }
}
