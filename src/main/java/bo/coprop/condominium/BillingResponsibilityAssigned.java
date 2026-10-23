package bo.coprop.condominium;

import bo.coprop.shared.AuditableEvent;
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
 * @param byDefault si la abrio el sistema por ser esa persona la propietaria unica de la unidad
 *     (issue #29) en vez de haberla pedido alguien. Va en la bitacora porque es la respuesta a "¿por
 *     que le cobran a esta persona?": o lo decidio alguien, o lo decidio la regla de 13.3, y son dos
 *     respuestas distintas
 */
public record BillingResponsibilityAssigned(
        UUID condominiumId,
        UUID unitId,
        UUID personId,
        @Nullable UUID conceptId,
        boolean byDefault,
        String actor,
        Instant occurredAt)
        implements AuditableEvent {

    @Override
    public String action() {
        return "BILLING_RESPONSIBILITY_ASSIGNED";
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
     * El concepto entra solo cuando lo hay, por lo mismo que en
     * {@link BillingResponsibilityEnded}; el origen entra siempre.
     */
    @Override
    public Map<String, String> newValues() {
        String origin = byDefault ? "DEFAULT_OWNER" : "EXPLICIT";
        return conceptId == null
                ? Map.of("personId", personId.toString(), "origin", origin)
                : Map.of("personId", personId.toString(), "conceptId", conceptId.toString(), "origin", origin);
    }
}
