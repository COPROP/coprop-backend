package bo.coprop.billing;

import bo.coprop.shared.AuditableEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Cambio la configuracion de expensas de un concepto. Issue #24.
 *
 * <p>Se audita porque decide cuanto se le cobra a todo el edificio el mes que viene, y es
 * exactamente la clase de dato sobre el que alguien pregunta despues "quien puso este monto". El
 * alta del primer valor tambien: no hay un "antes", y por eso {@link #oldValues} es nulo ahi.
 *
 * @param previousMethod y {@code previousAmount}, nulos cuando es la primera configuracion de ese
 *     concepto
 */
public record ExpenseSettingChanged(
        UUID condominiumId,
        UUID settingId,
        UUID conceptId,
        @Nullable ExpenseMethod previousMethod,
        @Nullable BigDecimal previousAmount,
        ExpenseMethod method,
        BigDecimal amount,
        String currency,
        String actor,
        Instant occurredAt)
        implements AuditableEvent {

    @Override
    public String action() {
        return "EXPENSE_SETTING_CHANGED";
    }

    @Override
    public String entity() {
        return "configuracion-de-expensas";
    }

    @Override
    public UUID entityId() {
        return settingId;
    }

    /** El concepto va aqui y no en los valores: es de quien es la configuracion, no un valor suyo. */
    @Override
    public @Nullable Map<String, String> oldValues() {
        if (previousMethod == null || previousAmount == null) {
            return null;
        }
        return Map.of("method", previousMethod.name(), "amount", previousAmount.toPlainString());
    }

    @Override
    public Map<String, String> newValues() {
        return Map.of(
                "conceptId",
                conceptId.toString(),
                "method",
                method.name(),
                "amount",
                amount.toPlainString(),
                "currency",
                currency);
    }
}
