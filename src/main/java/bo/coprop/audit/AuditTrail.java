package bo.coprop.audit;

import bo.coprop.shared.Pagina;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * La bitacora: que paso, quien lo hizo y cuando. Issue #21.
 *
 * <p>No tiene operacion de escritura, y es a proposito. <strong>Nadie escribe en la bitacora</strong>:
 * las filas aparecen porque un modulo publico un evento que implementa
 * {@link bo.coprop.shared.EventoAuditable}, y este modulo los escucha. Un metodo para registrar a
 * mano seria la primera forma de que una accion quedara auditada a medias --o dos veces-- segun se
 * acordara quien la escribio.
 *
 * <p>El oyente corre <strong>despues del commit de quien publico y en su propia transaccion</strong>,
 * asi que la fila existe en cuanto la accion termina: no hay que esperar a nada. El porque de no
 * hacerlo asincrono esta en el propio oyente, y tiene que ver con que la IP vive en el MDC.
 *
 * <p>Tampoco hay forma de modificar ni de borrar una fila. Lo impide un disparador en la base
 * (migracion V9), no la ausencia de un metodo aqui: una barrera que solo existe en Java se salta con
 * un {@code psql}.
 *
 * <p>La consulta esta <strong>siempre acotada a un condominio</strong>, como el resto del dominio:
 * la bitacora de un edificio no la ve el vecino. Las acciones de plataforma --conceder el
 * superadministrador, el primer login de una cuenta-- no cuelgan de ninguno y por eso no salen aqui;
 * su consulta llega con el issue #20, que es el que trae la operacion de concederlo.
 */
public interface AuditTrail {

    /**
     * Las acciones de un condominio, de la mas reciente a la mas vieja.
     *
     * <p>Los tres filtros son los del segundo criterio del issue: actor, entidad y rango de fechas.
     * Se combinan, y cualquiera puede venir nulo; todos nulos devuelve la bitacora entera del
     * condominio.
     *
     * <p><strong>Pagina siempre</strong>, incluso sin filtros. Un condominio de doscientas unidades
     * acumula miles de filas en un año, y un endpoint que las devolviera todas se cae el dia que
     * mas falta hace. Es el primer endpoint paginado del sistema, y por el se movio {@code Pagina} a
     * {@code shared}.
     *
     * @param actor el identificador de cuenta de quien hizo la accion; nulo para no filtrar
     * @param entidad el tipo de cosa sobre la que se actuo --{@code membresia},
     *     {@code invitacion}--; nulo para no filtrar
     * @param desde inclusive; nulo para no acotar por abajo
     * @param hasta exclusive, para que dos consultas consecutivas no devuelvan la misma fila dos
     *     veces; nulo para no acotar por arriba
     */
    Pagina<AuditEntryView> ofCondominium(
            UUID condominiumId,
            @Nullable String actor,
            @Nullable String entidad,
            @Nullable Instant desde,
            @Nullable Instant hasta,
            int pagina,
            int tamano);

    /**
     * Borra lo anterior a esa fecha y devuelve cuantas filas se fueron.
     *
     * <p>La retencion es de un año (Seguridad 10.1) y de esto se encarga una tarea diaria; recibe la
     * fecha en vez de calcularla para que un test pueda comprobarla sin esperar doce meses, igual
     * que el resto del sistema con el {@code Clock}.
     *
     * <p>Es la <strong>unica</strong> forma de que una fila desaparezca, y no pasa por un
     * {@code DELETE} normal: el disparador de la base los rechaza todos. Por dentro llama a la
     * funcion que la migracion V9 declara para esto.
     */
    long purgar(Instant hasta);
}
