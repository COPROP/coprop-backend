package bo.coprop.condominium;

import bo.coprop.shared.EventoAuditable;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Se registro que alguien ocupa una unidad.
 *
 * <p>En la bitacora desde el issue <strong>#19</strong>, por lo mismo que {@link OwnershipGranted}:
 * su cierre se audita, y registrar el fin sin el principio deja la historia a medias.
 */
public record OccupancyGranted(
        UUID condominiumId, UUID unitId, UUID personId, OccupancyRole role, String actor, Instant occurredAt)
        implements EventoAuditable {

    @Override
    public String accion() {
        return "OCUPACION_REGISTRADA";
    }

    @Override
    public String entidad() {
        return "ocupacion";
    }

    /** La unidad, por lo mismo que en {@link OwnershipTransferred}: la relacion lleva vigencia. */
    @Override
    public UUID entidadId() {
        return unitId;
    }

    @Override
    public Map<String, String> despues() {
        return Map.of("personId", personId.toString(), "role", role.name());
    }
}
