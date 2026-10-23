package bo.coprop.billing.internal;

import bo.coprop.shared.BusinessRuleViolated;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * El numero legible de una obligacion de pago (analisis 5.2).
 *
 * <p>Se compone del codigo del concepto, el año y el mes, y el codigo de la unidad:
 * {@code EXP-2026-09-A-302}. <strong>El mes sale del periodo cuando hay periodo y del vencimiento
 * cuando no</strong>: una multa no pertenece a ningun mes, pero vence en uno, y es el dato con el
 * que una persona la va a buscar.
 *
 * <p>No es una secuencia a proposito: un {@code OBL-000123} no dice nada al leerlo, y el analisis
 * escribe el ejemplo compuesto. El precio es que puede repetirse --dos multas del mismo concepto
 * sobre la misma unidad en el mismo mes-- y ahi lleva un correlativo detras.
 *
 * <p><strong>Vive aparte porque lo usan dos servicios</strong>: el alta de una obligacion sola
 * (#23) y la emision de expensas en lote (#24). Duplicarlo dejaria dos formas de nombrar la misma
 * cosa, y la que se quedara atras produciria numeros que no son los que el resto del sistema espera.
 */
final class ObligationNumbers {

    /**
     * Cuantos correlativos se prueban antes de rendirse.
     *
     * <p>Veinte obligaciones del mismo concepto sobre la misma unidad en el mismo mes no es un caso
     * real; es un bucle que se fue. Mejor fallar que girar.
     */
    private static final int MAXIMO_DE_CORRELATIVOS = 20;

    private final PaymentObligationRepository obligations;

    ObligationNumbers(PaymentObligationRepository obligations) {
        this.obligations = obligations;
    }

    /**
     * Un numero libre en el condominio para esa combinacion.
     *
     * @param period el periodo al que pertenece, o nulo si la deuda no es de un mes
     * @throws BusinessRuleViolated si los veinte correlativos estan tomados
     */
    String free(
            UUID condominiumId,
            String conceptCode,
            @Nullable BillingPeriod period,
            LocalDate dueDate,
            String unitCode) {
        int year = period != null ? period.getYear() : dueDate.getYear();
        int month = period != null ? period.getMonth() : dueDate.getMonthValue();
        return free(condominiumId, conceptCode, year, month, unitCode);
    }

    /**
     * El mismo numero, pero cuando el mes se sabe ya.
     *
     * <p>La usa la emision de expensas del #24, que trabaja sobre un periodo y no necesita deducir
     * el mes de un vencimiento.
     */
    String free(UUID condominiumId, String conceptCode, int year, int month, String unitCode) {
        String base = "%s-%d-%02d-%s".formatted(conceptCode, year, month, unitCode);

        if (!obligations.existsByCondominiumIdAndNumber(condominiumId, base)) {
            return base;
        }
        for (int correlativo = 2; correlativo <= MAXIMO_DE_CORRELATIVOS; correlativo++) {
            String candidato = base + "-" + correlativo;
            if (!obligations.existsByCondominiumIdAndNumber(condominiumId, candidato)) {
                return candidato;
            }
        }
        throw new BusinessRuleViolated(
                "Ya hay %d obligaciones con el numero %s en este condominio.".formatted(MAXIMO_DE_CORRELATIVOS, base));
    }
}
