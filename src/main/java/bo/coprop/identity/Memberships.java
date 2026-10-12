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
     * @throws bo.coprop.shared.RecursoNoEncontrado si la cuenta no existe
     * @throws bo.coprop.shared.ReglaDeNegocioViolada si la cuenta ya tiene membresia vigente en
     *     ese condominio --el segundo criterio del issue #11-- o si no llega ningun rol
     */
    MembershipView grant(
            UUID condominiumId, UUID accountId, Set<MembershipRole> roles, @Nullable UUID invitedBy, String actor);

    /**
     * Las membresias vigentes de un condominio.
     *
     * <p>Solo las vigentes: las cerradas son historia y quien las necesite las pedira cuando el
     * issue #19 le de una operacion para ello.
     */
    List<MembershipView> ofCondominium(UUID condominiumId);

    /**
     * La membresia vigente de una cuenta en un condominio, si la tiene.
     *
     * <p>Es lo que leera el filtro de aislamiento del issue <strong>#17</strong> para decidir si
     * esta peticion puede ver este condominio.
     */
    @Nullable
    MembershipView of(UUID condominiumId, UUID accountId);

    /**
     * @throws bo.coprop.shared.RecursoNoEncontrado si no existe, o si no es de ese condominio
     */
    MembershipView find(UUID condominiumId, UUID membershipId);
}
