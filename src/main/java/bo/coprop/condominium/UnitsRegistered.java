package bo.coprop.condominium;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Se dieron de alta unidades en un condominio.
 *
 * <p>Uno por llamada y no uno por unidad: el alta masiva del issue #10 crea hasta doscientas de
 * una vez, y eso es un solo hecho con un solo actor y una sola fecha. Doscientos eventos
 * dejarian doscientas lineas de bitacora para una sola decision.
 *
 * @param unitIds las unidades creadas, en el orden en que se pidieron
 * @param actor quien las dio de alta
 * @param occurredAt cuando
 */
public record UnitsRegistered(UUID condominiumId, List<UUID> unitIds, String actor, Instant occurredAt) {}
