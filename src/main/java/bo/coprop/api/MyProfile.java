package bo.coprop.api;

import bo.coprop.condominium.CondominiumView;
import bo.coprop.condominium.RelatedUnitView;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.PersonView;
import java.util.List;
import java.util.Set;

/**
 * Lo que devuelve {@code GET /api/v1/me}: quien eres y donde estas. Issue #14.
 *
 * <p>Es la primera llamada de todo cliente despues del login, y existe porque ninguna pantalla se
 * puede pintar sin ella: el analisis 5.4 la describe como "usuario y membresias por condominio", y
 * de aqui sale tanto el selector de condominio como que menu se le muestra a cada rol.
 *
 * <p><strong>Compone vistas de los dos modulos en vez de aplanarlas.</strong> El condominio viaja
 * como su {@link CondominiumView} y la persona como su {@link PersonView}, con los nombres de campo
 * que ya tienen en {@code /condominiums/{id}} y en {@code /people/{id}}. Aplanarlo daria un JSON
 * mas corto y un segundo contrato que mantener al dia a mano: el dia que al condominio se le añada
 * un campo, aqui saldria solo.
 *
 * <p>Los nombres propios de este sobre van en español, como el resto de lo que vive en {@code api}
 * --{@link PagedResponse}, las extensiones de problem+json--, y los de dentro en ingles, porque son los
 * que el analisis le da al dominio.
 *
 * @param account la cuenta con la que entro, no la persona: son dos cosas y la pantalla de perfil
 *     muestra las dos
 * @param managesThePlatform si es superadministrador. Va aqui y no como un rol mas de la lista
 *     porque no cuelga de ningun condominio (Seguridad 3), y meterlo en {@code roles} de alguno
 *     haria creer al cliente que ese condominio se lo dio
 * @param condominiums solo los de membresia <strong>activa</strong>, ordenados por nombre. Una
 *     membresia suspendida no se lista: el filtro de aislamiento le responderia 404 a todo lo de ese
 *     condominio, asi que ofrecerlo en el selector seria ofrecer una puerta cerrada
 */
public record MyProfile(
        PersonView person, AccountView account, boolean managesThePlatform, List<MyCondominium> condominiums) {

    /**
     * Un condominio al que entro, con lo que es suyo dentro.
     *
     * @param roles los de su membresia en <strong>este</strong> condominio, que es lo que decide que
     *     puede hacer aqui
     * @param units las unidades con las que tiene alguna relacion vigente. Vacio para quien
     *     administra y no es propietario, que es lo normal
     */
    public record MyCondominium(CondominiumView condominium, Set<MembershipRole> roles, List<RelatedUnitView> units) {}
}
