package bo.coprop.condominium;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Se registro una titularidad sobre una unidad.
 *
 * <p>Es un cambio con consecuencias economicas --de aqui sale a quien se le cobra-- asi que la
 * bitacora tiene que poder decir quien lo hizo y con que porcentaje.
 */
public record OwnershipGranted(
        UUID condominiumId, UUID unitId, UUID personId, BigDecimal share, String actor, Instant occurredAt) {}
