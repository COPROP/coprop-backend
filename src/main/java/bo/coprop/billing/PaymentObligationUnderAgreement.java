package bo.coprop.billing;

import bo.coprop.shared.AuditableEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Una obligacion emitida entro en un convenio de pago.
 *
 * <p>Se audita porque cambia como se cobra una deuda viva: deja de tratarse como un cobro corriente
 * --y, cuando el #27 entre, deja de ser una candidata normal a mora-- aunque siga debiendose. Quien
 * mire la bitacora de una unidad tiene que ver ese cambio y quien lo hizo.
 *
 * <p>El convenio en si --sus cuotas, su interes, que pasa si se incumple-- no vive en este modulo
 * todavia. Este evento es el borde: lo que {@code billing} sabe es que esta obligacion ya no se cobra
 * por la via normal.
 */
public record PaymentObligationUnderAgreement(
        UUID condominiumId, UUID obligationId, UUID unitId, String number, String actor, Instant occurredAt)
        implements AuditableEvent {

    @Override
    public String action() {
        return "PAYMENT_OBLIGATION_UNDER_AGREEMENT";
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
        return Map.of("status", PaymentObligationStatus.ISSUED.name());
    }

    @Override
    public Map<String, String> newValues() {
        return Map.of("status", PaymentObligationStatus.IN_AGREEMENT.name(), "number", number);
    }
}
