package bo.coprop.identity.internal;

import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.MembershipView;
import bo.coprop.identity.Memberships;
import bo.coprop.shared.ControladorDeApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Membresias de un condominio: quien entra a este edificio y con que roles.
 *
 * <p>Cuelga del condominio, al contrario que {@link PersonController}: la persona es global y la
 * membresia es de un condominio concreto.
 *
 * <p>Conceder una membresia y leer el padron piden el permiso de invitar usuarios (#16); quien
 * puede conceder <em>esos</em> roles es una segunda regla, y de ahi {@code puedeConceder}. La baja
 * y la suspension son el <strong>#19</strong>, asi que aqui solo hay alta y consulta.
 */
@ControladorDeApi
@RestController
@RequestMapping("/condominios/{condominiumId}/membresias")
class MembershipController {

    private final Memberships membresias;

    MembershipController(Memberships membresias) {
        this.membresias = membresias;
    }

    // Dos reglas en una: poder invitar, y poder conceder ESOS roles. El administrador del
    // condominio puede conceder cualquiera --incluidos los administrativos, decision explicita de
    // Seguridad 10.1-- y el superadministrador solo los administrativos, que es como pone al primer
    // administrador de un condominio recien creado.
    @PreAuthorize("@autorizacion.puedeConceder(authentication, #nueva.roles())")
    @PostMapping
    ResponseEntity<MembershipView> alta(
            @PathVariable UUID condominiumId, @Valid @RequestBody NuevaMembresia nueva, Principal quien) {
        MembershipView creada =
                membresias.grant(condominiumId, nueva.accountId(), nueva.roles(), nueva.invitedBy(), quien.getName());
        return ResponseEntity.created(URI.create("/api/v1/condominios/" + condominiumId + "/membresias/" + creada.id()))
                .body(creada);
    }

    /**
     * Las vigentes. Las cerradas son historia y las sacara el issue #19 cuando haga falta.
     *
     * <p>El padron del edificio --quien vive aqui y con que papel-- es dato de los vecinos, asi que
     * no lo ve cualquier miembro: lo exige el mismo permiso que reparte los roles, que es quien
     * tiene que mirar la lista para trabajar con ella. <strong>Quien quiera su propia membresia no
     * viene aqui</strong>: la trae {@code GET /api/v1/me} con sus roles, y por eso esto no necesita
     * una excepcion para lo propio.
     */
    @PreAuthorize("@autorizacion.puede(authentication, 'INVITAR_USUARIOS')")
    @GetMapping
    List<MembershipView> listar(@PathVariable UUID condominiumId) {
        return membresias.ofCondominium(condominiumId);
    }

    /** Una membresia del condominio, para quien reparte los roles. Misma regla que el listado. */
    @PreAuthorize("@autorizacion.puede(authentication, 'INVITAR_USUARIOS')")
    @GetMapping("/{membershipId}")
    MembershipView consultar(@PathVariable UUID condominiumId, @PathVariable UUID membershipId) {
        return membresias.find(condominiumId, membershipId);
    }

    /**
     * Lo que hace falta para conceder una membresia.
     *
     * <p>El condominio no viaja aqui: va en la ruta, por lo mismo que en el alta de unidades. Asi
     * no puede haber discrepancia entre el condominio de la URL y el del cuerpo.
     *
     * @param roles al menos uno; el diagrama de Seguridad 2 marca esa relacion como obligatoria
     * @param invitedBy la cuenta que invito; nulo en la del primer administrador
     */
    record NuevaMembresia(
            @jakarta.validation.constraints.NotNull UUID accountId,
            @NotEmpty Set<MembershipRole> roles,
            @Nullable UUID invitedBy) {}
}
