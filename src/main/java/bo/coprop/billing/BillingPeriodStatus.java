package bo.coprop.billing;

/**
 * En que punto del mes esta un periodo de facturacion (analisis 5.2).
 *
 * <p>La maquina es {@code DRAFT -> ISSUED -> CLOSED} y <strong>no vuelve atras</strong>: un periodo
 * emitido ya tiene obligaciones en manos de los propietarios, y uno cerrado es un mes contable que
 * no se reabre. Las transiciones las fuerza la entidad, no el servicio ni el controlador, asi que
 * valen igual llamadas desde el worker del #28 que desde una peticion HTTP.
 *
 * <p><strong>El periodo nace en {@link #DRAFT}</strong>, y eso se decidio el 19-oct-2026 contra la
 * alternativa de crearlo al emitir. La razon que mas peso: si naciera al emitir, naceria en
 * {@code ISSUED} y {@code DRAFT} seria un valor que nadie escribe nunca --el patron que ya costo dos
 * cabos sueltos aqui, {@code CondominiumStatus.SUSPENDED} y {@code AccountStatus.DEACTIVATED}--.
 * Las otras dos: las lecturas de agua del #26 se cuelgan del periodo antes de facturar, y la
 * previsualizacion que pide el #24 presupone un periodo que existe y todavia no ha emitido.
 */
public enum BillingPeriodStatus {
    /**
     * Se esta preparando: admite que se recalculen sus obligaciones y que entren las lecturas de
     * agua del #26.
     */
    DRAFT,
    /**
     * Ya salio a cobro.
     *
     * <p>Las obligaciones estan emitidas, asi que no se recalculan: lo que se le cobro a un
     * propietario no puede cambiar porque alguien corrija una tarifa despues. Un periodo emitido
     * sigue recibiendo pagos --eso es lo normal-- y no pasa a {@link #CLOSED} por cobrarse entero.
     */
    ISSUED,
    /**
     * Cierre contable del mes.
     *
     * <p>Es el estado final. Quien lo pone es, por ahora, la administracion; el calendario de cierre
     * es del #28.
     */
    CLOSED
}
