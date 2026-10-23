package bo.coprop.billing;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se devuelve de una obligacion de pago.
 *
 * @param number el nombre con el que una persona la busca: {@code EXP-2026-09-A-302}
 * @param periodId nulo cuando la deuda no es de un mes, como una multa o una reserva
 * @param amount con su {@code currency} al lado, que el analisis 12.2 exige en cada monto.
 *     <strong>Viaja como cadena en el JSON y no como numero</strong>: lo fija ARCHITECTURE.md, y la
 *     razon es el cliente --el analisis 12.2 prohibe coma flotante para el dinero, y un numero JSON
 *     en JavaScript es exactamente eso, asi que el redondeo que el esquema evita volveria en la
 *     pantalla--
 * @param issuedAt nula mientras es un borrador
 * @param overdue derivado de {@code dueDate} y del estado, no guardado: una vencida sigue siendo
 *     {@code ISSUED} y sigue cobrable
 * @param parentObligationId la obligacion que genero esta, cuando es mora. Nulo en las demas
 * @param sourceType la clase de cosa que la origino. Nulos los dos hasta el #24
 */
public record PaymentObligationView(
        UUID id,
        UUID condominiumId,
        UUID unitId,
        UUID responsiblePersonId,
        UUID conceptId,
        @Nullable UUID periodId,
        String number,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        @Nullable Instant issuedAt,
        LocalDate dueDate,
        PaymentObligationStatus status,
        boolean overdue,
        @Nullable UUID parentObligationId,
        @Nullable String sourceType,
        @Nullable UUID sourceId) {}
