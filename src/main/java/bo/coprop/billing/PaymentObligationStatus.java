package bo.coprop.billing;

/**
 * En que punto esta una obligacion de pago (analisis 5.3).
 *
 * <p>La maquina es <strong>{@code DRAFT -> ISSUED -> PAID}</strong>, con salidas a
 * {@link #CANCELLED} y {@link #IN_AGREEMENT}. No hay estado "vencida": <strong>vencer es un hecho
 * de la fecha, no un estado</strong>, y el analisis es explicito --una vencida sigue siendo cobrable
 * y sigue generando mora--. Si fuera un estado habria que recorrer la tabla cada noche para moverlo,
 * y una obligacion pagada el dia del vencimiento pasaria por dos estados en un dia.
 *
 * <p><strong>No existe {@code PARTIAL}, y tampoco el saldo</strong>: en el MVP no hay pagos
 * parciales (decidido el 19-oct-2026, escrito en 5.3). Una obligacion se paga por su monto completo.
 *
 * <p>Las transiciones las fuerza la entidad, no el servicio ni el controlador, asi que valen igual
 * llamadas desde la emision del #24, desde el worker del #28 o desde el registro de un pago del #43.
 */
public enum PaymentObligationStatus {
    /**
     * Calculada y todavia no cobrable: el propietario no la ve.
     *
     * <p>Existe porque el #24 pide previsualizar la emision antes de confirmarla. Un borrador que no
     * se confirma se anula; no se borra, por lo mismo que no se borra nada en este esquema.
     */
    DRAFT,
    /**
     * Salio a cobro.
     *
     * <p>El analisis la llama {@code PENDIENTE} en la maquina larga de 5.3; aqui es {@code ISSUED}
     * porque es el nombre que concuerda con el evento que el {@code package-info} de este modulo
     * promete publicar, y porque "emitida" es la accion que la pone ahi.
     */
    ISSUED,
    /** Cobrada por su monto completo. Es final: una pagada no se anula, se revierte el pago. */
    PAID,
    /**
     * Anulada.
     *
     * <p>Se llega desde las dos orillas: un borrador descartado y una emitida que no debio emitirse.
     * Nunca desde {@link #PAID}, que es un criterio del ticket.
     */
    CANCELLED,
    /**
     * Dentro de un convenio de pago: la deuda sigue viva pero su cobro lo gobierna el convenio.
     *
     * <p>Lo que el convenio hace con ella --cuotas, intereses, que pasa si se incumple-- es de su
     * propio ticket. Aqui solo existe la transicion de entrada y la salida a {@link #PAID}.
     */
    IN_AGREEMENT
}
