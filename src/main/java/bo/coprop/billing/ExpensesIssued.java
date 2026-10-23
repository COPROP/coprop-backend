package bo.coprop.billing;

import bo.coprop.shared.AuditableEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Las expensas de un concepto salieron a cobro en un mes. Issue #24.
 *
 * <p><strong>Es el resumen de la emision, y convive con los {@link PaymentObligationIssued} de cada
 * obligacion.</strong> Los dos hacen falta y no son lo mismo: el de la obligacion es lo que
 * escuchan las notificaciones del #50 para avisar a cada propietario, y tiene que haber uno por
 * deuda; este dice que una persona emitio el mes, cuantas obligaciones y por cuanto, que es lo que
 * alguien busca cuando pregunta "quien emitio septiembre". Reconstruir eso contando cuarenta filas
 * de bitacora con la misma hora no es lo mismo que tenerlo escrito.
 *
 * @param obligations cuantas se emitieron
 * @param total la suma de todas, que es lo que tiene que cuadrar con lo configurado
 */
public record ExpensesIssued(
        UUID condominiumId,
        UUID periodId,
        UUID conceptId,
        UUID settingId,
        int year,
        int month,
        int obligations,
        BigDecimal total,
        String currency,
        String actor,
        Instant occurredAt)
        implements AuditableEvent {

    @Override
    public String action() {
        return "EXPENSES_ISSUED";
    }

    /**
     * El periodo, no el concepto.
     *
     * <p>Es lo que una persona nombra --"la emision de septiembre"-- y lo que hace que la historia
     * del mes salga junta al filtrar la bitacora por entidad. El concepto distingue las dos filas
     * cuando un condominio emite expensas y fondo de reserva por separado, y va en los valores.
     */
    @Override
    public String entity() {
        return "periodo-de-facturacion";
    }

    @Override
    public UUID entityId() {
        return periodId;
    }

    @Override
    public Map<String, String> newValues() {
        return Map.of(
                "conceptId",
                conceptId.toString(),
                "settingId",
                settingId.toString(),
                "period",
                "%d-%02d".formatted(year, month),
                "obligations",
                String.valueOf(obligations),
                "total",
                total.toPlainString(),
                "currency",
                currency);
    }
}
