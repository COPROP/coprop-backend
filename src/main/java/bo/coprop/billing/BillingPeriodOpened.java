package bo.coprop.billing;

import bo.coprop.shared.AuditableEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Se abrio el periodo de facturacion de un mes.
 *
 * <p>Se audita porque abrir un periodo es lo que habilita cobrar ese mes: de aqui en adelante
 * cuelgan las lecturas de agua y, cuando el #24 emita, las obligaciones de todo el edificio. La
 * bitacora del analisis 12.2 tiene que poder decir quien preparo el mes y cuando.
 *
 * @param periodId el periodo recien abierto
 * @param year su año
 * @param month su mes
 * @param actor quien lo abrio
 * @param occurredAt cuando
 */
public record BillingPeriodOpened(
        UUID condominiumId, UUID periodId, int year, int month, String actor, Instant occurredAt)
        implements AuditableEvent {

    @Override
    public String action() {
        return "BILLING_PERIOD_OPENED";
    }

    @Override
    public String entity() {
        return "periodo-de-facturacion";
    }

    @Override
    public UUID entityId() {
        return periodId;
    }

    /**
     * El mes y el estado con el que nace.
     *
     * <p>El estado se escribe aunque hoy sea siempre {@code DRAFT}: es lo que hace legible la fila
     * siguiente, la del cambio de estado, sin tener que saberse la maquina de memoria.
     *
     * <p>{@code oldValues()} se queda en el nulo por defecto: no habia nada antes, que es el caso de
     * todo alta.
     */
    @Override
    public Map<String, String> newValues() {
        return Map.of(
                "year",
                String.valueOf(year),
                "month",
                String.valueOf(month),
                "status",
                BillingPeriodStatus.DRAFT.name());
    }
}
