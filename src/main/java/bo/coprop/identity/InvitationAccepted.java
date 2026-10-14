package bo.coprop.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Alguien acepto su invitacion y con eso nacio su membresia.
 *
 * <p>Va aparte de {@link MembershipGranted}, que tambien se publica en el mismo acto, porque no
 * dicen lo mismo: aquel dice que esa cuenta quedo vinculada al condominio, y este que la via fue
 * una invitacion y cual. La bitacora del issue #21 necesita las dos cosas, y una membresia tambien
 * puede nacer sin invitacion --la del primer administrador, que concede el superadministrador--.
 *
 * <p>El actor es la propia cuenta invitada: aceptar es un acto de quien entra, no de quien invito.
 */
public record InvitationAccepted(
        UUID condominiumId, UUID invitationId, UUID accountId, UUID membershipId, String actor, Instant occurredAt) {}
