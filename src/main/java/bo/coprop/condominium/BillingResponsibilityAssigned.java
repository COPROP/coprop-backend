package bo.coprop.condominium;

import bo.coprop.shared.EventoAuditable;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Se asigno quien responde por los cobros de una unidad.
 *
 * <p>De todas las relaciones es la que mas falta hace auditar: cambia a quien se le reclama el
 * dinero. En la bitacora desde el issue <strong>#19</strong>, junto con su cierre.
 *
 * @param conceptId nulo = por todos los conceptos
 */
public record BillingResponsibilityAssigned(
        UUID condominiumId, UUID unitId, UUID personId, @Nullable UUID conceptId, String actor, Instant occurredAt)
        implements EventoAuditable {

    @Override
    public String accion() {
        return "RESPONSABILIDAD_DE_PAGO_ASIGNADA";
    }

    @Override
    public String entidad() {
        return "responsabilidad-de-pago";
    }

    /** La unidad, por lo mismo que en {@link OwnershipTransferred}: la relacion lleva vigencia. */
    @Override
    public UUID entidadId() {
        return unitId;
    }

    /** El concepto entra solo cuando lo hay, por lo mismo que en {@link BillingResponsibilityEnded}. */
    @Override
    public Map<String, String> despues() {
        return conceptId == null
                ? Map.of("personId", personId.toString())
                : Map.of("personId", personId.toString(), "conceptId", conceptId.toString());
    }
}
