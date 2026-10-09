package bo.coprop.condominium;

/**
 * Estado de alta del condominio.
 *
 * <p>No hay estado "borrado": un condominio con historia financiera no se borra nunca, se
 * suspende. Es la misma regla que el analisis 12.2 fija para los registros financieros.
 */
public enum CondominiumStatus {
    /** Operativo: se emiten obligaciones y se aceptan pagos. */
    ACTIVO,
    /** Sigue consultable, pero no se emite ni se cobra. */
    SUSPENDIDO
}
