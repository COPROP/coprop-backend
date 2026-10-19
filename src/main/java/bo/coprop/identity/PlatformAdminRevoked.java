package bo.coprop.identity;

import bo.coprop.shared.AuditableEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Una cuenta dejo de administrar la plataforma. Issue <strong>#20</strong>.
 *
 * <p>Es el evento <strong>mas importante</strong> de la bitacora de plataforma junto a su contrario:
 * la fila de {@code platform_admin} se borra al revocar, asi que esto es lo unico que queda diciendo
 * que esa cuenta tuvo el rol, quien se lo quito y por que. Sin esta fila, revocar seria
 * indistinguible de no haber concedido nunca.
 *
 * <p>Va sin condominio, como {@link PlatformAdminGranted}: el alcance de este rol es la plataforma.
 * Las dos salen por {@code GET /api/v1/auditoria} y no por la de un condominio.
 *
 * @param sesionesCerradas si se le cerraron las sesiones. Se intenta siempre --Seguridad 8 pide
 *     revocarlas al retirar un rol administrativo, y no hay rol mas administrativo que este-- y lo
 *     que se registra es si quedaron cerradas, no si se intento
 */
public record PlatformAdminRevoked(
        UUID accountId, String reason, boolean closedSessions, String actor, Instant occurredAt)
        implements AuditableEvent {

    @Override
    public String action() {
        return "PLATFORM_REVOKED";
    }

    /** Nulo: el alcance de este rol es la plataforma y no cuelga de ningun condominio. */
    @Override
    public @Nullable UUID condominiumId() {
        return null;
    }

    @Override
    public String entity() {
        return "superadministrador";
    }

    @Override
    public UUID entityId() {
        return accountId;
    }

    /** Lo que habia: el rol. No hay mas valores que mirar, porque esta tabla no tiene mas. */
    @Override
    public Map<String, String> oldValues() {
        return Map.of("rol", MembershipRole.PLATFORM_ADMIN.name());
    }

    @Override
    public Map<String, String> newValues() {
        return Map.of("motivo", reason, "sesionesCerradas", String.valueOf(closedSessions));
    }
}
