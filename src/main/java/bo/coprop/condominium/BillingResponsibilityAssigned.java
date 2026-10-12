package bo.coprop.condominium;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Se asigno quien responde por los cobros de una unidad.
 *
 * <p>De todas las relaciones es la que mas falta hace auditar: cambia a quien se le reclama el
 * dinero.
 *
 * @param conceptId nulo = por todos los conceptos
 */
public record BillingResponsibilityAssigned(
        UUID condominiumId, UUID unitId, UUID personId, @Nullable UUID conceptId, String actor, Instant occurredAt) {}
