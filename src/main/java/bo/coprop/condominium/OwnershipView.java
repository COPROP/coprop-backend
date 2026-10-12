package bo.coprop.condominium;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Una titularidad vigente: quien es propietario de que, y en que porcentaje.
 *
 * <p>Seguridad 2 la usa para decidir que unidades ve un propietario. Varias personas pueden tener
 * una titularidad vigente sobre la misma unidad: la copropiedad existe, y de ahi el porcentaje.
 *
 * @param share porcentaje de copropiedad, con seis decimales por la misma razon que la alicuota
 */
public record OwnershipView(UUID id, UUID condominiumId, UUID unitId, UUID personId, BigDecimal share) {}
