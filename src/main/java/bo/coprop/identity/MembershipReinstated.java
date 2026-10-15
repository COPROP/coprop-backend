package bo.coprop.identity;

import bo.coprop.shared.EventoAuditable;
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
        implements EventoAuditable {

    @Override
    public String accion() {
        return "MEMBRESIA_REACTIVADA";
    }

    @Override
    public String entidad() {
        return "membresia";
    }

    @Override
    public UUID entidadId() {
        return membershipId;
    }

    @Override
    public Map<String, String> antes() {
        return Map.of("status", MembershipStatus.SUSPENDIDA.name());
    }

    @Override
    public Map<String, String> despues() {
        return Map.of("status", MembershipStatus.ACTIVA.name(), "motivo", motivo);
    }
}
