package bo.coprop.billing;

import bo.coprop.shared.AuditableEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Una obligacion de pago quedo cubierta.
 *
 * <p>El analisis lo llama {@code ObligationPaid} y es el evento al que <strong>reacciona el modulo
 * que origino la deuda</strong> (5.1): el agua marca su lectura como cobrada, la reserva confirma la
 * reserva. Aqui se llama como la entidad, igual que los demas eventos del proyecto.
 *
 * <p>Lo publica {@code billing} al cambiar el estado, no {@code payments}: el que sabe que una deuda
 * quedo cubierta es el dueño de la deuda. {@code payments} publica que entro dinero, que es otra cosa
 * --un pago puede cubrir varias obligaciones y dejar saldo a favor--.
 *
 * @param paidAmount el monto que tenia la obligacion. No hay pagos parciales en el MVP, asi que es
 *     tambien lo que se cobro
 */
public record PaymentObligationPaid(
        UUID condominiumId,
        UUID obligationId,
        UUID unitId,
        String number,
        BigDecimal paidAmount,
        String currency,
        String actor,
        Instant occurredAt)
        implements AuditableEvent {

    @Override
    public String action() {
        return "PAYMENT_OBLIGATION_PAID";
    }

    @Override
    public String entity() {
        return "obligacion-de-pago";
    }

    @Override
    public UUID entityId() {
        return obligationId;
    }

    @Override
    public Map<String, String> newValues() {
        return Map.of(
                "status",
                PaymentObligationStatus.PAID.name(),
                "number",
                number,
                "amount",
                paidAmount.toPlainString(),
                "currency",
                currency);
    }
}
