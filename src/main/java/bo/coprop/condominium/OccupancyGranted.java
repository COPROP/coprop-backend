package bo.coprop.condominium;

import java.time.Instant;
import java.util.UUID;

/** Se registro que alguien ocupa una unidad. */
public record OccupancyGranted(
        UUID condominiumId, UUID unitId, UUID personId, OccupancyRole role, String actor, Instant occurredAt) {}
