package bo.coprop.billing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Un periodo de facturacion por abrir.
 *
 * <p>El condominio no viaja aqui: va en la ruta, igual que en el alta de un concepto de cobro. El
 * estado tampoco, y no es un olvido: un periodo nace siempre en {@link BillingPeriodStatus#DRAFT} y
 * dejar que el cliente lo eligiera seria una forma de emitir sin emitir nada.
 *
 * <p><strong>El año y el mes son dos numeros, no una fecha.</strong> Un periodo es un mes entero, y
 * una fecha obligaria a elegir un dia que no significa nada. El mes se acota aqui entre 1 y 12 para
 * que un 13 salga como 422 con el campo señalado, que es lo que pide el ticket; la base lo acota
 * otra vez porque el dominio tambien se puede llamar desde un worker.
 *
 * @param year el año del periodo
 * @param month el mes, de 1 a 12
 */
public record NewBillingPeriod(@Min(2000) @Max(2100) int year, @Min(1) @Max(12) int month) {}
