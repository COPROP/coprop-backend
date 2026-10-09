package bo.coprop.condominium;

/**
 * Estado de la unidad.
 *
 * <p>No hay borrado: una unidad con deuda o pagos historicos no se borra nunca. Si deja de
 * existir -- una fusion de dos departamentos, por ejemplo -- se marca inactiva y su historia
 * sigue consultable.
 */
public enum UnitStatus {
    ACTIVA,
    INACTIVA
}
