package bo.coprop.identity;

import java.time.Instant;
import java.util.List;

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
public interface IdentityProvider {

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

    /**
     * Cierra en el proveedor todas las sesiones de esa cuenta y revoca sus refresh tokens. Issue
     * <strong>#19</strong>.
     *
     * <p>Es la mitad de Seguridad 5.3 que COPROP no puede hacer por su cuenta: el refresh token de
     * la app movil lo guarda y lo renueva Keycloak, asi que mientras el proveedor lo acepte la app
     * sigue consiguiendo tokens nuevos aunque aqui no quede sesion ninguna. La otra mitad, la
     * cookie del navegador, la cierra {@link Sessions}.
     *
     * <p><strong>Es idempotente</strong>: si esa persona no tiene usuario en el proveedor --una
     * cuenta invitada que nunca entro-- no hay nada que cerrar y no es un error.
     *
     * @throws FalloDelProveedorDeIdentidad si el proveedor no esta alcanzable o rechaza la
     *     operacion. Quien llama decide que hacer: la baja de una membresia no se deshace por esto,
     *     porque el acceso ya lo corto el filtro de condominio en la siguiente peticion
     */
    void closeSessions(String email);

    /**
     * Las sesiones que esa cuenta tiene abiertas en el proveedor. Issue <strong>#22</strong>.
     *
     * <p>Es la otra mitad de la lista que pide Seguridad 8, y hace falta porque
     * <strong>la sesion del BFF no es la unica</strong>: la app movil no tiene ninguna aqui y aun
     * asi esta dentro, con su refresh token, y la sesion de Keycloak es la que hace que volver a
     * entrar por la web no vuelva a pedir contraseña. Una pantalla de "mis sesiones activas" que
     * mirara solo una de las dos mentiria.
     *
     * <p>Es la unica consulta que este puerto hace al proveedor; las otras dos escriben.
     *
     * <p><strong>Es idempotente y tolerante</strong>: una cuenta sin usuario en el proveedor
     * --invitada y que nunca entro-- no tiene sesiones y eso no es un error.
     *
     * @throws FalloDelProveedorDeIdentidad si el proveedor no esta alcanzable o rechaza la
     *     operacion. Quien llama decide: la lista de sesiones del usuario prefiere enseñar lo que
     *     sabe a no enseñar nada
     */
    List<ProviderSession> sessionsOf(String email);

    /**
     * Una sesion abierta en el proveedor de identidad.
     *
     * <p>Lleva la <strong>IP</strong>, que la sesion del BFF no tiene: es lo unico de esta lista
     * que ayuda de verdad a reconocer una sesion ajena.
     *
     * @param id el identificador de la sesion en el proveedor
     * @param ip desde donde se abrio; nula si el proveedor no la da
     * @param created cuando empezo
     * @param lastAccess la ultima vez que se uso
     */
    record ProviderSession(
            String id, @org.jspecify.annotations.Nullable String ip, Instant created, Instant lastAccess) {}

    /** El proveedor de identidad no pudo hacerlo. */
    class IdentityProviderFailure extends RuntimeException {

        public IdentityProviderFailure(String message, Throwable causa) {
            super(message, causa);
        }

        public IdentityProviderFailure(String message) {
            super(message);
        }
    }
}
