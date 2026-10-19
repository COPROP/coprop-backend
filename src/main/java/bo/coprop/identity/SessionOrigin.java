package bo.coprop.identity;

/**
 * De cual de las dos puertas es una sesion. Issue <strong>#22</strong>.
 *
 * <p>Los nombres estan en español, como todo valor de enum del proyecto, y nombran lo que el
 * usuario reconoce --su navegador, su telefono-- y no la pieza tecnica que la guarda.
 */
public enum SessionOrigin {

    /**
     * La sesion de servidor del BFF: la que sostiene la cookie del navegador.
     *
     * <p>Vive en la tabla de la V6 y la gobierna esta aplicacion, asi que es la unica de la que se
     * sabe cuando caduca.
     */
    BROWSER,

    /**
     * La sesion en el proveedor de identidad.
     *
     * <p>Es la que hace que volver a entrar por la web no vuelva a pedir contraseña, y la que
     * sostiene el refresh token de la app movil. Sus tiempos los fija el realm.
     */
    PROVIDER
}
