package bo.coprop.shared;

/**
 * Claves del contexto de logging (MDC).
 *
 * <p>Son el contrato entre quien las pone y quien lee los logs, y por eso viven aqui y no en el
 * modulo que las rellena: cuando el filtro de condominio del issue #17 empiece a poner el suyo,
 * tiene que usar exactamente la misma cadena que ya salia en los logs, o buscar por ella dejara de
 * funcionar sin que nadie se entere.
 *
 * <p>El formato de log estructurado vuelca todo el MDC, asi que una clave nueva aparece en los
 * logs sin tocar nada mas.
 */
public final class ClavesDeLog {

    /**
     * Identificador de la request. Todas las lineas de una misma peticion lo llevan igual, que es
     * el criterio de aceptacion del issue #6. Lo pone {@code FiltroDeTrazas}.
     */
    public static final String TRAZA = "traceId";

    /**
     * Condominio en cuyo contexto ocurre la operacion.
     *
     * <p>Lo pone el filtro de condominio que describe Seguridad 7.2 (issue #17), con el condominio
     * de la ruta y solo despues de comprobar que quien pide tiene membresia activa en el.
     */
    public static final String CONDOMINIO = "condominiumId";

    /**
     * Persona que ejecuta la operacion.
     *
     * <p>Lo pone el filtro de identidad (issue #13), con el identificador de la cuenta de COPROP y
     * no con el sujeto de Keycloak: es el mismo valor que queda como actor en eventos y bitacora.
     */
    public static final String ACTOR = "actorId";

    /**
     * Desde donde llega la peticion.
     *
     * <p>Lo pone {@code FiltroDeTrazas} (issue #21), junto con la traza y por el mismo motivo: es un
     * hecho de la peticion y no del dominio. La bitacora la necesita --5.2 la pide en
     * {@code AuditLog}-- y ningun evento de dominio puede llevarla, porque un evento no sabe de
     * HTTP.
     *
     * <p>Detras de un proxy, {@code getRemoteAddr()} devuelve el del proxy: lo corrige
     * {@code server.forward-headers-strategy}, que en produccion esta puesto.
     */
    public static final String IP = "ip";

    private ClavesDeLog() {}
}
