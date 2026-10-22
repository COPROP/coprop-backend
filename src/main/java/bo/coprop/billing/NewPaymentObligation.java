package bo.coprop.billing;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Una obligacion de pago por crear. Nace siempre en {@link PaymentObligationStatus#DRAFT}.
 *
 * <p>El condominio no viaja aqui: va en el primer argumento de {@link PaymentObligations}, igual que
 * en el resto del modulo. <strong>La moneda tampoco</strong>: se copia del condominio al crear la
 * obligacion, asi que no hay forma de guardar un monto con una moneda que su edificio no usa.
 *
 * <p><strong>El numero legible no se pide, se compone</strong>: el porque esta en
 * {@link PaymentObligations#register}.
 *
 * <p>No lo llena una persona por HTTP --no hay ruta-- sino el calculo de expensas del #24, el del
 * agua del #25 y la politica de mora del #27.
 *
 * @param amount el monto, con dos decimales y mayor que cero. Un cargo de cero no es una deuda: lo
 *     que corresponde ahi es no crear la obligacion
 * @param dueDate el dia en que vence, en la zona del condominio. Lo calcula quien emite, a partir
 *     del {@code dueDay} de la configuracion
 * @param periodId el mes al que pertenece, o nulo si la deuda no es de un mes
 * @param parentObligationId la obligacion que la genero, cuando esto es mora (analisis 5.2)
 * @param sourceType y {@code sourceId}, el origen por referencia del analisis 5.1. Van juntos o no
 *     van: un identificador sin tipo no se puede resolver
 */
public record NewPaymentObligation(
        @NotNull UUID unitId,
        @NotNull UUID responsiblePersonId,
        @NotNull UUID conceptId,
        @Nullable UUID periodId,
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @NotNull LocalDate dueDate,
        @Nullable UUID parentObligationId,
        @Nullable @Size(max = 30) String sourceType,
        @Nullable UUID sourceId) {}
