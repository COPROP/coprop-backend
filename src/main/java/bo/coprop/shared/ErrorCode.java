package bo.coprop.shared;

/**
 * Catalogo de codigos de error del contrato de la API.
 *
 * <p>El codigo es la parte estable del contrato: el cliente ramifica por el, nunca por el texto
 * del mensaje ni por el titulo, que pueden cambiar sin aviso. Traducir un codigo a su estado HTTP
 * es trabajo de la capa web; aqui no se conoce HTTP.
 */
public enum ErrorCode {
    /** La entrada no cumple las restricciones declaradas. Viaja con la lista de campos. */
    VALIDATION,

    /**
     * El cuerpo de la peticion no se pudo leer: no es JSON valido.
     *
     * <p>Se separa de {@link #VALIDATION} porque son problemas distintos y el cliente hace cosas
     * distintas. Un cuerpo mal formado es un defecto del cliente y no tiene campos que señalar;
     * un valor inadmisible dentro de un JSON correcto si los tiene, y sale como
     * {@code VALIDATION} con su lista. El RFC 9110 reserva el 400 para lo primero y el 422 para
     * lo segundo.
     */
    UNREADABLE_BODY,

    /**
     * El recurso no existe. Tambien cubre el acceso a un condominio sin membresia activa: se
     * responde lo mismo que si no existiera, para no revelar que condominios hay (Seguridad 7.2).
     */
    NOT_FOUND,

    /** Transicion de estado invalida o choque de concurrencia. */
    CONFLICT,

    /** La peticion esta bien formada, pero una regla del dominio la rechaza. */
    BUSINESS_RULE,

    /** No hay identidad: falta el token o no es valido. */
    NOT_AUTHENTICATED,

    /**
     * Hay identidad y el rol alcanza, pero la operacion es sensible y la autenticacion no es lo
     * bastante fuerte ni lo bastante reciente (Seguridad 7.3).
     *
     * <p>Es distinto de {@link #NOT_AUTHENTICATED} --ahi no hay sesion y aqui si-- y distinto de
     * {@link #NO_PERMISSION} --ahi el rol no alcanza y aqui alcanza--. Lo que falta no es quien es ni
     * que puede, sino <strong>cuando y como</strong> lo demostro: el cliente no tiene que mandar al
     * usuario al login ni decirle que pida permiso, sino pedirle su segundo factor y repetir la
     * misma operacion.
     *
     * <p>Sale como <strong>401</strong> y no como 403, con la cabecera de desafio del RFC 9470. Es
     * la unica negativa de la API que se responde con 401 teniendo sesion valida, y es a proposito:
     * el 401 es el estado que lleva un desafio de autenticacion, y aqui lo que se desafia es el
     * nivel de la autenticacion, no su existencia.
     */
    REAUTHENTICATION_REQUIRED,

    /** Hay identidad y membresia activa, pero el rol no alcanza. */
    NO_PERMISSION,

    /**
     * El token es valido pero en COPROP no corresponde a ninguna cuenta que pueda entrar.
     *
     * <p>Es distinto de {@link #NO_PERMISSION} y por eso tiene codigo propio: ahi hay una cuenta a
     * la que le falta rol, y aqui no hay cuenta, asi que al usuario no se le ofrece reintentar
     * sino pedir su invitacion. El acceso lo da la invitacion, no el haberse autenticado
     * (Seguridad 10.1).
     *
     * <p>Sale igual cuando no hay cuenta con ese correo, cuando esta bloqueada y cuando esta dada
     * de baja: distinguirlos le diria a cualquiera con una cuenta de Google si un correo esta
     * registrado en la plataforma.
     */
    NO_ACCOUNT,

    /** Fallo no previsto. Nunca lleva detalle al cliente. */
    INTERNAL
}
