package bo.coprop.condominium;

/**
 * En calidad de que ocupa alguien una unidad (analisis 5.2).
 *
 * <p>Separado de la titularidad: el propietario que alquila no ocupa, y el inquilino ocupa sin ser
 * propietario. Y separado tambien de quien paga, que es
 * {@link BillingResponsibilityView}: en un alquiler corriente ocupa el inquilino y paga el
 * propietario.
 */
public enum OccupancyRole {
    /** Vive ahi y es propietario o familiar del propietario. */
    RESIDENTE,
    /** Vive ahi con un contrato de alquiler. */
    INQUILINO
}
