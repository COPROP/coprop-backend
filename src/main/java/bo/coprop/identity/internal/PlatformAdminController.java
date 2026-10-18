package bo.coprop.identity.internal;

import bo.coprop.identity.Identities;
import bo.coprop.identity.PlatformAdminView;
import bo.coprop.shared.ControladorDeApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.security.Principal;
import java.util.List;
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
 * Quien administra la plataforma. Issue <strong>#20</strong>.
 *
 * <p>Hasta este issue el rol de plataforma solo lo abria el arranque, y solo cuando no habia
 * ninguno: la plataforma podia empezar pero no crecer, y el segundo superadministrador salia de un
 * {@code INSERT} a mano. Esto lo convierte en una operacion con actor, motivo y bitacora.
 *
 * <p><strong>La ruta es global</strong>, sin {@code /condominios/{id}} delante, y eso es lo que
 * decide quien pasa. En una ruta global el filtro de condominio no corre, asi que las unicas
 * autoridades de la peticion son las que puso el filtro de identidad: la de plataforma, si la tiene.
 * Un {@code CONDO_ADMIN} llega aqui sin ninguna y recibe <strong>403</strong>, que es el primer
 * criterio de aceptacion del issue --"un CONDO_ADMIN no puede asignar SUPER_ADMIN"-- y sale solo de
 * la forma de la ruta, sin una regla escrita aparte.
 *
 * <p>El permiso es {@code INVITE_ADMINS_ONLY}, que es la celda de {@code PLATFORM_ADMIN}
 * en la fila "Invitar usuarios y asignar roles" de Seguridad 3: "Solo administradores". Conceder el
 * rol de plataforma es asignar un rol administrativo, el mayor de ellos, asi que la celda ya lo
 * cubre y no hace falta añadir una fila a la matriz --que seria cambiar el analisis--.
 */
@ControladorDeApi
@RestController
@RequestMapping("/superadministradores")
class PlatformAdminController {

    private final Identities identidades;

    PlatformAdminController(Identities identidades) {
        this.identidades = identidades;
    }

    /**
     * Quien administra la plataforma hoy.
     *
     * <p>Lo necesita quien va a revocar: sin listado habria que conocer de antemano el identificador
     * de la cuenta. Y no sale de aqui quien lo <em>tuvo</em>: la fila se borra al revocar, y esa
     * historia la cuenta la bitacora de plataforma.
     */
    @PreAuthorize("@autorizacion.puede(authentication, 'INVITE_ADMINS_ONLY')")
    @GetMapping
    List<PlatformAdminView> listar() {
        return identidades.platformAdmins();
    }

    /**
     * Concede el rol a otra cuenta. Nadie se lo concede a si mismo.
     *
     * <p>Pide segundo factor reciente (#22): es el mayor de los roles administrativos y Seguridad
     * 7.3 nombra asignarlos entre las operaciones sensibles. Aqui no hay condicion que poner, al
     * contrario que en el alta de una membresia: el unico rol que esta ruta reparte es este.
     */
    @PreAuthorize("@autorizacion.puede(authentication, 'INVITE_ADMINS_ONLY')"
            + " and @reautenticacion.reciente(authentication)")
    @PostMapping
    ResponseEntity<PlatformAdminView> conceder(@Valid @RequestBody NuevoSuperadministrador nuevo, Principal quien) {
        PlatformAdminView concedido = identidades.grantPlatformAdmin(nuevo.accountId(), quien.getName());
        return ResponseEntity.created(URI.create("/api/v1/superadministradores"))
                .body(concedido);
    }

    /**
     * Le quita el rol a una cuenta, y le cierra las sesiones.
     *
     * <p>Es un {@code POST} a un sub-recurso y no un {@code DELETE} sobre la cuenta, por lo mismo
     * que la baja de una membresia: <strong>lleva motivo</strong>, y la fila se borra, asi que el
     * motivo es lo unico que queda explicando el cambio. Un {@code DELETE} con cuerpo es algo que
     * los intermediarios tratan como quieren.
     *
     * <p>Responde <strong>204</strong>: no hay recurso que devolver, porque el rol ya no esta.
     */
    @PreAuthorize("@autorizacion.puede(authentication, 'INVITE_ADMINS_ONLY')"
            + " and @reautenticacion.reciente(authentication)")
    @PostMapping("/{accountId}/revocacion")
    ResponseEntity<Void> revocar(@PathVariable UUID accountId, @Valid @RequestBody Motivo motivo, Principal quien) {
        identidades.revokePlatformAdmin(accountId, motivo.motivo(), quien.getName());
        return ResponseEntity.noContent().build();
    }

    /** A quien se le concede. Sin motivo: lo que hay que explicar es por que se quita. */
    record NuevoSuperadministrador(@NotNull UUID accountId) {}

    /** El motivo de la revocacion. Mismo minimo que el de una baja, y por lo mismo. */
    record Motivo(@NotBlank @Size(min = 5, max = 500) String motivo) {}
}
