package bo.coprop.condominium;

import java.time.Instant;
import java.util.UUID;

/**
 * Se creo una torre o bloque.
 *
 * <p>Lleva el nombre con el que se creo para que la bitacora no dependa de volver a leerlo: el
 * dia que alguien lo renombre, la linea antigua tiene que seguir diciendo lo que decia.
 */
public record BlockRegistered(UUID condominiumId, UUID blockId, String name, String actor, Instant occurredAt) {}
