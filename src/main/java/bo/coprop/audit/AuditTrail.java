package bo.coprop.audit;

import bo.coprop.shared.PagedResponse;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * La bitacora: que paso, quien lo hizo y cuando. Issue #21.
 *
 * <p>No tiene operacion de escritura, y es a proposito. <strong>Nadie escribe en la bitacora</strong>:
 * las filas aparecen porque un modulo publico un evento que implementa
 * {@link bo.coprop.shared.AuditableEvent}, y este modulo los escucha. Un metodo para registrar a
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
 * <p>Hay <strong>dos consultas y no una</strong>, y es deliberado: {@link #ofCondominium} esta
 * acotada a un condominio, como el resto del dominio --la bitacora de un edificio no la ve el
 * vecino--, y {@link #ofPlatform} devuelve las acciones que no cuelgan de ninguno. Una sola con el
 * condominio opcional seria una donde olvidarse de pasarlo lo muestra todo, y los dos publicos no
 * son el mismo: el administrador de un edificio no tiene por que ver acciones de la plataforma
 * entera.
 */
public interface AuditTrail {

    /**
     * Las acciones de un condominio, de la mas reciente a la mas vieja.
     *
     * <p>Los tres filtros son los del segundo criterio del issue: actor, entidad y rango de fechas.
     * Se combinan, y cualquiera puede venir nulo; todos nulos devuelve la bitacora entera del
     * condominio.
     *
     * <p><strong>PagedResponse siempre</strong>, incluso sin filtros. Un condominio de doscientas unidades
     * acumula miles de filas en un año, y un endpoint que las devolviera todas se cae el dia que
     * mas falta hace. Es el primer endpoint paginado del sistema, y por el se movio {@code PagedResponse} a
     * {@code shared}.
     *
     * @param actor el identificador de cuenta de quien hizo la accion; nulo para no filtrar
     * @param entidad el tipo de cosa sobre la que se actuo --{@code membresia},
     *     {@code invitacion}--; nulo para no filtrar
     * @param desde inclusive; nulo para no acotar por abajo
     * @param hasta exclusive, para que dos consultas consecutivas no devuelvan la misma fila dos
     *     veces; nulo para no acotar por arriba
     */
    PagedResponse<AuditEntryView> ofCondominium(
            UUID condominiumId,
            @Nullable String actor,
            @Nullable String entity,
            @Nullable Instant desde,
            @Nullable Instant until,
            int page,
            int tamano);

    /**
     * Las acciones de <strong>plataforma</strong>, las que no cuelgan de ningun condominio. Issue
     * <strong>#20</strong>.
     *
     * <p>Son las que van con {@code condominium_id} nulo: conceder y revocar el rol de
     * superadministrador, y el primer acceso de una cuenta. No salen en
     * {@link #ofCondominium} a proposito --el administrador de un edificio no tiene por que ver
     * acciones de la plataforma entera-- y por eso hacen falta las dos consultas y no una con el
     * condominio opcional: <strong>son dos publicos distintos</strong>, y una sola con un filtro que
     * se pueda omitir seria una donde olvidarse de pasarlo lo muestra todo.
     *
     * <p>Llega con este issue porque es el que trae la operacion de conceder el rol: hasta ahora la
     * unica fila de plataforma la escribia el arranque, y no habia a quien ensenarsela.
     *
     * <p>Mismos filtros, misma paginacion y mismo orden que la del condominio.
     */
    PagedResponse<AuditEntryView> ofPlatform(
            @Nullable String actor,
            @Nullable String entity,
            @Nullable Instant desde,
            @Nullable Instant until,
            int page,
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
    long purge(Instant until);
}
