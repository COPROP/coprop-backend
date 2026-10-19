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
public enum Scope {

    /** No puede. Es el "—" de la matriz, y es el valor por defecto de todo lo que no se declara. */
    NONE,

    /**
     * Puede, sobre lo suyo.
     *
     * <p><strong>Un permiso con este alcance no autoriza por si solo</strong>: dice que la accion le
     * corresponde al rol, no que este recurso sea suyo. Quien lo comprueba es la cadena de
     * autorizacion del issue <strong>#15</strong> --{@code AccessControl}, en el modulo
     * {@code api}--, que mira si la unidad es de esa persona: propietaria vigente, ocupante, o
     * responsable de sus cobros, como describe Seguridad 7.2. Si no lo es, responde <strong>404 y
     * no 403</strong>: un 403 confirmaria que esa unidad existe.
     *
     * <p>De ahi que este valor sea mayor que {@link #NONE} y menor que {@link #ALL} en el orden
     * del enum: {@code PermissionMatrix} compara alcances para quedarse con el mayor de los roles de
     * una membresia, y el orden de declaracion es el que decide.
     */
    OWN,

    /** Puede, en todo el alcance del rol. Es el "Sí" de la matriz. */
    ALL
}
