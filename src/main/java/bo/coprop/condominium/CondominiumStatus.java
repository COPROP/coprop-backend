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
    /**
     * Sigue consultable, pero no se emite ni se cobra.
     *
     * <p><strong>Hoy nadie lo pone y no tiene ticket.</strong> El alta escribe siempre ACTIVO y la
     * edicion del #90 deja el estado fuera a proposito: suspender es una accion de negocio con
     * efectos sobre los QR vivos y sobre la mora en curso que estan sin decidir, no el cambio de un
     * campo. Lo de aqui arriba es lo que tendra que cumplir cuando se decida, no lo que pasa ahora.
     */
    SUSPENDIDO
}
