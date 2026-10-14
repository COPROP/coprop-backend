package bo.coprop.identity;

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
public record PlatformAdminGranted(UUID accountId, @Nullable UUID grantedBy, Instant occurredAt) {}
