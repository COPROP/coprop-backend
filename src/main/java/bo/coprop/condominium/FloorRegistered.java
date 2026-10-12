package bo.coprop.condominium;

import java.time.Instant;
import java.util.UUID;

/** Se creo un piso dentro de un bloque. */
public record FloorRegistered(
        UUID condominiumId, UUID blockId, UUID floorId, int number, String actor, Instant occurredAt) {}
