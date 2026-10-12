package bo.coprop.condominium;

import java.util.UUID;

/** Una ocupacion vigente: quien vive en una unidad, y en calidad de que. */
public record OccupancyView(UUID id, UUID condominiumId, UUID unitId, UUID personId, OccupancyRole role) {}
