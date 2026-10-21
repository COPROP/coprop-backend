package bo.coprop.billing;

import java.util.List;
import java.util.UUID;

/**
 * Las obligaciones de pago: lo que cada unidad debe y por que. Issue #23.
 *
 * <p>Es la entidad central del sistema. El analisis 5.1 la resume en una cadena --
 * {@code unit -> payment_obligation -> payment_intent_line -> payment_intent ->
 * provider_transaction -> payment -> payment_allocation}-- y todo lo que genera deuda desemboca
 * aqui: expensas (#24), agua (#25), mora (#27), multas y reservas.
 *
 * <p><strong>No hay rutas HTTP en este ticket, y es una decision.</strong> Crear obligaciones es del
 * #24 y del #25 --que calculan montos y no solo cambian un estado--, cobrarlas es del #34 y del #43,
 * y el estado de cuenta que las muestra es del #31. Lo que este ticket deja listo es el modelo y la
 * maquina, para que esos cuatro la usen en vez de inventarse cada uno la suya. Es el mismo reparto
 * que en el #101 con el periodo.
 *
 * <p><strong>Lo que no trae, y donde esta cada cosa:</strong>
 *
 * <ul>
 *   <li><em>El calculo del monto</em>: #24 por alicuota, #25 por lectura y tarifa, #27 por politica
 *       de mora. Aqui el monto llega dado.
 *   <li><em>El pago</em>: el {@code Payment} y su aplicacion son del #43. Aqui solo esta la
 *       transicion a {@code PAID}, que ese ticket llamara.
 *   <li><em>El saldo y los pagos parciales</em>: Fase 2. En el MVP se paga el monto completo.
 *   <li><em>El borrado</em>: no existe. Una deuda no se borra; se anula, y queda.
 * </ul>
 */
public interface PaymentObligations {

    /**
     * Crea la obligacion en {@link PaymentObligationStatus#DRAFT}.
     *
     * <p><strong>Compone el numero legible</strong> que pide el analisis 5.2 con el codigo del
     * concepto, el año y el mes, y el codigo de la unidad: {@code EXP-2026-09-A-302}. El mes sale
     * del periodo cuando lo hay y de la fecha de vencimiento cuando no --una multa no pertenece a
     * ningun mes--. Si ese nombre ya existe en el condominio, le pone un correlativo detras
     * ({@code -2}), que es el caso de dos multas del mismo concepto sobre la misma unidad en el
     * mismo mes.
     *
     * @throws bo.coprop.shared.ResourceNotFound si el condominio, la unidad, el concepto, el periodo
     *     o la obligacion padre no existen, o no son de ese condominio
     * @throws bo.coprop.shared.BusinessRuleViolated si el origen llega a medias
     */
    PaymentObligationView register(UUID condominiumId, NewPaymentObligation body, String actor);

    /**
     * La saca a cobro: {@code DRAFT -> ISSUED}, y le pone la fecha de emision.
     *
     * <p>Publica {@link PaymentObligationIssued}, que es el evento que el {@code package-info} de
     * este modulo promete y por el que esperan las notificaciones.
     *
     * @throws bo.coprop.shared.ResourceNotFound si no existe o no es de ese condominio
     * @throws bo.coprop.shared.StateConflict si no estaba en {@code DRAFT}
     */
    PaymentObligationView issue(UUID condominiumId, UUID obligationId, String actor);

    /**
     * La marca pagada: {@code ISSUED -> PAID}, o {@code IN_AGREEMENT -> PAID}.
     *
     * <p>La llamara el #43 cuando un pago la cubra entera. <strong>No hay pago parcial</strong>: no
     * existe una forma de pagar la mitad y que el estado lo refleje.
     *
     * @throws bo.coprop.shared.ResourceNotFound si no existe o no es de ese condominio
     * @throws bo.coprop.shared.StateConflict si es un borrador, si ya estaba pagada o si esta anulada
     */
    PaymentObligationView pay(UUID condominiumId, UUID obligationId, String actor);

    /**
     * La anula, con su motivo.
     *
     * <p>Se puede anular un borrador --el que se descarto al previsualizar-- y una emitida que no
     * debio emitirse. <strong>Una pagada no</strong>: eso es revertir el pago, y es del #43. Es un
     * criterio del ticket.
     *
     * @throws bo.coprop.shared.ResourceNotFound si no existe o no es de ese condominio
     * @throws bo.coprop.shared.StateConflict si ya estaba anulada, o si esta pagada
     */
    PaymentObligationView cancel(UUID condominiumId, UUID obligationId, String reason, String actor);

    /**
     * La mete en un convenio de pago: {@code ISSUED -> IN_AGREEMENT}.
     *
     * <p>Lo que el convenio hace con ella --cuotas, intereses, incumplimiento-- es de su ticket.
     * Aqui solo cambia de estado, para que la deuda deje de tratarse como un cobro corriente.
     *
     * @throws bo.coprop.shared.ResourceNotFound si no existe o no es de ese condominio
     * @throws bo.coprop.shared.StateConflict si no estaba emitida
     */
    PaymentObligationView putUnderAgreement(UUID condominiumId, UUID obligationId, String actor);

    /**
     * Lo que una unidad debe, de la mas reciente a la mas antigua.
     *
     * <p>Sin paginar todavia: lo que pagina es el estado de cuenta del #31, que es quien lo necesita.
     *
     * @throws bo.coprop.shared.ResourceNotFound si el condominio o la unidad no existen
     */
    List<PaymentObligationView> ofUnit(UUID condominiumId, UUID unitId);

    /**
     * Las obligaciones de un periodo, por numero.
     *
     * <p>La necesitan la emision del #24 --para no emitir dos veces-- y el cierre del mes.
     *
     * @throws bo.coprop.shared.ResourceNotFound si el condominio o el periodo no existen
     */
    List<PaymentObligationView> ofPeriod(UUID condominiumId, UUID periodId);
}
