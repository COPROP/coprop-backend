package bo.coprop.identity;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se devuelve de una membresia: el vinculo de una cuenta con un condominio y sus roles.
 *
 * <p>Los roles vienen como conjunto porque una membresia puede tener varios: un propietario que
 * tambien esta en el directorio (Seguridad 2).
 *
 * <p>La vigencia viaja desde el issue <strong>#19</strong>, que es el que trajo la baja. Sin ella,
 * el padron con las membresias cerradas --que es como el administrador ve quien estuvo y ya no
 * esta-- no podria distinguir una vigente de una terminada, porque la baja no es un estado sino una
 * fecha de cierre.
 *
 * @param validTo nulo mientras la membresia este vigente. Con valor, la persona esta dada de baja
 *     de ese condominio desde ese instante
 */
public record MembershipView(
        UUID id,
        UUID accountId,
        UUID condominiumId,
        MembershipStatus status,
        Set<MembershipRole> roles,
        Instant validFrom,
        @Nullable Instant validTo) {

    /**
     * Si esta membresia da acceso hoy.
     *
     * <p>Son las dos condiciones y hace falta preguntar por las dos: una membresia suspendida sigue
     * abierta --suspender no cierra la fila, que es justo lo que la distingue de una baja-- y una
     * cerrada conserva el estado que tenia cuando se cerro, porque cerrar no lo toca.
     */
    public boolean daAcceso() {
        return validTo == null && status == MembershipStatus.ACTIVE;
    }
}
