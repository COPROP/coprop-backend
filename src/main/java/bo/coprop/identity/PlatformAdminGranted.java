package bo.coprop.identity;

import bo.coprop.shared.EventoAuditable;
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
        implements EventoAuditable {

    /** Lo que se escribe como actor cuando no lo concedio nadie: lo abrio el arranque. */
    static final String ARRANQUE = "arranque";

    @Override
    public String accion() {
        return "PLATAFORMA_CONCEDIDA";
    }

    /**
     * Nulo: el alcance de este rol es la plataforma y no cuelga de ningun condominio.
     *
     * <p>Es la razon de que la consulta de auditoria de un condominio no lo liste. Quien audita la
     * plataforma entera lo hace por la ruta global, que es del issue #20 cuando exista la operacion
     * de concederlo a otra cuenta.
     */
    @Override
    public @Nullable UUID condominiumId() {
        return null;
    }

    /**
     * Este evento no lleva actor como los demas, porque el rol se concede por SQL o en el arranque.
     * Cuando exista la operacion del issue #20, {@code grantedBy} sera el actor de verdad.
     */
    @Override
    public String actor() {
        return grantedBy == null ? ARRANQUE : grantedBy.toString();
    }

    @Override
    public String entidad() {
        return "superadministrador";
    }

    @Override
    public UUID entidadId() {
        return accountId;
    }
}
