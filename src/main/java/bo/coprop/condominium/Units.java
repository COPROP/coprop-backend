package bo.coprop.condominium;

import java.util.List;
import java.util.UUID;

/**
 * Unidades y la jerarquia opcional que las organiza.
 *
 * <p>Los bloques y los pisos viven aqui y no en un servicio propio porque solo existen para
 * ordenar unidades: un bloque sin unidades no le sirve a nadie.
 *
 * <p>La jerarquia es opcional de verdad. Un condominio de doce casas no necesita bloques ni
 * pisos, y nada le obliga a inventarselos.
 *
 * <p><strong>El condominio es el primer argumento de todo</strong>, incluso de las consultas por
 * identificador. No es ceremonia: el analisis 12.2 exige el {@code condominium_id} en toda
 * consulta y Seguridad 7.1 pide comprobar que el recurso pedido pertenece al condominio del
 * contexto. Una firma que acepta solo el identificador de la unidad deja esa comprobacion al
 * criterio de quien llama, y basta olvidarla una vez para servir la unidad de otro condominio.
 *
 * <p>Toda operacion de escritura recibe el actor y publica un evento: es lo que hara que el alta
 * quede en la bitacora del analisis 12.2 cuando llegue el issue <strong>#21</strong>.
 */
public interface Units {

    /**
     * Da de alta una unidad.
     *
     * @throws bo.coprop.shared.RecursoNoEncontrado si el condominio no existe
     * @throws bo.coprop.shared.ReglaDeNegocioViolada si el codigo ya existe en ese condominio, o
     *     si el bloque o el piso indicados no son de ese condominio
     */
    UnitView register(UUID condominiumId, NewUnit nueva, String actor);

    /**
     * Da de alta varias unidades de una vez.
     *
     * <p><strong>Todo o nada.</strong> Si una sola falla no se crea ninguna: un alta masiva a
     * medias deja al administrador sin saber cuales entraron, y reintentarla choca con las que ya
     * estaban.
     *
     * @throws bo.coprop.shared.ReglaDeNegocioViolada si hay codigos repetidos dentro del propio
     *     lote, o si alguna unidad no pasa las validaciones de {@link #register}
     */
    List<UnitView> registerAll(UUID condominiumId, List<NewUnit> nuevas, String actor);

    /**
     * Devuelve una unidad de este condominio.
     *
     * @throws bo.coprop.shared.RecursoNoEncontrado si no existe <strong>o si es de otro
     *     condominio</strong>. Las dos cosas responden igual a proposito: distinguirlas diria que
     *     esa unidad existe en algun sitio (Seguridad 7.1, y el criterio 404-no-403 del issue #17)
     */
    UnitView find(UUID condominiumId, UUID unitId);

    /**
     * Crea una torre o bloque.
     *
     * @throws bo.coprop.shared.RecursoNoEncontrado si el condominio no existe
     * @throws bo.coprop.shared.ReglaDeNegocioViolada si ya hay un bloque con ese nombre
     */
    BlockView addBlock(UUID condominiumId, String name, String actor);

    /**
     * Crea un piso dentro de un bloque de este condominio.
     *
     * @throws bo.coprop.shared.RecursoNoEncontrado si el bloque no existe o es de otro condominio
     * @throws bo.coprop.shared.ReglaDeNegocioViolada si ese bloque ya tiene ese piso
     */
    FloorView addFloor(UUID condominiumId, UUID blockId, int number, String actor);
}
