package bo.coprop.shared;

/**
 * Catalogo de codigos de error del contrato de la API.
 *
 * <p>El codigo es la parte estable del contrato: el cliente ramifica por el, nunca por el texto
 * del mensaje ni por el titulo, que pueden cambiar sin aviso. Traducir un codigo a su estado HTTP
 * es trabajo de la capa web; aqui no se conoce HTTP.
 */
public enum CodigoDeError {
    /** La entrada no cumple las restricciones declaradas. Viaja con la lista de campos. */
    VALIDACION,

    /**
     * El cuerpo de la peticion no se pudo leer: no es JSON valido.
     *
     * <p>Se separa de {@link #VALIDACION} porque son problemas distintos y el cliente hace cosas
     * distintas. Un cuerpo mal formado es un defecto del cliente y no tiene campos que señalar;
     * un valor inadmisible dentro de un JSON correcto si los tiene, y sale como
     * {@code VALIDACION} con su lista. El RFC 9110 reserva el 400 para lo primero y el 422 para
     * lo segundo.
     */
    CUERPO_ILEGIBLE,

    /**
     * El recurso no existe. Tambien cubre el acceso a un condominio sin membresia activa: se
     * responde lo mismo que si no existiera, para no revelar que condominios hay (Seguridad 7.2).
     */
    NO_ENCONTRADO,

    /** Transicion de estado invalida o choque de concurrencia. */
    CONFLICTO,

    /** La peticion esta bien formada, pero una regla del dominio la rechaza. */
    REGLA_DE_NEGOCIO,

    /** No hay identidad: falta el token o no es valido. */
    NO_AUTENTICADO,

    /** Hay identidad y membresia activa, pero el rol no alcanza. */
    SIN_PERMISO,

    /**
     * El token es valido pero en COPROP no corresponde a ninguna cuenta que pueda entrar.
     *
     * <p>Es distinto de {@link #SIN_PERMISO} y por eso tiene codigo propio: ahi hay una cuenta a
     * la que le falta rol, y aqui no hay cuenta, asi que al usuario no se le ofrece reintentar
     * sino pedir su invitacion. El acceso lo da la invitacion, no el haberse autenticado
     * (Seguridad 10.1).
     *
     * <p>Sale igual cuando no hay cuenta con ese correo, cuando esta bloqueada y cuando esta dada
     * de baja: distinguirlos le diria a cualquiera con una cuenta de Google si un correo esta
     * registrado en la plataforma.
     */
    SIN_CUENTA,

    /** Fallo no previsto. Nunca lleva detalle al cliente. */
    INTERNO
}
