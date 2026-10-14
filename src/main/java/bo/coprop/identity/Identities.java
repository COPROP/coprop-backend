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
     * @param emailVerificado el {@code email_verified} del token
     */
    @Nullable
    AccountView signIn(String idpSubject, String email, boolean emailVerificado);

    /**
     * Si esa cuenta administra la plataforma (Seguridad 3).
     *
     * <p>Vive en su propia tabla y no en una membresia, porque no depende de ningun condominio, y
     * no viaja en el token, porque los roles se resuelven en cada peticion: quitarselo a alguien
     * tiene que surtir efecto ya, no cuando expire su token.
     */
    boolean esSuperadministrador(java.util.UUID accountId);
}
