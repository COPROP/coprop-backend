package bo.coprop.condominium;

import bo.coprop.shared.AuditableEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Alguien dejo de responder por los cobros de una unidad.
 *
 * <p>De las tres relaciones es la que mas falta hace tener en la bitacora, por lo mismo que su alta:
 * cambia a quien se le reclama el dinero. Las obligaciones ya emitidas no se mueven --siguen
 * apuntando a quien respondia cuando se emitieron-- y eso es lo que hace que el criterio del issue
 * #19 se sostenga: lo historico se conserva.
 *
 * @param conceptId nulo = respondia por todos los conceptos de la unidad
 */
public record BillingResponsibilityEnded(
        UUID condominiumId, UUID unitId, UUID personId, @Nullable UUID conceptId, String actor, Instant occurredAt)
        implements AuditableEvent {

    @Override
    public String action() {
        return "BILLING_RESPONSIBILITY_ENDED";
    }

    @Override
    public String entity() {
        return "responsabilidad-de-pago";
    }

    /** La unidad, por lo mismo que en {@link OwnershipTransferred}: la relacion lleva vigencia. */
    @Override
    public UUID entityId() {
        return unitId;
    }

    /**
     * El concepto entra solo cuando lo hay.
     *
     * <p>{@code Map.of} no admite valores nulos, y un {@code "conceptId": "null"} de texto seria
     * peor que su ausencia: dentro de un año nadie sabria si fue un nulo o una cadena.
     */
    @Override
    public Map<String, String> oldValues() {
        return conceptId == null
                ? Map.of("personId", personId.toString())
                : Map.of("personId", personId.toString(), "conceptId", conceptId.toString());
    }
}
