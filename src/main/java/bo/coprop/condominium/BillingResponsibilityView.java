package bo.coprop.condominium;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Una responsabilidad de pago vigente: quien responde por los cobros de una unidad.
 *
 * <p>Es la tercera relacion y la razon de que no haya una sola tabla con columna de tipo: en un
 * alquiler corriente el inquilino ocupa y el propietario sigue pagando, y eso no se puede decir
 * con un unico papel por persona y unidad.
 *
 * @param conceptId nulo = responde por todo lo que se cobre a la unidad. Con valor, solo por ese
 *     concepto, que es como se reparte "el inquilino paga el agua y el propietario las expensas".
 *     El concepto como entidad nace en M2.
 */
public record BillingResponsibilityView(
        UUID id, UUID condominiumId, UUID unitId, UUID personId, @Nullable UUID conceptId) {}
