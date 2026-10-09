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
 */
public interface Units {

    /**
     * Da de alta una unidad.
     *
     * @throws bo.coprop.shared.RecursoNoEncontrado si el condominio no existe
     * @throws bo.coprop.shared.ReglaDeNegocioViolada si el codigo ya existe en ese condominio, o
     *     si el bloque o el piso indicados no son de ese condominio
     */
    UnitView register(UUID condominiumId, NewUnit nueva);

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
    List<UnitView> registerAll(UUID condominiumId, List<NewUnit> nuevas);

    /**
     * Devuelve una unidad por su identificador.
     *
     * @throws bo.coprop.shared.RecursoNoEncontrado si no existe
     */
    UnitView find(UUID unitId);

    /**
     * Crea una torre o bloque.
     *
     * @throws bo.coprop.shared.RecursoNoEncontrado si el condominio no existe
     * @throws bo.coprop.shared.ReglaDeNegocioViolada si ya hay un bloque con ese nombre
     */
    BlockView addBlock(UUID condominiumId, String name);

    /**
     * Crea un piso dentro de un bloque.
     *
     * @throws bo.coprop.shared.RecursoNoEncontrado si el bloque no existe
     * @throws bo.coprop.shared.ReglaDeNegocioViolada si ese bloque ya tiene ese piso
     */
    FloorView addFloor(UUID blockId, int number);
}
