package bo.coprop.api;

import bo.coprop.identity.Alcance;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.Permiso;
import java.util.List;
import java.util.Map;

/**
 * Lo que devuelve {@code GET /api/v1/parametricas/matriz-de-permisos}: la matriz de Seguridad 3 tal
 * como la aplica el backend. Issue #89.
 *
 * <p><strong>Sale de {@code MatrizDePermisos}, no de una copia.</strong> Es el punto entero: una
 * pantalla que pinte la matriz desde una tabla escrita a mano acaba diciendo una cosa mientras la
 * autorizacion hace otra, y de las dos la que manda no es la que se ve. Aqui las dos leen la misma
 * fuente, y el dia que una celda cambie la pantalla cambia con ella.
 *
 * <p><strong>Cada fila declara los siete roles, incluidos los que no pueden.</strong> Omitir los
 * {@link Alcance#NONE} daria una respuesta mas corta y obligaria al cliente a decidir que
 * significa una ausencia --no puede, o no se declaro--. Es la misma leccion del issue #16: la mitad
 * del valor de una matriz esta en los "--".
 *
 * @param roles los siete de Seguridad 3, en el orden en que los declara {@link MembershipRole}:
 *     primero el de plataforma, despues los seis de condominio
 * @param permisos una fila por permiso, en el orden de {@link Permiso}, que agrupa por ambito
 *     --plataforma, administracion, dinero, lo propio, reportes-- y no alfabeticamente
 */
public record MatrizDeRolesYPermisos(List<MembershipRole> roles, List<Fila> permisos) {

    /**
     * Un permiso y hasta donde llega cada rol con el.
     *
     * @param pideSegundoFactor si la operacion exige haber reautenticado hace poco (Seguridad 7.3).
     *     Va en la fila del permiso y no en la del rol porque no depende de quien pregunta: lo pide
     *     la operacion, a todos por igual
     * @param alcances los siete roles, siempre los siete
     */
    public record Fila(Permiso permiso, boolean pideSegundoFactor, Map<MembershipRole, Alcance> alcances) {}
}
