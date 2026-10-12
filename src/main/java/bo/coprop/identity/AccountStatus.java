package bo.coprop.identity;

/**
 * Estado de una cuenta de acceso (Seguridad 2).
 *
 * <p>Es el estado de la credencial, no de la persona ni de su vinculo con un condominio: una
 * cuenta bloqueada sigue siendo de alguien que puede ser propietario. Lo que decide si entra a un
 * condominio es su membresia.
 */
public enum AccountStatus {
    /** Tiene cuenta pero nunca ha entrado. El {@code idpSubject} todavia no existe. */
    INVITADO,
    ACTIVO,
    /** Bloqueada por seguridad; se puede desbloquear. */
    BLOQUEADO,
    /** Dada de baja. No vuelve. */
    DESACTIVADO
}
