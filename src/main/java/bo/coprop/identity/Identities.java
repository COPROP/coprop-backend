package bo.coprop.identity;

import org.jspecify.annotations.Nullable;

/**
 * La identidad local de quien llega con un token del proveedor de identidad.
 *
 * <p>Es la frontera entre Keycloak y COPROP. Keycloak dice <em>quien es</em> --un {@code sub} y un
 * correo-- y aqui se traduce a la cuenta y la persona de la plataforma. Lo que ese alguien
 * <em>puede ver</em> no se decide aqui: eso es la membresia, y la decide el filtro de condominio
 * del issue #17 con la matriz de roles del #16.
 *
 * <p>Vive en {@code identity} y no en {@code api} porque el vinculo es un hecho del dominio: deja
 * rastro en la cuenta y en la bitacora. Lo que vive en {@code api} es el filtro que lo invoca.
 */
public interface Identities {

    /**
     * Resuelve la identidad local del sujeto de un token y, en el primer login, la vincula.
     *
     * <p><strong>Devuelve {@code null} cuando esa persona no tiene acceso</strong>, y lo devuelve
     * igual en los cuatro casos en que eso ocurre: no hay cuenta con ese correo, la hay pero esta
     * bloqueada, esta dada de baja, o ya esta vinculada a otro sujeto. Quien llama no puede
     * distinguirlos y es a proposito --mismo criterio que el alta de personas y que el 404-no-403
     * del #17--; el log si los distingue, porque lo lee quien opera, no quien ataca.
     *
     * <p><strong>El primer login exige el correo verificado.</strong> Vincular por correo es lo que
     * hace que entrar con contraseña y entrar con Google caigan en la misma cuenta, y es seguro
     * solo mientras el proveedor garantice que ese correo es de quien dice. Un token con
     * {@code email_verified} en falso no vincula nada.
     *
     * <p>Cuando vincula, publica {@link AccountActivated}: es el unico momento en la vida de una
     * cuenta en que se sabe que alguien entro por primera vez, y el issue #21 lo quiere en la
     * bitacora.
     *
     * <p>No recibe actor, al contrario que el resto de operaciones de escritura: el actor es el
     * propio sujeto que entra, y pasarlo seria repetir el primer argumento.
     *
     * @param idpSubject el {@code sub} del token
     * @param email el correo del token
     * @param emailVerified el {@code email_verified} del token
     */
    @Nullable
    AccountView signIn(String idpSubject, String email, boolean emailVerified);

    /**
     * Si esa cuenta administra la plataforma (Seguridad 3).
     *
     * <p>Vive en su propia tabla y no en una membresia, porque no depende de ningun condominio, y
     * no viaja en el token, porque los roles se resuelven en cada peticion: quitarselo a alguien
     * tiene que surtir efecto ya, no cuando expire su token.
     */
    boolean esSuperadministrador(java.util.UUID accountId);

    /**
     * Concede el rol de plataforma a otra cuenta. Issue <strong>#20</strong>.
     *
     * <p>Hasta este issue el rol solo lo abria el arranque, y solo cuando no habia ninguno: con eso
     * la plataforma podia empezar pero no crecer, y el segundo superadministrador salia de un
     * {@code INSERT} a mano. Esta es esa operacion.
     *
     * <p>No lleva motivo, igual que el alta de una membresia: lo que hace falta explicar es por que
     * se le quita a alguien, no por que se le da. Y <strong>nadie se lo concede a si mismo</strong>,
     * que es lo que ya impide el {@code CHECK} de la migracion V7; aqui se comprueba antes para
     * poder explicarlo.
     *
     * <p>Publica {@link PlatformAdminGranted}, que es la fila de la bitacora de plataforma.
     *
     * @param actor la cuenta que lo concede, que viaja tambien como {@code grantedBy}
     * @throws bo.coprop.shared.ResourceNotFound si la cuenta no existe
     * @throws bo.coprop.shared.BusinessRuleViolated si se lo concede a si mismo
     * @throws bo.coprop.shared.StateConflict si esa cuenta ya lo tiene
     */
    PlatformAdminView grantPlatformAdmin(java.util.UUID accountId, String actor);

    /**
     * Le quita el rol de plataforma a una cuenta. Issue <strong>#20</strong>.
     *
     * <p><strong>Borra la fila y le cierra las sesiones</strong>, las del BFF y las del proveedor.
     * Lo segundo es Seguridad 8 --revocar sesiones al retirar un rol administrativo-- y no hay rol
     * mas administrativo que este; lo primero es la forma de la tabla, explicada en
     * {@link PlatformAdminView}. Lo que queda de la historia es la bitacora.
     *
     * <p><strong>Nadie se lo revoca a si mismo.</strong> Es la misma regla de recuperacion que en la
     * baja de una membresia: quien se lo quitara se quedaria sin la unica pantalla desde la que
     * podria devolverselo, y con eso la plataforma podria quedarse sin nadie que cree condominios.
     * Para dejar el puesto se lo pide a otro superadministrador.
     *
     * <p>Publica {@link PlatformAdminRevoked}.
     *
     * @param reason por que se le quita. Obligatorio: la fila se borra, asi que esto es lo unico que
     *     lo explica
     * @throws bo.coprop.shared.ResourceNotFound si esa cuenta no lo tiene --y responde lo mismo
     *     que si no existiera, porque quien administra la plataforma no es dato publico--
     * @throws bo.coprop.shared.BusinessRuleViolated si se lo revoca a si mismo
     */
    void revokePlatformAdmin(java.util.UUID accountId, String reason, String actor);

    /**
     * Quien administra la plataforma hoy, de la concesion mas reciente a la mas antigua.
     *
     * <p>Hace falta para poder revocar: sin listado, quitarle el rol a alguien exige conocer de
     * antemano el identificador de su cuenta. Solo las vigentes, que en esta tabla son todas: una
     * fila revocada no existe.
     */
    java.util.List<PlatformAdminView> platformAdmins();

    /**
     * Las sesiones abiertas de una cuenta, las dos mitades juntas. Issue <strong>#22</strong>.
     *
     * <p>Es la "lista de sesiones activas por usuario" de Seguridad 8. Se compone aqui y no en la
     * capa web porque hacen falta los dos nombres con los que una sesion puede estar indexada --el
     * sujeto del proveedor y el identificador de la cuenta-- y el correo con el que el proveedor
     * conoce a esa persona, y los tres son datos de este modulo.
     *
     * <p><strong>Un proveedor caido no vacia la lista</strong>: se devuelven las del BFF y se
     * registra el fallo. Es la decision contraria a la del correo de la invitacion (#18) y la misma
     * que la del cierre de sesiones (#19): enseñar media lista es mejor que no enseñar ninguna a
     * quien esta intentando averiguar si le robaron la cuenta.
     *
     * @throws bo.coprop.shared.ResourceNotFound si la cuenta no existe
     */
    java.util.List<ActiveSessionView> sessionsOf(java.util.UUID accountId);

    /**
     * Cierra todas las sesiones de una cuenta: el "cerrar sesion en todos los dispositivos" de
     * Seguridad 5.3. Issue <strong>#22</strong>.
     *
     * <p><strong>Incluye la que hace la peticion.</strong> Quien pulsa esto acaba de perder el
     * telefono y lo que quiere es que no quede ninguna abierta; dejar la suya obligaria a decidir
     * cual es la suya --que es justo lo que no se puede emparejar entre las dos mitades-- y a
     * explicarle por que sigue dentro.
     *
     * <p>Publica {@link SessionsSignedOut}, que va a la bitacora de plataforma.
     *
     * @param actor quien lo pide, que hoy es siempre la propia cuenta
     * @return cuantas sesiones del BFF se cerraron
     * @throws bo.coprop.shared.ResourceNotFound si la cuenta no existe
     */
    int signOutEverywhere(java.util.UUID accountId, String actor);
}
