package bo.coprop.identity.internal;

import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.MembershipView;
import bo.coprop.identity.Memberships;
import bo.coprop.shared.ApiController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Membresias de un condominio: quien entra a este edificio y con que roles.
 *
 * <p>Cuelga del condominio, al contrario que {@link PersonController}: la persona es global y la
 * membresia es de un condominio concreto.
 *
 * <p>Conceder una membresia y leer el padron piden el permiso de invitar usuarios (#16); quien
 * puede conceder <em>esos</em> roles es una segunda regla, y de ahi {@code puedeConceder}.
 *
 * <p>La baja, la suspension y la reactivacion son el <strong>#19</strong>, y las tres piden
 * {@code puedeCambiar}, que es la misma regla del alta leida al reves: quien no puede nombrar a un
 * administrador tampoco puede quitarlo ni dejarlo fuera.
 *
 * <p>El cambio de roles es el <strong>#20</strong>, y es el unico que pide las <strong>dos</strong>
 * mitades a la vez: {@code puedeCambiar} por los que se van y {@code puedeConceder} por los que
 * llegan. El rol de plataforma no se reparte desde aqui --no cuelga de ningun condominio-- sino en
 * {@link PlatformAdminController}.
 */
@ApiController
@RestController
@RequestMapping("/condominios/{condominiumId}/membresias")
class MembershipController {

    private final Memberships memberships;

    MembershipController(Memberships memberships) {
        this.memberships = memberships;
    }

    // Dos reglas en una: poder invitar, y poder conceder ESOS roles. El administrador del
    // condominio puede conceder cualquiera --incluidos los administrativos, decision explicita de
    // Seguridad 10.1-- y el superadministrador solo los administrativos, que es como pone al primer
    // administrador de un condominio recien creado.
    // El segundo factor solo si entre los roles hay alguno administrativo (#22): lo sensible de
    // Seguridad 7.3 es nombrar a un administrador, no dar de alta a un propietario.
    @PreAuthorize("@authorization.canGrant(authentication, #body.roles())"
            + " and @reauthentication.ifAdminRole(authentication, #body.roles())")
    @PostMapping
    ResponseEntity<MembershipView> alta(
            @PathVariable UUID condominiumId, @Valid @RequestBody NewMembership body, Principal principal) {
        MembershipView created =
                memberships.grant(condominiumId, body.accountId(), body.roles(), body.invitedBy(), principal.getName());
        return ResponseEntity.created(
                        URI.create("/api/v1/condominios/" + condominiumId + "/membresias/" + created.id()))
                .body(created);
    }

    /**
     * El padron del edificio.
     *
     * <p>Quien vive aqui y con que papel es dato de los vecinos, asi que no lo ve cualquier miembro:
     * lo exige el mismo permiso que reparte los roles, que es quien tiene que mirar la lista para
     * trabajar con ella. <strong>Quien quiera su propia membresia no viene aqui</strong>: la trae
     * {@code GET /api/v1/me} con sus roles, y por eso esto no necesita una excepcion para lo propio.
     *
     * @param cerradas si incluir las membresias dadas de baja (issue #19). Por defecto no: lo que se
     *     pide casi siempre es quien esta hoy, y una lista que mezcle a los de antes sin pedirlo
     *     haria que el administrador invitara a gente que ya se fue creyendo que sigue dentro. Con
     *     {@code true} salen todas, de la mas reciente a la mas antigua, y la misma cuenta puede
     *     aparecer varias veces: volver a entrar despues de una baja abre una fila nueva
     */
    @PreAuthorize("@authorization.can(authentication, 'INVITE_USERS')")
    @GetMapping
    List<MembershipView> list(
            @PathVariable UUID condominiumId, @RequestParam(defaultValue = "false") boolean cerradas) {
        return cerradas ? memberships.allOfCondominium(condominiumId) : memberships.ofCondominium(condominiumId);
    }

    /** Una membresia del condominio, para quien reparte los roles. Misma regla que el listado. */
    @PreAuthorize("@authorization.can(authentication, 'INVITE_USERS')")
    @GetMapping("/{membershipId}")
    MembershipView consultar(@PathVariable UUID condominiumId, @PathVariable UUID membershipId) {
        return memberships.find(condominiumId, membershipId);
    }

    /**
     * La baja: esa cuenta deja de pertenecer a este condominio. Issue #19.
     *
     * <p><strong>Pide poder conceder los roles que la membresia tiene</strong>, no solo
     * {@code INVITE_USERS}, y es la misma regla del alta leida al reves: quien no puede nombrar
     * a un administrador tampoco puede quitarlo. Sin esto, el superadministrador --que segun la
     * matriz solo reparte roles administrativos-- podria dar de baja a cualquier propietario de
     * cualquier edificio, que es justo lo que esa celda acota.
     *
     * <p>Es un {@code POST} a un sub-recurso y no un {@code DELETE} sobre la membresia, al contrario
     * que la mudanza de un ocupante: la baja <strong>lleva motivo</strong>, el issue lo exige, y un
     * {@code DELETE} con cuerpo es algo que los intermediarios tratan como quieren. Ademas no borra:
     * responde la membresia cerrada, con su {@code validTo} puesto.
     */
    @PreAuthorize("@authorization.canChange(authentication, #condominiumId, #membershipId)")
    @PostMapping("/{membershipId}/baja")
    MembershipView terminate(
            @PathVariable UUID condominiumId,
            @PathVariable UUID membershipId,
            @Valid @RequestBody Reason reason,
            Principal principal) {
        return memberships.revoke(condominiumId, membershipId, reason.reason(), principal.getName());
    }

    /**
     * La suspension temporal: sigue siendo miembro y no entra. Issue #19.
     *
     * <p>Misma regla de autorizacion que la baja, y por lo mismo: dejar fuera a un administrador es
     * tan grave como quitarle el rol.
     */
    @PreAuthorize("@authorization.canChange(authentication, #condominiumId, #membershipId)")
    @PostMapping("/{membershipId}/suspension")
    MembershipView suspend(
            @PathVariable UUID condominiumId,
            @PathVariable UUID membershipId,
            @Valid @RequestBody Reason reason,
            Principal principal) {
        return memberships.suspend(condominiumId, membershipId, reason.reason(), principal.getName());
    }

    /**
     * Cambia los roles de una membresia. Issue #20.
     *
     * <p><strong>Pide las dos mitades de la regla y no una</strong>: poder quitar los roles que la
     * membresia tiene hoy ({@code puedeCambiar}) <em>y</em> poder conceder los que llegan en el
     * cuerpo ({@code puedeConceder}). Un cambio es las dos cosas a la vez, y con una sola mitad se
     * escapa justo lo que la matriz acota: con solo {@code puedeConceder}, el superadministrador
     * convertiria a un propietario en {@code CONDO_ADMIN} --y la celda "Solo administradores" le
     * prohibe tocar propietarios--; con solo {@code puedeCambiar}, podria quitarle sus roles
     * administrativos a alguien y dejarle {@code OWNER}, que es repartir un rol que no le toca.
     *
     * <p>El orden importa, y no es estetico: {@code puedeCambiar} <strong>lanza</strong> cuando la
     * membresia es de otro condominio, y SpEL corta en el primer {@code and} que falla. Puesto
     * primero, pedir la membresia de otro edificio responde 404 --el segundo criterio del issue-- y
     * no un 403 que confirmaria que existe.
     *
     * <p>Es un {@code PUT} del conjunto completo, no un {@code PATCH} que añada o quite: los roles
     * de una membresia son un solo valor. Asi la peticion es idempotente y la bitacora guarda las
     * dos mitades, que es lo que se pregunta despues. El motivo es obligatorio, por lo mismo que en
     * la baja.
     *
     * <p><strong>Pide segundo factor reciente</strong> desde el issue #22: Seguridad 7.3 nombra
     * "asignar roles administrativos" entre las operaciones sensibles, y esta es la operacion que
     * los mueve. Lo pide <strong>siempre</strong>, no solo cuando hay un rol administrativo en el
     * cuerpo, porque el cuerpo trae los roles que quedan y no los que se van: quitarle
     * {@code CONDO_ADMIN} a alguien se escribe mandando un cuerpo sin el, y es tan sensible como
     * ponerselo.
     */
    @PreAuthorize("@authorization.canChange(authentication, #condominiumId, #membershipId)"
            + " and @authorization.canGrant(authentication, #incoming.roles())"
            + " and @reauthentication.recent(authentication)")
    @PutMapping("/{membershipId}/roles")
    MembershipView changeRoles(
            @PathVariable UUID condominiumId,
            @PathVariable UUID membershipId,
            @Valid @RequestBody NewRoles incoming,
            Principal principal) {
        return memberships.changeRoles(
                condominiumId, membershipId, incoming.roles(), incoming.reason(), principal.getName());
    }

    /** Levanta la suspension. Vuelve a dar acceso en la siguiente peticion. Issue #19. */
    @PreAuthorize("@authorization.canChange(authentication, #condominiumId, #membershipId)")
    @PostMapping("/{membershipId}/reactivacion")
    MembershipView reinstate(
            @PathVariable UUID condominiumId,
            @PathVariable UUID membershipId,
            @Valid @RequestBody Reason reason,
            Principal principal) {
        return memberships.reinstate(condominiumId, membershipId, reason.reason(), principal.getName());
    }

    /**
     * El motivo de una baja, una suspension o una reactivacion.
     *
     * <p>Obligatorio y con un minimo de caracteres a proposito: el alcance del issue #19 pide la baja
     * "con motivo", y un campo que admita un espacio en blanco no es un motivo. Acabara en la
     * bitacora, que es el unico sitio donde se guarda.
     */
    record Reason(@NotBlank @Size(min = 5, max = 500) String reason) {}

    /**
     * Lo que hace falta para conceder una membresia.
     *
     * <p>El condominio no viaja aqui: va en la ruta, por lo mismo que en el alta de unidades. Asi
     * no puede haber discrepancia entre el condominio de la URL y el del cuerpo.
     *
     * @param roles al menos uno; el diagrama de Seguridad 2 marca esa relacion como obligatoria
     * @param invitedBy la cuenta que invito; nulo en la del primer administrador
     */
    record NewMembership(
            @jakarta.validation.constraints.NotNull UUID accountId,
            @NotEmpty Set<MembershipRole> roles,
            @Nullable UUID invitedBy) {}

    /**
     * Los roles que la membresia tendra, y por que. Issue #20.
     *
     * <p>El conjunto llega <strong>completo</strong>: lo que no venga aqui es lo que se quita. Un
     * cuerpo con un rol que se añade dejaria al cliente adivinando el estado anterior, y al servidor
     * sin poder guardar en la bitacora las dos mitades del cambio.
     */
    record NewRoles(@NotEmpty Set<MembershipRole> roles, @NotBlank @Size(min = 5, max = 500) String reason) {}
}
