package bo.coprop.identity;

import bo.coprop.shared.AuditableEvent;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Una cuenta paso a tener alcance de plataforma.
 *
 * <p>Es de los eventos que mas importa que lleguen a la bitacora del issue #21: el
 * superadministrador es quien crea condominios y quien puede abrir un acceso de soporte, asi que
 * quien se lo concedio a quien es justo lo que hay que poder reconstruir despues.
 *
 * @param grantedBy nulo en el primero, que no lo concede nadie: lo abre el arranque de la
 *     plataforma cuando no hay ninguno
 */
public record PlatformAdminGranted(UUID accountId, @Nullable UUID grantedBy, Instant occurredAt)
        implements AuditableEvent {

    /** Lo que se escribe como actor cuando no lo concedio nadie: lo abrio el arranque. */
    static final String ARRANQUE = "arranque";

    @Override
    public String action() {
        return "PLATFORM_GRANTED";
    }

    /**
     * Nulo: el alcance de este rol es la plataforma y no cuelga de ningun condominio.
     *
     * <p>Es la razon de que la consulta de auditoria de un condominio no lo liste. Quien audita la
     * plataforma entera lo hace por {@code GET /api/v1/auditoria}, que llego con el issue
     * <strong>#20</strong> junto a la operacion de concederlo a otra cuenta.
     */
    @Override
    public @Nullable UUID condominiumId() {
        return null;
    }

    /**
     * El actor es {@code grantedBy}, que desde el issue <strong>#20</strong> es una cuenta de
     * verdad: la de quien concedio el rol.
     *
     * <p>Sigue siendo nulo en un caso, el primero de todos: ese no lo concede nadie, lo abre el
     * arranque de la plataforma cuando no hay ninguno, y entonces el actor es un nombre.
     */
    @Override
    public String actor() {
        return grantedBy == null ? ARRANQUE : grantedBy.toString();
    }

    @Override
    public String entity() {
        return "superadministrador";
    }

    @Override
    public UUID entityId() {
        return accountId;
    }
}
