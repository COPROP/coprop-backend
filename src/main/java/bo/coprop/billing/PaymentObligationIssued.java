package bo.coprop.billing;

import bo.coprop.shared.AuditableEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Una obligacion de pago salio a cobro.
 *
 * <p><strong>Es el evento que el {@code package-info} de este modulo promete publicar</strong>, y el
 * que esperan los demas: las notificaciones (#50) avisan al propietario, y el modulo que origino la
 * deuda sabe asi que su cargo ya esta en la calle. Por eso es un record publico de {@code billing} y
 * no algo interno.
 *
 * <p>Lleva el monto y el numero para que quien escuche no tenga que volver a preguntar: un oyente
 * que consulta la obligacion para redactar un correo acopla la notificacion al esquema de cobros.
 *
 * @param number el nombre legible, que es lo que vera el propietario
 * @param dueDate cuando vence, que es el otro dato de un aviso
 */
public record PaymentObligationIssued(
        UUID condominiumId,
        UUID obligationId,
        UUID unitId,
        UUID responsiblePersonId,
        String number,
        BigDecimal amount,
        String currency,
        LocalDate dueDate,
        String actor,
        Instant occurredAt)
        implements AuditableEvent {

    @Override
    public String action() {
        return "PAYMENT_OBLIGATION_ISSUED";
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
        return Map.of("status", PaymentObligationStatus.DRAFT.name());
    }

    /**
     * El estado nuevo y lo que se le cobra.
     *
     * <p>El monto entra en la bitacora aunque la fila siga en la tabla: emitir es el momento en que
     * una deuda queda fijada, y si algun dia se puede corregir, la bitacora tiene que poder decir
     * con cuanto salio.
     */
    @Override
    public Map<String, String> newValues() {
        return Map.of(
                "status",
                PaymentObligationStatus.ISSUED.name(),
                "number",
                number,
                "amount",
                amount.toPlainString(),
                "currency",
                currency,
                "dueDate",
                dueDate.toString());
    }
}
