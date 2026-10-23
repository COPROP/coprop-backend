package bo.coprop.billing;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se devuelve de una configuracion de expensas.
 *
 * @param amount con su {@code currency} al lado, que el analisis 12.2 exige en cada monto. Su
 *     significado lo da {@code method}, y viaja como cadena por lo mismo que en
 *     {@link PaymentObligationView}
 * @param validTo nulo en la vigente. Con valor, es historia y no se vuelve a tocar
 */
public record ExpenseSettingView(
        UUID id,
        UUID condominiumId,
        UUID conceptId,
        ExpenseMethod method,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        Instant validFrom,
        @Nullable Instant validTo) {}
