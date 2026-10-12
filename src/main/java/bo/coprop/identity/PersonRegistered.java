package bo.coprop.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Se dio de alta una persona.
 *
 * <p>Solo se publica cuando la fila se crea de verdad. El alta de una persona que ya existia
 * reutiliza la fila y no publica nada: no ha pasado nada que auditar, y un evento por intento
 * dejaria en la bitacora del #21 el rastro de que esa persona ya estaba --que es justo lo que la
 * respuesta uniforme evita.
 */
public record PersonRegistered(UUID personId, String actor, Instant occurredAt) {}
