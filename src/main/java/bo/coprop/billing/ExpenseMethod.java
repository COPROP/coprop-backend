package bo.coprop.billing;

/**
 * Como se calcula lo que paga cada unidad por un concepto de expensas. Issue #24.
 *
 * <p>Son dos y no las cuatro que enumera RF-10 --tambien por metro cuadrado y por tipo de unidad--
 * porque el alcance del ticket nombra dos: monto fijo por unidad o por alicuota. Las otras dos no
 * entran en el MVP, y añadirlas aqui dejaria dos valores que nada escribe; ese patron ya costo dos
 * cabos sueltos en este proyecto ({@code CondominiumStatus.SUSPENDED} y
 * {@code AccountStatus.DEACTIVATED}). Cuando hagan falta, entran con su migracion.
 *
 * <p>El metodo decide ademas <strong>que significa el monto</strong> de la configuracion, y eso es
 * lo que permite que sea una sola columna: con {@link #FIXED_PER_UNIT} es lo que paga cada unidad y
 * con {@link #BY_ALIQUOT} es el total que se reparte.
 */
public enum ExpenseMethod {

    /**
     * El mismo monto para toda unidad activa.
     *
     * <p>Es lo que usa un condominio de casas iguales, y lo que usa casi todo el mundo para el fondo
     * de reserva. No mira la alicuota, asi que no le exige que sume 100.
     */
    FIXED_PER_UNIT,

    /**
     * El total se reparte entre las unidades activas en proporcion a su alicuota.
     *
     * <p>Exige que las alicuotas de las unidades activas sumen 100, que es la validacion que
     * {@code UnitRelations} dejo escrita para M2: no se puede comprobar fila a fila al dar de alta
     * una unidad, y es aqui --repartiendo un total-- donde una suma que no cuadra deja de ser un
     * detalle y se convierte en dinero mal cobrado.
     */
    BY_ALIQUOT
}
