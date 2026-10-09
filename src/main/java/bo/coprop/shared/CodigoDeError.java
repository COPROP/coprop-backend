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

    /** Fallo no previsto. Nunca lleva detalle al cliente. */
    INTERNO
}
