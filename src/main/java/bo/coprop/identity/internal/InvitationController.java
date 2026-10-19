package bo.coprop.identity.internal;

import bo.coprop.identity.InvitationView;
import bo.coprop.identity.Invitations;
import bo.coprop.identity.MembershipRole;
import bo.coprop.shared.ApiController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Invitaciones de un condominio, del lado del administrador. Issue #18.
 *
 * <p>Cuelga del condominio, como la membresia: invitar es invitar <em>a un edificio</em>. Lo que no
 * cuelga de ninguno es abrir el enlace y aceptarlo, que es {@link InvitationAccessController}:
 * quien llega con un token todavia no esta en ningun condominio, que es justamente lo que la
 * invitacion va a arreglar.
 *
 * <p><strong>Emitir pide poder conceder esos roles</strong>, la misma regla que el alta directa de
 * membresia, porque es lo mismo diferido: el administrador del condominio puede cualquiera y el
 * superadministrador solo los administrativos, que es como invita al primer administrador de un
 * condominio recien creado. El resto --listar, reenviar, revocar-- pide {@code INVITE_USERS},
 * que en la matriz es del administrador del condominio: son operaciones sobre el padron del
 * edificio y el superadministrador no lo opera (Seguridad 10.1).
 *
 * <p>Ninguna respuesta de aqui lleva el token. Un administrador que pudiera leerlo podria entrar
 * como el invitado; si hace falta un enlace nuevo, se reenvia.
 */
@ApiController
@RestController
@RequestMapping("/condominios/{condominiumId}/invitaciones")
class InvitationController {

    private final Invitations invitations;

    InvitationController(Invitations invitations) {
        this.invitations = invitations;
    }

    // Como el alta de una membresia (#22): una invitacion es una membresia diferida, asi que
    // invitar a alguien como CONDO_ADMIN es asignar un rol administrativo y pide segundo factor.
    // Invitar a un propietario no.
    @PreAuthorize("@authorization.canGrant(authentication, #body.roles())"
            + " and @reauthentication.ifAdminRole(authentication, #body.roles())")
    @PostMapping
    ResponseEntity<InvitationView> issue(
            @PathVariable UUID condominiumId, @Valid @RequestBody NewInvitation body, Principal principal) {
        InvitationView issued = invitations.issue(condominiumId, body.accountId(), body.roles(), principal.getName());
        return ResponseEntity.created(
                        URI.create("/api/v1/condominios/" + condominiumId + "/invitaciones/" + issued.id()))
                .body(issued);
    }

    /** Las vivas y las cerradas: quien administra necesita ver que ya invito a alguien y no entro. */
    @PreAuthorize("@authorization.can(authentication, 'INVITE_USERS')")
    @GetMapping
    List<InvitationView> list(@PathVariable UUID condominiumId) {
        return invitations.ofCondominium(condominiumId);
    }

    @PreAuthorize("@authorization.can(authentication, 'INVITE_USERS')")
    @GetMapping("/{invitationId}")
    InvitationView consultar(@PathVariable UUID condominiumId, @PathVariable UUID invitationId) {
        return invitations.find(condominiumId, invitationId);
    }

    /**
     * Reenvia: revoca esta y emite otra.
     *
     * <p>Responde <strong>201</strong> y la cabecera {@code Location} de la nueva, porque es lo que
     * pasa de verdad: el recurso que se devuelve no es el de la ruta. Un 200 daria a entender que la
     * invitacion de la URL sigue viva, y lo que queda vivo es su sustituta.
     */
    @PreAuthorize("@authorization.can(authentication, 'INVITE_USERS')")
    @PostMapping("/{invitationId}/reenvio")
    ResponseEntity<InvitationView> resend(
            @PathVariable UUID condominiumId, @PathVariable UUID invitationId, Principal principal) {
        InvitationView body = invitations.resend(condominiumId, invitationId, principal.getName());
        return ResponseEntity.created(URI.create("/api/v1/condominios/" + condominiumId + "/invitaciones/" + body.id()))
                .body(body);
    }

    /**
     * Revoca.
     *
     * <p>{@code POST .../revocacion} y no {@code DELETE}: la fila no se borra, se cierra con una
     * fecha. Es la misma convencion que la vigencia de todo lo demas --lo que afecta a algo ya
     * emitido se cierra, no se actualiza en sitio-- y lo que el issue #21 querra en la bitacora es
     * precisamente la fila cerrada, no su ausencia.
     */
    @PreAuthorize("@authorization.can(authentication, 'INVITE_USERS')")
    @PostMapping("/{invitationId}/revocacion")
    InvitationView revoke(@PathVariable UUID condominiumId, @PathVariable UUID invitationId, Principal principal) {
        return invitations.revoke(condominiumId, invitationId, principal.getName());
    }

    /**
     * Lo que hace falta para invitar.
     *
     * <p>Apunta a una cuenta que ya existe, no a un correo suelto: es el orden de Seguridad 6.1 --el
     * administrador registra primero a la persona y su relacion con la unidad-- y asi el correo al
     * que se escribe es el que ya se valido al abrir la cuenta, no uno que viaja en cada invitacion.
     *
     * <p>Las unidades no estan aqui aunque el alcance del issue las nombre: son del modulo
     * {@code condominium} y se registran con sus propios endpoints, antes de invitar.
     *
     * @param roles al menos uno; los que tendra la membresia cuando se acepte
     */
    record NewInvitation(@NotNull UUID accountId, @NotEmpty Set<MembershipRole> roles) {}
}
