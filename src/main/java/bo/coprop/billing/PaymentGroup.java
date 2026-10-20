package bo.coprop.billing;

/**
 * Con que otros conceptos comparte cobro este (analisis 5.3).
 *
 * <p>Es lo que decide <strong>que obligaciones caben en un mismo QR</strong>. El analisis pide que
 * un {@code PaymentIntent} rechace obligaciones de grupos distintos, porque cada grupo puede acabar
 * en una cuenta bancaria o en un servicio distinto del condominio, y un solo QR no se puede partir
 * entre dos.
 *
 * <p><strong>No se deriva del tipo del concepto, y por eso es un dato aparte.</strong> La razon es
 * la mora: el analisis exige que la mora se pague con lo que la genero --la del agua con el agua--
 * asi que un condominio tiene tantos conceptos de tipo {@link ChargeConceptType#LATE_FEE} como
 * grupos en los que cobre mora, cada uno con el grupo de su obligacion de origen. Derivar el grupo
 * del tipo haria imposible esa configuracion, que es justo la que el analisis pide. Vale lo mismo
 * para {@link ChargeConceptType#OTHER}: un aporte extraordinario se cobra con las expensas o
 * aparte, y eso lo decide el condominio.
 *
 * <p>Son cuatro y no uno por concepto: agrupar es precisamente lo que permite que las expensas de
 * tres meses y su mora salgan en un solo QR, que es el caso normal de cobro.
 */
public enum PaymentGroup {
    DUES,
    WATER,
    RESERVATIONS,
    FINES
}
