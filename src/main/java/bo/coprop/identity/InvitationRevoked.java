package bo.coprop.identity;

import bo.coprop.shared.AuditableEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Se revoco una invitacion y su enlace dejo de servir.
 *
 * <p>Tercer criterio del issue #18, para la bitacora del #21. Se publica tanto cuando el
 * administrador la revoca a mano como cuando un reenvio revoca la anterior: en los dos casos un
 * enlace que estaba circulando deja de valer, y eso es lo que hay que poder reconstruir despues.
 *
 * @param porReenvio si la revoco un reenvio en vez de una decision de revocarla
 */
public record InvitationRevoked(
        UUID condominiumId, UUID invitationId, UUID accountId, boolean porReenvio, String actor, Instant occurredAt)
        implements AuditableEvent {

    /**
     * Revocar a mano y revocar por un reenvio son dos acciones, por lo mismo que en
     * {@link InvitationIssued}: quien lee la bitacora busca una decision de cortar el acceso, y las
     * revocaciones de reenvio la enterrarian.
     */
    @Override
    public String action() {
        return porReenvio ? "INVITATION_REVOKED_BY_RESEND" : "INVITATION_REVOKED";
    }

    @Override
    public String entity() {
        return "invitacion";
    }

    @Override
    public UUID entityId() {
        return invitationId;
    }

    /**
     * Sin valores de despues: lo que cuenta es la accion y su fecha.
     *
     * <p>Un {@code revokedAt} aqui seria repetir {@code occurredAt}, que es la columna por la que la
     * bitacora se ordena y se filtra.
     */
    @Override
    public java.util.Map<String, String> oldValues() {
        return java.util.Map.of("accountId", accountId.toString());
    }
}
