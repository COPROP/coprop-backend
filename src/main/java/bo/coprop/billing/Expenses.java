package bo.coprop.billing;

import java.util.List;
import java.util.UUID;

/**
 * Las expensas: como se calculan y como se emiten por unidad. Issue #24.
 *
 * <p>Es el primer modulo que <strong>crea</strong> obligaciones de pago en lote, y el que por fin
 * llena el {@code sourceType}/{@code sourceId} que el #23 dejo vacios: cada obligacion de expensas
 * apunta a la {@code ExpenseSetting} con la que se calculo su monto.
 *
 * <p><strong>El flujo es el del analisis 7.1</strong>, y son dos pasos y no uno:
 *
 * <ol>
 *   <li>{@link #calculate} reparte el monto configurado entre las unidades activas y deja una
 *       obligacion en {@code DRAFT} por unidad. Es la "vista previa en estado BORRADOR" del
 *       diagrama, y se puede repetir: recalcular reemplaza los borradores anteriores.
 *   <li>{@link #issue} las saca a cobro todas juntas y pasa el periodo a {@code ISSUED}.
 * </ol>
 *
 * <p><strong>Por que la previsualizacion escribe.</strong> El analisis dibuja el borrador como un
 * estado --"Vista previa en estado BORRADOR", con un lazo de "no aprobado" que vuelve a calcular--
 * y el #23 construyo {@code DRAFT} y su transicion justo para esto. Un calculo que no se guardara
 * obligaria a recalcularlo al confirmar, y entre las dos llamadas una alicuota o una unidad pueden
 * cambiar: lo que se emitiria no seria lo que la persona aprobo.
 *
 * <p><strong>Lo que no trae, y donde esta cada cosa:</strong>
 *
 * <ul>
 *   <li><em>El agua</em>: tiene su lectura y sus cuatro estrategias, y es el #25 y el #26.
 *   <li><em>La mora</em>: es el #27, que mira las obligaciones vencidas que esto emite.
 *   <li><em>El calendario</em>: emitir el dia configurado sin que nadie lo pida es el worker del
 *       #28. Este ticket le deja las dos operaciones hechas e idempotentes.
 *   <li><em>El estado de cuenta</em>: lo que ve el propietario es el #31.
 *   <li><em>Los descuentos y ajustes</em>: RF-13, y tienen su entidad propia
 *       ({@code ObligationAdjustment}) en otro ticket. Aqui el monto sale del reparto y nada mas.
 * </ul>
 */
public interface Expenses {

    /**
     * Registra la configuracion de expensas de un concepto, cerrando la anterior.
     *
     * <p>Nunca actualiza en sitio, igual que la configuracion del condominio: la fila anterior queda
     * como historia con su {@code validTo}, y es lo que permite decir con que monto se emitio cada
     * mes. Lo emitido no se toca.
     *
     * @throws bo.coprop.shared.ResourceNotFound si el condominio o el concepto no existen, o si el
     *     concepto es de otro condominio
     * @throws bo.coprop.shared.BusinessRuleViolated si el concepto no es de tipo
     *     {@link ChargeConceptType#DUES}
     */
    ExpenseSettingView configure(UUID condominiumId, NewExpenseSetting body, String actor);

    /**
     * Las configuraciones vigentes del condominio, una por concepto.
     *
     * @throws bo.coprop.shared.ResourceNotFound si el condominio no existe
     */
    List<ExpenseSettingView> current(UUID condominiumId);

    /**
     * Toda la historia de configuraciones del condominio, de la mas reciente a la mas antigua.
     *
     * <p>Separada de {@link #current} y no un parametro suyo porque son dos preguntas distintas: la
     * vigente es lo que se va a cobrar y la historia es con que se cobro. Es el "historial de
     * vigencias" del criterio del ticket.
     *
     * @throws bo.coprop.shared.ResourceNotFound si el condominio no existe
     */
    List<ExpenseSettingView> history(UUID condominiumId);

    /**
     * Calcula las expensas del mes y deja una obligacion en {@code DRAFT} por unidad activa.
     *
     * <p><strong>Abre el periodo si no existe</strong>, de forma idempotente: es lo que el #101 le
     * pidio a este ticket por escrito, para que el worker del #28 no falle en un mes que nadie
     * preparo.
     *
     * <p><strong>Recalcular borra los borradores anteriores y escribe otros.</strong> No los anula:
     * un borrador reemplazado antes de que nadie lo viera no fue deuda de nadie, y dejarlo anulado
     * ocuparia su numero legible --el siguiente calculo saldria {@code EXP-2026-09-A-302-2}-- y
     * llenaria el mes de filas que no cuentan. El borrador que si se anula es el que el
     * administrador descarta a mano, con {@link PaymentObligations#cancel}.
     *
     * <p><strong>Es todo o nada</strong> (analisis 7.1): si una unidad falla no se emite ninguna.
     *
     * @throws bo.coprop.shared.ResourceNotFound si el condominio o el concepto no existen
     * @throws bo.coprop.shared.StateConflict si el mes ya se emitio o esta cerrado
     * @throws bo.coprop.shared.BusinessRuleViolated si no hay configuracion vigente para ese
     *     concepto, si el condominio no tiene unidades activas, si el metodo es
     *     {@link ExpenseMethod#BY_ALIQUOT} y las alicuotas no suman 100, o si alguna unidad no tiene
     *     un responsable de pago que se pueda resolver sin ambiguedad
     */
    ExpenseRunView calculate(UUID condominiumId, UUID conceptId, int year, int month, String actor);

    /**
     * Saca a cobro los borradores del mes y pasa el periodo a {@code ISSUED}.
     *
     * <p><strong>Emitir un mes ya emitido no crea nada</strong>, que es el primer criterio del
     * ticket: responde conflicto diciendo cuantas obligaciones tiene ya ese concepto en ese mes. Lo
     * idempotente es abrir el periodo, no emitirlo; emitir dos veces duplicaria la deuda de todo el
     * edificio, asi que no puede pasar en silencio.
     *
     * @throws bo.coprop.shared.ResourceNotFound si el condominio, el concepto o el periodo no
     *     existen
     * @throws bo.coprop.shared.StateConflict si ese concepto ya se emitio en ese mes, o si el
     *     periodo esta cerrado
     * @throws bo.coprop.shared.BusinessRuleViolated si no hay nada calculado que emitir
     */
    ExpenseRunView issue(UUID condominiumId, UUID conceptId, int year, int month, String actor);
}
