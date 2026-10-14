package bo.coprop.identity;

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
        UUID condominiumId, UUID invitationId, UUID accountId, boolean porReenvio, String actor, Instant occurredAt) {}
