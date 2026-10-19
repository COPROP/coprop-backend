package bo.coprop.identity;

import bo.coprop.shared.AuditableEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Se levanto la suspension de una membresia. Issue <strong>#19</strong>.
 *
 * <p>Vuelve a dar acceso en la siguiente peticion, sin que la persona tenga que hacer nada: los
 * roles se resuelven en cada request y nunca estuvieron en su token.
 *
 * <p>Es una accion distinta de la suspension y no el mismo evento con una bandera, por lo mismo que
 * un reenvio de invitacion es distinto de una emision: quien lee la bitacora busca "a quien se
 * suspendio" o "a quien se le devolvio el acceso", y con una bandera dentro del JSON eso no se
 * filtra.
 */
public record MembershipReinstated(
        UUID condominiumId, UUID membershipId, UUID accountId, String motivo, String actor, Instant occurredAt)
        implements AuditableEvent {

    @Override
    public String action() {
        return "MEMBERSHIP_REACTIVATED";
    }

    @Override
    public String entity() {
        return "membresia";
    }

    @Override
    public UUID entityId() {
        return membershipId;
    }

    @Override
    public Map<String, String> oldValues() {
        return Map.of("status", MembershipStatus.SUSPENDED.name());
    }

    @Override
    public Map<String, String> newValues() {
        return Map.of("status", MembershipStatus.ACTIVE.name(), "motivo", motivo);
    }
}
