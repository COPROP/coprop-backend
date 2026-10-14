package bo.coprop.identity;

/**
 * Hasta donde llega un permiso para un rol: la diferencia entre "Sí", "Propias" y "—" de la matriz
 * de Seguridad 3.
 *
 * <p>La columna del propietario dice "Propias" en media docena de filas, y esa palabra no es un sí
 * mas pequeño: es una condicion sobre <em>cual</em> recurso, no sobre si la accion esta permitida.
 * Tratarla como un sí abriria las unidades del vecino; tratarla como un no dejaria al propietario
 * sin ver su propio estado de cuenta.
 */
public enum Alcance {

    /** No puede. Es el "—" de la matriz, y es el valor por defecto de todo lo que no se declara. */
    NINGUNO,

    /**
     * Puede, sobre lo suyo.
     *
     * <p>Quien lo comprueba de verdad es la cadena de autorizacion del issue <strong>#15</strong>,
     * que mira si la unidad es de esa persona --propietario vigente, o inquilino responsable del
     * servicio-- como describe Seguridad 7.2. Hasta entonces, un permiso con este alcance no
     * autoriza por si solo: dice que la accion le corresponde al rol, no que este recurso sea suyo.
     */
    PROPIO,

    /** Puede, en todo el alcance del rol. Es el "Sí" de la matriz. */
    TODO
}
