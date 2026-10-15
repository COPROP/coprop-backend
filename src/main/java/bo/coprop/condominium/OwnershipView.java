package bo.coprop.condominium;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Una titularidad: quien es propietario de que, y en que porcentaje.
 *
 * <p>Seguridad 2 la usa para decidir que unidades ve un propietario. Varias personas pueden tener
 * una titularidad vigente sobre la misma unidad: la copropiedad existe, y de ahi el porcentaje.
 *
 * <p>La vigencia viaja desde el issue <strong>#19</strong>, que es el que trajo la venta de una
 * unidad. Sin ella, el historico de titularidades --lo que el administrador consulta para dar un
 * comprobante de la epoca de quien vendio, como pide Seguridad 6.2-- seria una lista de personas
 * sin fechas, de la que no se podria decir quien era el dueño en un mes dado.
 *
 * @param share porcentaje de copropiedad, con seis decimales por la misma razon que la alicuota
 * @param validTo nulo mientras la titularidad este vigente
 */
public record OwnershipView(
        UUID id,
        UUID condominiumId,
        UUID unitId,
        UUID personId,
        BigDecimal share,
        Instant validFrom,
        @Nullable Instant validTo) {}
