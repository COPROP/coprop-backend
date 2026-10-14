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
 * <p>Los nombres de columna van en español porque el analisis nombra esta entidad por sus datos y en
 * español: actor, accion, entidad, antes, despues, fecha, IP.
 */
@Entity
@Table(name = "audit_log")
class AuditLog {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String actor;

    @Column(nullable = false, length = 60)
    private String accion;

    @Column(nullable = false, length = 40)
    private String entidad;

    @Column(name = "entidad_id", nullable = false)
    private UUID entidadId;

    /** Nulo en las acciones de plataforma, que no cuelgan de ningun condominio. */
    @Column(name = "condominium_id")
    private @Nullable UUID condominiumId;

    /** JSON de texto. Nulo en toda alta: no habia nada antes. */
    @Column(length = 4000)
    private @Nullable String antes;

    /** JSON de texto. Nulo cuando la accion no deja valores que mirar. */
    @Column(length = 4000)
    private @Nullable String despues;

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
            String accion,
            String entidad,
            UUID entidadId,
            @Nullable UUID condominiumId,
            @Nullable String antes,
            @Nullable String despues,
            @Nullable String ip,
            @Nullable String traceId,
            Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.actor = actor;
        this.accion = accion;
        this.entidad = entidad;
        this.entidadId = entidadId;
        this.condominiumId = condominiumId;
        this.antes = antes;
        this.despues = despues;
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

    String getAccion() {
        return accion;
    }

    String getEntidad() {
        return entidad;
    }

    UUID getEntidadId() {
        return entidadId;
    }

    @Nullable
    UUID getCondominiumId() {
        return condominiumId;
    }

    @Nullable
    String getAntes() {
        return antes;
    }

    @Nullable
    String getDespues() {
        return despues;
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
