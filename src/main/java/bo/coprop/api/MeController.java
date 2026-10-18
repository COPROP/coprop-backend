package bo.coprop.api;

import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.UnitRelations;
import bo.coprop.identity.Identities;
import bo.coprop.identity.MembershipStatus;
import bo.coprop.identity.MembershipView;
import bo.coprop.identity.Memberships;
import bo.coprop.identity.People;
import bo.coprop.shared.ControladorDeApi;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me}: quien eres y a que condominios entras. Issue #14.
 *
 * <p>Es la llamada con la que arranca todo cliente despues del login (analisis 5.4, y los dos
 * diagramas de secuencia de Seguridad 5). Si devuelve un solo condominio, el cliente entra directo;
 * si devuelve varios, pinta un selector.
 *
 * <p><strong>No recibe ningun identificador.</strong> Todo lo que necesita sale de la identidad que
 * dejo el filtro: pedir la cuenta en la ruta convertiria "mi perfil" en "el perfil de quien yo diga"
 * y haria falta decidir quien puede verlo. La consulta de una cuenta por identificador ya existe y
 * es otra cosa: {@code GET /api/v1/cuentas/{accountId}}.
 *
 * <p><strong>Sin {@code @PreAuthorize}, a proposito.</strong> No hay un permiso de la matriz que
 * encaje porque no hay nada que autorizar: lo unico que devuelve es lo propio de quien pregunta, y
 * quien no tiene cuenta ya no llego hasta aqui --el filtro de identidad responde 403
 * {@code SIN_CUENTA}--. Exigir un permiso dejaria sin perfil justo a quien acaba de entrar y
 * todavia no tiene membresia en ninguna parte.
 *
 * <p><strong>Devuelve 200 con la lista vacia a quien no tiene ninguna membresia activa</strong>, y
 * no un 404 ni un 403. Es una respuesta correcta a una pregunta sobre uno mismo: esa persona existe
 * y no esta en ningun condominio todavia. Es tambien el caso del superadministrador recien abierto
 * por el arranque, que es quien crea el primero.
 *
 * <p>Vive en {@code api} y no en un modulo de dominio porque es la unica respuesta que cruza
 * {@code identity} y {@code condominium}; el porque esta en el {@code package-info} de este paquete.
 */
@ControladorDeApi
@RestController
@RequestMapping("/me")
class MeController {

    private final People personas;
    private final Identities identidades;
    private final Memberships membresias;
    private final Condominiums condominios;
    private final UnitRelations relaciones;

    MeController(
            People personas,
            Identities identidades,
            Memberships membresias,
            Condominiums condominios,
            UnitRelations relaciones) {
        this.personas = personas;
        this.identidades = identidades;
        this.membresias = membresias;
        this.condominios = condominios;
        this.relaciones = relaciones;
    }

    @GetMapping
    MiPerfil consultar(Authentication quien) {
        var actor = IdentidadAutenticada.actorDe(quien);
        UUID cuenta = actor.accountId();
        UUID persona = actor.personId();

        return new MiPerfil(
                personas.find(persona),
                personas.findAccount(cuenta),
                identidades.esSuperadministrador(cuenta),
                misCondominios(cuenta, persona));
    }

    /**
     * Los condominios de las membresias activas, cada uno con sus roles y sus unidades.
     *
     * <p>Una consulta por condominio y no una en bloque: son los condominios de una persona, que
     * son uno o dos y rara vez mas de cinco. Si algun dia una administradora de muchos edificios lo
     * nota, lo que toca es una consulta en bloque en {@code Condominiums}, no cachear esto.
     */
    private List<MiPerfil.MiCondominio> misCondominios(UUID cuenta, UUID persona) {
        return membresias.ofAccount(cuenta).stream()
                .filter(membresia -> membresia.status() == MembershipStatus.ACTIVE)
                .map(membresia -> deLaMembresia(membresia, persona))
                // Por nombre y no por el orden en que se concedieron las membresias: el selector lo
                // lee una persona, y un orden que cambia entre dos peticiones se nota.
                .sorted(Comparator.comparing(mio -> mio.condominio().name()))
                .toList();
    }

    private MiPerfil.MiCondominio deLaMembresia(MembershipView membresia, UUID persona) {
        UUID condominio = membresia.condominiumId();
        return new MiPerfil.MiCondominio(
                condominios.find(condominio), membresia.roles(), relaciones.relatedTo(condominio, persona));
    }
}
