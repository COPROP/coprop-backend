package bo.coprop.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Una linea de la bitacora.
 *
 * <p>Los nombres van en español, al contrario que el resto de las vistas del sistema, porque el
 * analisis no nombra esta entidad en ingles: la nombra por sus datos, en 5.2 --"actor, accion,
 * entidad, antes, despues, fecha, IP"-- y esos son los nombres que usa.
 *
 * @param condominio nulo en las acciones de plataforma, que no cuelgan de ninguno
 * @param antes nulo cuando no habia nada antes, que es el caso de toda alta
 * @param despues nulo cuando la accion no deja valores que mirar, como una revocacion
 * @param ip nula cuando el evento se reentrego tras un reinicio y no habia peticion de la que
 *     sacarla
 * @param traceId la traza de la peticion, para encontrar sus lineas de log
 */
public record AuditEntryView(
        UUID id,
        String actor,
        String accion,
        String entidad,
        UUID entidadId,
        @Nullable UUID condominio,
        @Nullable Map<String, String> antes,
        @Nullable Map<String, String> despues,
        @Nullable String ip,
        @Nullable String traceId,
        Instant occurredAt) {}
