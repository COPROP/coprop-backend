package bo.coprop.billing;

import bo.coprop.shared.AuditableEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Una obligacion de pago quedo anulada.
 *
 * <p>Se audita con su motivo porque es la accion con mas filo de todo el modulo: anular una deuda es
 * decirle a un propietario que no debe lo que decia que debia, y el analisis 12.2 pide que la
 * bitacora pueda responder quien la quito y por que. <strong>El motivo va aqui y no en la fila</strong>,
 * igual que en la baja de una membresia (#19): la fila dice que esta anulada, la bitacora por que.
 *
 * @param previousStatus de donde venia. Importa: anular un borrador que nadie vio no es lo mismo que
 *     anular una deuda que el propietario ya tenia en su estado de cuenta
 * @param reason por que se anulo, como lo escribio quien lo hizo
 */
public record PaymentObligationCancelled(
        UUID condominiumId,
        UUID obligationId,
        UUID unitId,
        String number,
        PaymentObligationStatus previousStatus,
        String reason,
        String actor,
        Instant occurredAt)
        implements AuditableEvent {

    @Override
    public String action() {
        return "PAYMENT_OBLIGATION_CANCELLED";
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
    public Map<String, String> oldValues() {
        return Map.of("status", previousStatus.name());
    }

    @Override
    public Map<String, String> newValues() {
        return Map.of("status", PaymentObligationStatus.CANCELLED.name(), "number", number, "reason", reason);
    }
}
