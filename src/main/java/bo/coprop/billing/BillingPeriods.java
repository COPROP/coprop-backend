package bo.coprop.billing;

import java.util.List;
import java.util.UUID;

/**
 * Los periodos de facturacion de un condominio: el mes al que pertenece cada obligacion. Issue #101.
 *
 * <p>El analisis 5.2 lo quiere como entidad propia y con estado. Sin el, {@code PaymentObligation}
 * (#23) no tiene con que agrupar las obligaciones de un mes, el worker del #28 no tiene que cerrar y
 * las lecturas de agua del #26 no tienen donde colgarse.
 *
 * <p><strong>El condominio es el primer argumento de todo</strong>, por la misma razon que en
 * {@link ChargeConcepts}: el analisis 12.2 exige el {@code condominium_id} en toda consulta, y una
 * firma que acepta solo el identificador del periodo deja la comprobacion al criterio de quien
 * llama. Un periodo de otro condominio no existe para este.
 *
 * <p><strong>Las transiciones viven aqui y no en HTTP.</strong> No hay ruta que emita ni que cierre:
 * emitir es del #24 (expensas) y del #25 (agua), que hacen mucho mas que cambiar un estado, y el
 * calendario de cierre es del #28. Lo que este ticket deja listo es la maquina, para que esos tres
 * la llamen en vez de inventarse cada uno la suya.
 *
 * <p><strong>Lo que no trae, y donde esta cada cosa:</strong>
 *
 * <ul>
 *   <li><em>Emitir las obligaciones del periodo</em>: el #24 para expensas y el #25 para agua. Aqui
 *       el periodo solo existe y cambia de estado.
 *   <li><em>Reabrir</em>: no existe. {@code CLOSED} es final, y un mes contable que se reabre no es
 *       un cierre.
 *   <li><em>El borrado</em>: tampoco, por lo mismo que el concepto de cobro --un periodo con
 *       obligaciones emitidas no se puede borrar sin partir la historia de un cobro-- y porque un
 *       periodo que sobra se queda en {@code DRAFT} sin molestar a nadie.
 * </ul>
 */
public interface BillingPeriods {

    /**
     * Abre el periodo de un mes, en {@link BillingPeriodStatus#DRAFT}.
     *
     * @throws bo.coprop.shared.ResourceNotFound si el condominio no existe
     * @throws bo.coprop.shared.StateConflict si ese condominio ya tiene abierto ese año y mes
     */
    BillingPeriodView open(UUID condominiumId, NewBillingPeriod body, String actor);

    /**
     * Los periodos del condominio, del mas reciente al mas antiguo.
     *
     * <p>Sin paginar, igual que el catalogo de conceptos: son doce al año y quien los pide los pide
     * para pintar un selector de mes.
     *
     * @throws bo.coprop.shared.ResourceNotFound si el condominio no existe
     */
    List<BillingPeriodView> list(UUID condominiumId);

    /**
     * Lo saca a cobro: {@code DRAFT -> ISSUED}.
     *
     * <p>Lo llamara el #24 despues de crear las obligaciones del mes, dentro de su transaccion.
     *
     * @throws bo.coprop.shared.ResourceNotFound si el periodo no es de ese condominio o no existe
     * @throws bo.coprop.shared.StateConflict si ya estaba emitido o si estaba cerrado
     */
    BillingPeriodView issue(UUID condominiumId, UUID periodId, String actor);

    /**
     * Cierra el mes: {@code ISSUED -> CLOSED}.
     *
     * <p><strong>Un periodo con obligaciones en {@code DRAFT} no se cierra</strong>: o se emiten o
     * se anulan. Era el criterio que el #101 dejo pendiente y que el #23 cerro al traer la
     * obligacion de pago.
     *
     * @throws bo.coprop.shared.ResourceNotFound si el periodo no es de ese condominio o no existe
     * @throws bo.coprop.shared.StateConflict si todavia esta en {@code DRAFT} o si ya estaba cerrado
     * @throws bo.coprop.shared.BusinessRuleViolated si el periodo tiene obligaciones en borrador
     */
    BillingPeriodView close(UUID condominiumId, UUID periodId, String actor);
}
