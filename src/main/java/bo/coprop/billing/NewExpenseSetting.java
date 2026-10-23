package bo.coprop.billing;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * La configuracion de expensas de un concepto, por registrar. Issue #24.
 *
 * <p>No lleva vigencia: la vigencia la pone el sistema al registrarla --cierra la anterior y abre
 * esta-- y aceptar una fecha de parte del cliente permitiria abrir una configuracion con fecha
 * pasada, que reescribiria la historia con la que ya se emitio.
 *
 * <p>La moneda tampoco: se copia del condominio, igual que en {@link NewPaymentObligation}.
 *
 * @param conceptId el concepto que se emite con esta configuracion. Tiene que ser de tipo
 *     {@link ChargeConceptType#DUES}: el agua se calcula con una tarifa (#25) y la mora con una
 *     politica (#27), cada una con su propia tabla
 * @param method si el monto es por unidad o un total a repartir
 * @param amount con dos decimales y mayor que cero. Lo que significa lo dice {@code method}
 */
public record NewExpenseSetting(
        @NotNull UUID conceptId,
        @NotNull ExpenseMethod method,
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount) {}
