package bo.coprop.billing;

import bo.coprop.shared.AuditableEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Un periodo de facturacion cambio de estado: se emitio o se cerro.
 *
 * <p><strong>Un solo evento para las dos transiciones, con el nombre de accion elegido segun el
 * estado nuevo.</strong> Es el mismo recurso que ya usan la invitacion emitida o reenviada y la
 * revocada por reenvio: lo que cambia entre emitir y cerrar es el estado destino, no la forma del
 * hecho, y dos records identicos solo se diferenciarian en una cadena. Quien lea la bitacora ve
 * {@code BILLING_PERIOD_ISSUED} o {@code BILLING_PERIOD_CLOSED}, que es lo que importa.
 *
 * <p>Lleva las dos mitades --{@link #oldValues()} y {@link #newValues()}-- porque aqui si habia algo
 * antes: es un cambio, no un alta.
 *
 * @param from el estado del que venia
 * @param to el estado al que paso
 */
public record BillingPeriodStatusChanged(
        UUID condominiumId,
        UUID periodId,
        int year,
        int month,
        BillingPeriodStatus from,
        BillingPeriodStatus to,
        String actor,
        Instant occurredAt)
        implements AuditableEvent {

    /** {@code BILLING_PERIOD_ISSUED} o {@code BILLING_PERIOD_CLOSED}, segun a donde paso. */
    @Override
    public String action() {
        return switch (to) {
            case ISSUED -> "BILLING_PERIOD_ISSUED";
            case CLOSED -> "BILLING_PERIOD_CLOSED";
            // DRAFT no es destino de ninguna transicion: el periodo nace ahi y no vuelve. La rama
            // existe porque el switch es exhaustivo, y si algun dia hubiera un estado nuevo sin
            // nombre de accion, esto falla en el acto en vez de escribir una accion inventada.
            case DRAFT -> throw new IllegalStateException("Un periodo no vuelve a DRAFT.");
        };
    }

    @Override
    public String entity() {
        return "periodo-de-facturacion";
    }

    @Override
    public UUID entityId() {
        return periodId;
    }

    @Override
    public Map<String, String> oldValues() {
        return Map.of("status", from.name());
    }

    /**
     * El estado nuevo y el mes al que pertenece.
     *
     * <p>El año y el mes se repiten en cada fila aunque no cambien: la bitacora se lee sola, y una
     * fila que dice "se emitio" sin decir que mes obliga a ir a buscar la fila del alta.
     */
    @Override
    public Map<String, String> newValues() {
        return Map.of("status", to.name(), "year", String.valueOf(year), "month", String.valueOf(month));
    }
}
