package bo.coprop.billing;

/**
 * Que clase de cobro es un concepto (analisis 5.2).
 *
 * <p>Son las clases que el analisis enumera, y no una lista abierta, porque de ellas depende
 * <strong>como se calcula</strong> el monto: {@link #DUES} se reparte por alicuota (issue #24),
 * {@link #WATER} sale de una lectura y una tarifa (#25) y {@link #LATE_FEE} de la politica de mora
 * (#27). El metodo de calculo no es de este ticket; el tipo si, porque es lo que dira a cada uno de
 * esos tres a que conceptos les toca.
 *
 * <p>{@link #OTHER} existe para que un condominio no tenga que esperar una version nueva para
 * cobrar algo que nadie previo --un aporte extraordinario, una multa pactada en asamblea--. Su
 * monto lo pone quien emite, porque no hay formula que derivarlo.
 */
public enum ChargeConceptType {
    /** Expensas ordinarias. Se reparten por alicuota. */
    DUES,
    WATER,
    /** Multa que no es mora: la que impone el reglamento interno. */
    FINE,
    /** Mora: lo que se recarga por pagar tarde. */
    LATE_FEE,
    /** Reserva de un area comun. */
    RESERVATION,
    OTHER
}
