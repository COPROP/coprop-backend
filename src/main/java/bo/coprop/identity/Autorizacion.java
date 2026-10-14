package bo.coprop.identity;

import org.springframework.security.core.Authentication;

/**
 * Lo que preguntan los controladores antes de dejar pasar una operacion. Es el {@code @authz} de
 * Seguridad 7.2.
 *
 * <p>Se invoca desde {@code @PreAuthorize} por <strong>nombre de bean</strong>
 * ({@code @autorizacion}), que es una cadena: por eso un controlador de {@code condominium} puede
 * usarlo sin importar nada de {@code identity} y sin romper la regla de dependencias. El precio es
 * que el compilador no ve ese uso, asi que lo sostienen los tests de cada endpoint.
 *
 * <p>No consulta la base para lo que ya sabe: los roles del condominio de la ruta los dejo el filtro
 * del issue #17 como autoridades de la peticion, y de ahi los lee. Solo baja a la base cuando la
 * ruta no tiene condominio.
 */
public interface Autorizacion {

    /**
     * Hasta donde le llega ese permiso a quien hace esta peticion, en el condominio de la ruta.
     *
     * <p>Existe porque {@link #puede} aplasta tres valores en dos: para la cadena del issue
     * <strong>#15</strong> no es lo mismo un {@link Alcance#NINGUNO} --el rol no hace esto, y la
     * respuesta es 403-- que un {@link Alcance#PROPIO}, donde falta comprobar si el recurso es
     * suyo y la respuesta, si no lo es, tiene que ser 404. Quien compone las dos cosas vive en
     * {@code api}, que es el unico modulo que ve a la vez la matriz y las relaciones de la unidad.
     *
     * <p>Devuelve el alcance mayor de los roles de la peticion, nunca nulo.
     *
     * @param permiso el nombre de un {@link Permiso}
     */
    Alcance alcanceDe(Authentication quien, String permiso);

    /**
     * Si quien hace esta peticion puede hacer esto <strong>en el condominio de la ruta</strong>.
     *
     * <p>No recibe el condominio porque no le hace falta: las autoridades de la peticion son las de
     * la membresia en ese condominio y de ningun otro, y eso lo garantiza el filtro, que ya
     * respondio 404 si no habia membresia activa.
     *
     * <p>Un permiso de alcance {@link Alcance#PROPIO} cuenta como permitido aqui, porque lo
     * contrario dejaria al propietario sin su propio estado de cuenta. <strong>Por eso este metodo
     * no basta para un endpoint que recibe un recurso en la ruta</strong>: ahi hay que preguntar
     * por el recurso, con {@link #alcanceDe} y las relaciones de la unidad. Vale tal cual para las
     * operaciones de alcance de condominio --dar de alta una unidad, cambiar la configuracion--,
     * que son las que no nombran ningun recurso propio.
     *
     * @param permiso el nombre de un {@link Permiso}
     */
    boolean puede(Authentication quien, String permiso);

    /**
     * Si puede hacerlo <strong>en algun condominio</strong>.
     *
     * <p>Para las rutas que no cuelgan de uno: dar de alta una persona o abrirle cuenta. La persona
     * es global (Seguridad 2), pero quien la da de alta lo hace para su edificio, asi que basta con
     * que administre alguno. Esto si consulta la base, porque en una ruta global no hay autoridades
     * de condominio que leer.
     */
    boolean puedeEnAlgunCondominio(Authentication quien, String permiso);

    /**
     * Si puede conceder <strong>esos</strong> roles.
     *
     * <p>Son dos reglas distintas y por eso no cabe en {@link #puede}: el administrador del
     * condominio puede asignar cualquier rol, incluidos {@code CONDO_ADMIN} y {@code FINANCE_ADMIN}
     * --decision explicita de Seguridad 10.1, que queda en auditoria--, mientras que el
     * superadministrador solo puede asignar roles administrativos, que es la celda "Solo
     * administradores" de la matriz.
     */
    boolean puedeConceder(Authentication quien, java.util.Set<MembershipRole> roles);
}
