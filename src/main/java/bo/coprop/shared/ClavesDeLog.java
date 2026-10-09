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
     * <p>Todavia no lo rellena nadie: no hay condominios hasta M1. Lo pondra el filtro que describe
     * Seguridad 7.2, con el issue **#17**.
     */
    public static final String CONDOMINIO = "condominiumId";

    /**
     * Persona que ejecuta la operacion.
     *
     * <p>Todavia no lo rellena nadie: no hay identidad hasta M1. Llega con el issue **#13**.
     */
    public static final String ACTOR = "actorId";

    private ClavesDeLog() {}
}
