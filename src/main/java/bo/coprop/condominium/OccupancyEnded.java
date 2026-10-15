package bo.coprop.condominium;

import bo.coprop.shared.EventoAuditable;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Alguien dejo de ocupar una unidad: se mudo, o se le acabo el contrato.
 *
 * <p>Con esto pierde el acceso a esa unidad, porque la ocupacion vigente es una de las tres
 * relaciones que lo dan (Seguridad 2). Es la mitad manual de lo que Seguridad 6.2 describe como
 * automatico --"el inquilino deja de ver esa unidad al dia siguiente"--: la fecha de fin del
 * contrato como dato de la ocupacion no existe todavia, asi que hoy el cierre lo registra el
 * administrador.
 */
public record OccupancyEnded(
        UUID condominiumId, UUID unitId, UUID personId, OccupancyRole role, String actor, Instant occurredAt)
        implements EventoAuditable {

    @Override
    public String accion() {
        return "OCUPACION_TERMINADA";
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
    public Map<String, String> antes() {
        return Map.of("personId", personId.toString(), "role", role.name());
    }
}
