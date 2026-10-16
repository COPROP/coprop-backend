package bo.coprop.api;

import bo.coprop.identity.Identities;
import bo.coprop.identity.OrigenDeSesion;
import bo.coprop.identity.SesionActivaView;
import bo.coprop.shared.ControladorDeApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las sesiones activas de quien pregunta, y el boton de cerrarlas todas. Issue <strong>#22</strong>,
 * Seguridad 8 y 5.3.
 *
 * <p><strong>Siempre son las propias.</strong> No recibe ningun identificador y no hay forma de
 * pedir las de otro: lo que la ruta devuelve lo decide la autenticacion, igual que
 * {@code GET /api/v1/me}. Por eso tampoco lleva {@code @PreAuthorize} --no hay rol que alcance o no
 * alcance-- ni cuelga de un condominio: una sesion no es de un edificio.
 *
 * <p><strong>Vive en {@code api} y no en {@code identity}</strong>, al contrario que los demas
 * controladores de ese dominio, por una sola razon: marcar cual de las sesiones es la que esta
 * haciendo esta peticion. Eso exige el identificador de la sesion HTTP, que solo existe aqui. El
 * resto --componer las dos mitades, cerrar, auditar-- esta en {@code identity}, que es donde
 * tiene que estar.
 *
 * <p>Comparte el prefijo {@code /me} con {@link MeController} y son dos controladores distintos a
 * proposito, por lo mismo que {@code /condominios/{id}} lo comparten {@code condominium} e
 * {@code identity}: cada uno sirve la parte que le toca y la ruta es del cliente, no de un modulo.
 */
@ControladorDeApi
@RestController
@RequestMapping("/me/sesiones")
class SesionesController {

    private final Identities identidades;

    SesionesController(Identities identidades) {
        this.identidades = identidades;
    }

    /**
     * Donde esta abierta mi cuenta ahora mismo.
     *
     * <p>Salen las dos mitades: la del navegador, que gobierna esta aplicacion, y las del proveedor
     * de identidad, que sostienen el refresh token de la app movil. Una lista que mirara solo una
     * de las dos diria que no hay nadie dentro cuando si lo hay.
     *
     * <p>Devuelve <strong>200 con lista vacia</strong> a quien no tiene ninguna, que es lo que le
     * pasa a quien entra por primera vez con la app movil y nunca por la web.
     */
    @GetMapping
    List<MiSesion> listar(Principal quien, HttpServletRequest peticion) {
        String sesionActual = idDeLaSesion(peticion);
        return identidades
                .sessionsOf(IdentidadAutenticada.actorDe(autenticacionDe(quien)).accountId())
                .stream()
                .map(sesion -> MiSesion.de(sesion, sesionActual))
                .toList();
    }

    /**
     * Cerrar sesion en todos los dispositivos.
     *
     * <p><strong>Cierra tambien esta</strong>, asi que la respuesta es lo ultimo que esta sesion
     * recibe: la siguiente peticion del navegador ya no tiene sesion y cae en el 401 de siempre.
     * Es deliberado y esta explicado en {@link Identities#signOutEverywhere}.
     *
     * <p>Responde <strong>204</strong> y no el numero de sesiones cerradas: ese numero es de la
     * bitacora, no del cliente, y enseñarselo a quien acaba de pulsar el boton solo abre la
     * pregunta de por que no coincide con lo que la lista decia hace un segundo.
     *
     * <p>Es un {@code POST} a un sub-recurso y no un {@code DELETE} sobre la coleccion, por lo
     * mismo que la baja de una membresia: lo que pasa aqui no es borrar filas, es una operacion con
     * nombre que deja rastro en la bitacora.
     */
    @PostMapping("/cierre")
    ResponseEntity<Void> cerrarTodas(Principal quien) {
        var actor = IdentidadAutenticada.actorDe(autenticacionDe(quien));
        identidades.signOutEverywhere(actor.accountId(), quien.getName());
        return ResponseEntity.noContent().build();
    }

    /**
     * El {@link Principal} que recibe un controlador es la autenticacion, y aqui hace falta como
     * tal para sacarle el actor.
     *
     * <p>El casteo no puede fallar: las tres cadenas exigen autenticacion y lo que Spring inyecta
     * como {@code Principal} es la propia {@code Authentication}. Si fallara seria un cambio en la
     * cadena de seguridad, y {@link IdentidadAutenticada#actorDe} ya explica por que eso revienta
     * en vez de responder un 403.
     */
    private static org.springframework.security.core.Authentication autenticacionDe(Principal quien) {
        if (quien instanceof org.springframework.security.core.Authentication autenticacion) {
            return autenticacion;
        }
        throw new IllegalStateException(
                "La peticion llego a /me/sesiones con un Principal que no es una Authentication: %s."
                        .formatted(quien.getClass().getName()));
    }

    /** El identificador de la sesion de esta peticion, o nada si viene por la app movil. */
    private static @Nullable String idDeLaSesion(HttpServletRequest peticion) {
        HttpSession sesion = peticion.getSession(false);
        return sesion == null ? null : sesion.getId();
    }

    /**
     * Una sesion como la ve su dueño.
     *
     * <p>Es la vista del dominio mas una cosa que el dominio no puede saber: si es
     * <strong>esta</strong>. Sin esa marca, una lista de tres sesiones no le dice a nadie cual
     * puede cerrar sin quedarse fuera, que es justo la pregunta que lleva a esta pantalla.
     *
     * @param actual si es la sesion desde la que se esta mirando. Siempre falso en las del
     *     proveedor: sus identificadores son de otro espacio y no se pueden emparejar con el de la
     *     sesion del navegador
     */
    record MiSesion(
            String id,
            OrigenDeSesion origen,
            @Nullable String ip,
            Instant creada,
            Instant ultimoAcceso,
            @Nullable Instant expira,
            boolean actual) {

        static MiSesion de(SesionActivaView sesion, @Nullable String sesionActual) {
            return new MiSesion(
                    sesion.id(),
                    sesion.origen(),
                    sesion.ip(),
                    sesion.creada(),
                    sesion.ultimoAcceso(),
                    sesion.expira(),
                    sesion.origen() == OrigenDeSesion.NAVEGADOR && sesion.id().equals(sesionActual));
        }
    }
}
