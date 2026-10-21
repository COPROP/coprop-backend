package bo.coprop.billing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Un concepto de cobro por dar de alta.
 *
 * <p>El condominio no viaja aqui: va en la ruta, igual que en el alta de una unidad. Asi no puede
 * haber discrepancia entre el condominio de la URL y el del cuerpo.
 *
 * <p>No trae monto ni formula. El concepto dice <strong>que</strong> se cobra; cuanto lo deciden el
 * issue #24 para las expensas, el #25 para el agua y el #27 para la mora, cada uno con su propia
 * configuracion con vigencia. Un monto aqui seria un dato que cambia y que no se puede cambiar sin
 * reescribir lo ya emitido.
 *
 * @param code el codigo con el que el administrador lo nombra: "EXP", "AGUA", "MORA-EXP". Unico
 *     dentro del condominio
 * @param name como se lee en un estado de cuenta: "Expensas ordinarias"
 * @param type que clase de cobro es
 * @param paymentGroup con que otros conceptos comparte QR. Se pide siempre y no se deduce del tipo:
 *     el porque esta en {@link PaymentGroup}
 */
public record NewChargeConcept(
        @NotBlank @Size(max = 32) String code,
        @NotBlank @Size(max = 80) String name,
        @NotNull ChargeConceptType type,
        @NotNull PaymentGroup paymentGroup) {}
