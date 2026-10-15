package bo.coprop.identity;

import bo.coprop.shared.EventoAuditable;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Se suspendio una membresia. Issue <strong>#19</strong>.
 *
 * <p>La suspension es temporal y <strong>no cierra la fila</strong>: eso es lo que la distingue de
 * la baja. La persona sigue siendo miembro del condominio, con sus roles intactos, y mientras este
 * suspendida el filtro de aislamiento le responde lo mismo que a quien no pertenece. Para volver,
 * {@link MembershipReinstated}.
 *
 * <p>El motivo queda aqui y solo aqui, por lo mismo que en {@link MembershipRevoked}.
 *
 * @param sesionesCerradas si se le cerraron las sesiones, que solo pasa cuando esta era su ultima
 *     membresia activa y no administra la plataforma
 */
public record MembershipSuspended(
        UUID condominiumId,
        UUID membershipId,
        UUID accountId,
        String motivo,
        boolean sesionesCerradas,
        String actor,
        Instant occurredAt)
        implements EventoAuditable {

    @Override
    public String accion() {
        return "MEMBRESIA_SUSPENDIDA";
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
        return Map.of("status", MembershipStatus.ACTIVA.name());
    }

    @Override
    public Map<String, String> despues() {
        return Map.of(
                "status",
                MembershipStatus.SUSPENDIDA.name(),
                "motivo",
                motivo,
                "sesionesCerradas",
                String.valueOf(sesionesCerradas));
    }
}
