package bo.coprop.condominium;

import bo.coprop.shared.EventoAuditable;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Se dio de alta un condominio.
 *
 * <p>Existe para que el alta quede auditada sin que este modulo conozca al de auditoria. La regla
 * 4 de ARCHITECTURE.md dice que {@code audit} solo reacciona a eventos y que nadie depende de el.
 *
 * <p><strong>Implementa {@link EventoAuditable} desde el 16-oct-2026, y antes no.</strong> El
 * evento se publicaba desde el issue #9 con el comentario de que la bitacora llegaria con el #21,
 * y el #21 cerro sin cablearlo: nadie lo escuchaba, asi que <em>el alta de un condominio no
 * quedaba registrada</em> aunque el primer criterio del #9 lo exige. Es la forma exacta del riesgo
 * que esta interfaz tiene de nacimiento --no hay lista de eventos auditables en ninguna parte, asi
 * que un evento que no la implementa no falla, simplemente no se audita-- y conviene dejarla
 * escrita aqui para que se reconozca la proxima vez.
 *
 * <p>Lleva {@code actor} porque una bitacora sin quien hizo la cosa no sirve de nada.
 *
 * @param condominiumId el condominio recien creado
 * @param name su nombre en el momento del alta, para que la bitacora no dependa de leerlo despues
 * @param actor quien lo dio de alta
 * @param occurredAt cuando
 */
public record CondominiumRegistered(UUID condominiumId, String name, String actor, Instant occurredAt)
        implements EventoAuditable {

    @Override
    public String action() {
        return "CONDOMINIUM_REGISTERED";
    }

    @Override
    public String entity() {
        return "condominio";
    }

    @Override
    public UUID entityId() {
        return condominiumId;
    }

    /**
     * Solo el nombre, y no el NIT ni el tipo.
     *
     * <p>El alta es la unica accion cuyo {@code condominiumId} y {@code entityId} son el mismo, y
     * la fila del condominio sigue existiendo con todos sus datos: lo que la bitacora tiene que
     * poder decir de un alta es quien y cuando, no repetir la ficha. El nombre va porque es lo que
     * identifica el condominio para quien lee la bitacora, y porque puede haber cambiado despues
     * --{@link CondominiumEdited} lo permite-- y entonces el nombre de hoy ya no diria cual se dio
     * de alta.
     *
     * <p>{@code oldValues()} se queda en el nulo por defecto: no habia nada antes, que es el caso de
     * todo alta.
     */
    @Override
    public Map<String, String> newValues() {
        return Map.of("name", name);
    }
}
