package bo.coprop.condominium;

/**
 * Las tres formas en que una persona se relaciona con una unidad.
 *
 * <p>Es el mismo reparto que modelan {@code UnitOwnership}, {@code UnitOccupancy} y
 * {@code BillingResponsibility}, pero visto del lado de la persona: para decirle a un cliente que
 * pinta con esta unidad no hace falta la fila entera de cada relacion, solo cual de las tres es.
 *
 * <p>No es un estado ni son excluyentes: en un alquiler normal el propietario tiene
 * {@link #OWNER} y {@link #BILLING_RESPONSIBLE}, y el inquilino {@link #OCCUPANT}. De ahi
 * que {@link RelatedUnitView} lleve un conjunto y no un valor.
 */
public enum UnitRelation {
    OWNER,
    OCCUPANT,
    BILLING_RESPONSIBLE
}
