package bo.coprop.condominium;

/**
 * Tipo de unidad.
 *
 * <p>El analisis 5.2 dice que la unidad tiene tipo pero no enumera los valores; estos se
 * acordaron al resolver el issue #10. Parqueos y depositos van aparte de los departamentos
 * porque suelen tener alicuota propia y a veces dueño distinto.
 */
public enum UnitType {
    APARTMENT,
    HOUSE,
    /** Local comercial. */
    COMMERCIAL,
    PARKING,
    /** Baulera o deposito. */
    STORAGE
}
