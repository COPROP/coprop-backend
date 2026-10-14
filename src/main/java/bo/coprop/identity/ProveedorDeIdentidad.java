package bo.coprop.identity;

/**
 * Lo que COPROP le pide al proveedor de identidad, que es muy poco.
 *
 * <p>Es la direccion contraria a {@link Identities}: alli llega alguien con un token y se traduce a
 * una cuenta de COPROP; aqui COPROP le pide al proveedor que prepare la credencial de alguien a
 * quien acaba de invitar. Son dos puertos y no uno porque se usan en momentos distintos y uno de
 * los dos sale a la red.
 *
 * <p><strong>COPROP no ve contraseñas, ni para ponerlas.</strong> El realm tiene el registro
 * cerrado ({@code registrationAllowed: false}), asi que la persona invitada no puede crearse el
 * usuario; y una pantalla nuestra que recogiera la contraseña para enviarla al proveedor nos
 * pondria en el camino de la credencial, que es justo lo que el #12 y el #13 decidieron evitar
 * --"no hay contraseña que pedir: la guarda el proveedor de identidad, no esta aplicacion"--. De
 * ahi la forma de este puerto: se crea el usuario y se le pide al proveedor que le escriba.
 *
 * <p>El adaptador habla con la Admin API de Keycloak con el cliente {@code coprop-admin}, que
 * Seguridad 9 acota a {@code manage-users} y {@code view-users}: no administra el realm.
 */
public interface ProveedorDeIdentidad {

    /**
     * Deja a esa persona en condiciones de poner su contraseña e iniciar sesion.
     *
     * <p>Crea el usuario en el proveedor si no existe y le pide al proveedor que le envie la accion
     * de credencial. <strong>Es idempotente</strong>: abrir el enlace de la invitacion dos veces
     * tiene que volver a mandar el correo, no reventar con un choque de usuario repetido.
     *
     * <p>El correo del usuario nace <strong>sin verificar</strong>, y es importante que sea asi:
     * quien lo verifica es la accion que el proveedor le envia, y el primer login del #13 exige
     * {@code email_verified} para vincular la cuenta. Darlo por verificado aqui convertiria "nos
     * dijeron ese correo" en "ese correo es suyo".
     *
     * @param email el correo de la cuenta invitada, que es tambien su nombre de usuario
     * @throws FalloDelProveedorDeIdentidad si el proveedor no esta alcanzable o rechaza la
     *     operacion. No se traga: una invitacion cuyo destinatario no puede entrar no esta emitida.
     */
    void prepararCredencial(String email);

    /** El proveedor de identidad no pudo hacerlo. */
    class FalloDelProveedorDeIdentidad extends RuntimeException {

        public FalloDelProveedorDeIdentidad(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }

        public FalloDelProveedorDeIdentidad(String mensaje) {
            super(mensaje);
        }
    }
}
