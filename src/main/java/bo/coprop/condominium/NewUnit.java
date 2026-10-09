package bo.coprop.condominium;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Una unidad por dar de alta.
 *
 * <p>El condominio no viaja aqui: va en la ruta. Asi no puede haber discrepancia entre el
 * condominio de la URL y el del cuerpo, que es la clase de incoherencia que termina creando una
 * unidad en el condominio equivocado.
 *
 * @param code identificador visible, el que la gente usa: "302", "Casa 7"
 * @param type departamento, casa, local, parqueo o deposito
 * @param aliquot porcentaje de copropiedad, con el que se reparten las expensas
 * @param areaM2 metros cuadrados; opcional, porque de un parqueo no siempre se sabe
 * @param blockId torre o bloque al que pertenece; opcional
 * @param floorId piso; opcional, y si viene tiene que ser de ese bloque
 */
public record NewUnit(
        @NotBlank @Size(max = 32) String code,
        @NotNull UnitType type,
        // Hasta 6 decimales: en un edificio de 200 unidades, redondear la alicuota a dos decimales
        // deja varios bolivianos sin repartir cada mes.
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") @Digits(integer = 3, fraction = 6) BigDecimal aliquot,
        @Nullable @Positive @Digits(integer = 8, fraction = 2) BigDecimal areaM2,
        @Nullable UUID blockId,
        @Nullable UUID floorId) {}
