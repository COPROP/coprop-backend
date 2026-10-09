package bo.coprop.condominium;

import java.time.Instant;
import java.util.UUID;

/**
 * Se dio de alta un condominio.
 *
 * <p>Existe para que el alta quede auditada sin que este modulo conozca al de auditoria. La regla
 * 4 de ARCHITECTURE.md dice que {@code audit} solo reacciona a eventos y que nadie depende de el;
 * el registro en la bitacora llega con el issue <strong>#21</strong>, que es quien consumira esto.
 *
 * <p>Lleva {@code actor} porque una bitacora sin quien hizo la cosa no sirve de nada. Hoy es el
 * identificador que traiga el contexto de seguridad, que hasta el issue #13 es siempre el mismo.
 *
 * @param condominiumId el condominio recien creado
 * @param name su nombre en el momento del alta, para que la bitacora no dependa de leerlo despues
 * @param actor quien lo dio de alta
 * @param occurredAt cuando
 */
public record CondominiumRegistered(UUID condominiumId, String name, String actor, Instant occurredAt) {}
