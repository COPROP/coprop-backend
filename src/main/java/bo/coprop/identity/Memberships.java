package bo.coprop.identity;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * El vinculo entre una cuenta y un condominio: quien entra a que edificio y con que roles.
 *
 * <p>La membresia cuelga de la cuenta y no de la persona, como la modela Seguridad 2. La
 * consecuencia practica es que un propietario que nunca se registro no tiene membresia, y no le
 * hace falta: existe como persona con su titularidad en {@code UnitRelations} desde que se carga
 * el edificio. La membresia aparece cuando acepta la invitacion.
 */
public interface Memberships {

    /**
     * Vincula una cuenta a un condominio con uno o mas roles.
     *
     * <p>Exige al menos un rol: el diagrama de Seguridad 2 marca esa relacion como obligatoria,
     * de uno a muchos. Una membresia sin roles no autoriza nada y solo sirve para confundir a
     * quien lea la tabla.
     *
     * <p>Publica {@link MembershipGranted}.
     *
     * @param invitedBy la cuenta que invito; nulo en la del primer administrador, que no la invita
     *     nadie
     * @param actor quien la concede, para la bitacora
     * @throws bo.coprop.shared.ResourceNotFound si la cuenta no existe
     * @throws bo.coprop.shared.BusinessRuleViolated si la cuenta ya tiene membresia vigente en
     *     ese condominio --el segundo criterio del issue #11-- o si no llega ningun rol
     */
    MembershipView grant(
            UUID condominiumId, UUID accountId, Set<MembershipRole> roles, @Nullable UUID invitedBy, String actor);

    /**
     * Da de baja una membresia: esa cuenta deja de pertenecer a ese condominio. Issue
     * <strong>#19</strong>.
     *
     * <p><strong>No borra nada</strong>: cierra la vigencia de la fila, que es lo que hace cierto el
     * segundo criterio del issue --lo historico se conserva-- y lo que permite volver a entrar mas
     * adelante sin chocar con el indice de "una sola vigente". El estado tampoco se toca: la fila
     * se queda diciendo si estaba activa o suspendida cuando se la dio de baja.
     *
     * <p>Surte efecto en la <strong>siguiente peticion</strong>, sin esperar a que caduque ningun
     * token: los roles y la membresia se resuelven en cada request contra la base (Seguridad 4.2), y
     * el filtro de condominio responde 404 en cuanto la fila esta cerrada. Eso es el primer criterio
     * del issue, y no depende de que se cierren las sesiones.
     *
     * <p>Las sesiones se cierran <strong>solo si esa cuenta se queda sin ningun condominio al que
     * entrar</strong> y no administra la plataforma. Una sesion de COPROP no es de un condominio,
     * asi que cerrarla en toda baja echaria de un edificio a quien sigue perteneciendo a otro, y
     * Seguridad 6.3 dice de la baja justo lo contrario: "afecta solo a ese condominio".
     *
     * <p>Publica {@link MembershipRevoked}, que es donde queda el motivo: la fila cerrada dice
     * cuando y la bitacora por que.
     *
     * @param motivo por que se le da de baja. Obligatorio, lo exige el alcance del issue
     * @throws bo.coprop.shared.ResourceNotFound si la membresia no existe o no es de ese
     *     condominio
     * @throws bo.coprop.shared.StateConflict si ya estaba dada de baja
     * @throws bo.coprop.shared.BusinessRuleViolated si quien la da de baja es su propio dueño, o si
     *     es la ultima membresia con {@code CONDO_ADMIN} del condominio
     */
    MembershipView revoke(UUID condominiumId, UUID membershipId, String motivo, String actor);

    /**
     * Suspende una membresia sin cerrarla. Issue <strong>#19</strong>.
     *
     * <p>La persona sigue siendo miembro del condominio, con sus roles intactos, y mientras este
     * suspendida el filtro de aislamiento le responde lo mismo que a quien no pertenece. Es lo que
     * el issue llama "suspension temporal sin borrar la membresia", y la diferencia con la baja es
     * que esto se deshace con {@link #reinstate} y la baja no.
     *
     * <p>Cierra sesiones con la misma regla que la baja, y por el mismo motivo: quien queda
     * suspendido en su unico condominio no tiene adonde entrar.
     *
     * <p>Publica {@link MembershipSuspended}.
     *
     * @throws bo.coprop.shared.ResourceNotFound si la membresia no existe o no es de ese
     *     condominio
     * @throws bo.coprop.shared.StateConflict si esta dada de baja o ya estaba suspendida
     * @throws bo.coprop.shared.BusinessRuleViolated si quien la suspende es su propio dueño, o si
     *     es la ultima membresia con {@code CONDO_ADMIN} del condominio
     */
    MembershipView suspend(UUID condominiumId, UUID membershipId, String motivo, String actor);

    /**
     * Levanta la suspension. Issue <strong>#19</strong>.
     *
     * <p>Vuelve a dar acceso en la siguiente peticion y la persona no tiene que hacer nada: sus
     * roles nunca estuvieron en su token. <strong>No le devuelve la sesion</strong>, que si se le
     * cerro esta cerrada; tiene que volver a entrar.
     *
     * <p>Publica {@link MembershipReinstated}.
     *
     * @throws bo.coprop.shared.ResourceNotFound si la membresia no existe o no es de ese
     *     condominio
     * @throws bo.coprop.shared.StateConflict si esta dada de baja --una baja no se deshace, se
     *     concede una membresia nueva-- o si ya estaba activa
     */
    MembershipView reinstate(UUID condominiumId, UUID membershipId, String motivo, String actor);

    /**
     * Cambia los roles de una membresia vigente. Issue <strong>#20</strong>.
     *
     * <p>Recibe el conjunto <strong>completo</strong>, no un rol que se añade o se quita: los roles
     * de una membresia son un solo valor y se sustituyen de una vez. Asi la operacion es idempotente
     * --repetirla deja lo mismo-- y la bitacora puede guardar las dos mitades, que es lo que se
     * pregunta despues: no que roles tiene, sino cual le quitaron.
     *
     * <p><strong>Edita la fila; no la cierra ni abre otra.</strong> La vigencia de una membresia
     * significa el tiempo que esa cuenta pertenecio al condominio, y en eso se apoya el padron del
     * #19: cerrarla aqui haria que apareciera una baja que nadie dio. La historia de los roles vive
     * en la bitacora, que es append-only y guarda actor, motivo y fecha.
     *
     * <p>Surte efecto en la <strong>siguiente peticion</strong>: los roles se resuelven en cada
     * request contra la base (Seguridad 4.2) y nunca estuvieron en el token. Lo que si hace falta
     * ademas es cerrar las sesiones cuando el cambio <strong>retira un rol administrativo</strong>,
     * que es lo que pide Seguridad 8; cuando solo añade, o cuando los roles que se van no son
     * administrativos, no se cierra nada.
     *
     * <p>Publica {@link MembershipRolesChanged}, y <strong>solo si algo cambio</strong>: una
     * peticion que repite los roles que ya hay no deja fila en la bitacora. La bitacora registra
     * cambios, no peticiones.
     *
     * @param roles el conjunto completo que la membresia tendra. Al menos uno, y ninguno puede ser
     *     {@code PLATFORM_ADMIN}: ese rol no cuelga de un condominio y se concede por su propia
     *     operacion, {@link Identities#grantPlatformAdmin}
     * @param motivo por que se le cambian. Obligatorio, por lo mismo que en la baja: acaba en la
     *     bitacora y es lo unico que explica el cambio dentro de un año
     * @throws bo.coprop.shared.ResourceNotFound si la membresia no existe o no es de ese
     *     condominio
     * @throws bo.coprop.shared.StateConflict si esta dada de baja
     * @throws bo.coprop.shared.BusinessRuleViolated si no llega ningun rol, si llega
     *     {@code PLATFORM_ADMIN}, si quien lo pide es el dueño de esa membresia, o si el cambio
     *     dejaria al condominio sin ningun {@code CONDO_ADMIN} activo
     */
    MembershipView changeRoles(
            UUID condominiumId, UUID membershipId, Set<MembershipRole> roles, String motivo, String actor);

    /**
     * Las membresias vigentes de un condominio.
     *
     * <p>Solo las vigentes. El padron con las cerradas lo da {@link #allOfCondominium}.
     */
    List<MembershipView> ofCondominium(UUID condominiumId);

    /**
     * Todas las membresias que ha tenido un condominio, vigentes y cerradas, de la mas reciente a
     * la mas antigua. Issue <strong>#19</strong>.
     *
     * <p>Es la mitad consultable del segundo criterio del issue: quien estuvo y ya no esta sigue
     * saliendo, con las fechas entre las que pertenecio. Lo que pregunta el administrador cuando
     * alguien reclama algo de hace tres meses es precisamente eso.
     *
     * <p>Una misma cuenta puede aparecer varias veces: volver a entrar despues de una baja abre una
     * fila nueva, que es lo que el indice parcial de la migracion V5 deja pasar a proposito.
     */
    List<MembershipView> allOfCondominium(UUID condominiumId);

    /**
     * La membresia vigente de una cuenta en un condominio, si la tiene.
     *
     * <p>Es lo que leera el filtro de aislamiento del issue <strong>#17</strong> para decidir si
     * esta peticion puede ver este condominio.
     */
    @Nullable
    MembershipView of(UUID condominiumId, UUID accountId);

    /**
     * Las membresias vigentes de una cuenta, en todos los condominios.
     *
     * <p>Es lo que responde {@code GET /api/v1/me} (issue <strong>#14</strong>): el cliente no sabe
     * en que condominios esta hasta que se lo digan, asi que esta consulta no puede recibir uno. Es
     * la segunda excepcion declarada a la regla de que el condominio es el primer argumento de toda
     * operacion del dominio, y por el mismo motivo que {@link People}: la pregunta es precisamente
     * cuales son.
     *
     * <p>Devuelve las vigentes, activas y suspendidas por igual. Que hacer con una suspendida es de
     * quien llama: el filtro de aislamiento la rechaza y {@code /me} no la lista.
     */
    List<MembershipView> ofAccount(UUID accountId);

    /**
     * @throws bo.coprop.shared.ResourceNotFound si no existe, o si no es de ese condominio
     */
    MembershipView find(UUID condominiumId, UUID membershipId);
}
