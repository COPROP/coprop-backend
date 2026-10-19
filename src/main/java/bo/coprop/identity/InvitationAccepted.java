package bo.coprop.identity;

import bo.coprop.shared.AuditableEvent;
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
        UUID condominiumId, UUID invitationId, UUID accountId, UUID membershipId, String actor, Instant occurredAt)
        implements AuditableEvent {

    @Override
    public String action() {
        return "INVITATION_ACCEPTED";
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
     * Lleva la membresia que nacio.
     *
     * <p>Es lo que ata las dos filas de la bitacora --esta y la de {@code MEMBERSHIP_GRANTED}-- y
     * lo que permite responder por que esa persona esta dentro: porque acepto esta invitacion.
     */
    @Override
    public java.util.Map<String, String> newValues() {
        return java.util.Map.of("membershipId", membershipId.toString(), "accountId", accountId.toString());
    }
}
