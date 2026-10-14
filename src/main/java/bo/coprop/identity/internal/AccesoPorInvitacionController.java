package bo.coprop.identity.internal;

import bo.coprop.identity.InvitationView;
import bo.coprop.identity.Invitations;
import bo.coprop.shared.ControladorDeApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las dos llamadas que hace quien recibio una invitacion. Issue #18.
 *
 * <p>No cuelgan de un condominio, y no es un descuido: quien llega con un token todavia no esta en
 * ninguno, que es justamente lo que la invitacion va a arreglar. Si colgaran de
 * {@code /condominios/{id}/...}, el filtro del #17 pediria una membresia que aun no existe y
 * responderia 404 a todo el mundo.
 *
 * <p><strong>El token viaja en el cuerpo, no en la ruta.</strong> Es una credencial: en la ruta
 * acabaria en el log de accesos, en el historial del navegador y en cualquier cabecera
 * {@code Referer}, y Seguridad 6.1 se toma el trabajo de guardar solo su hash justamente para que
 * no quede en ningun sitio. En el cuerpo, ademas, {@code Enmascarador} ya lo tapa: {@code token} es
 * una de sus claves. En el enlace del correo si va en la URL, pero esa URL es de la web y no llega
 * nunca al servidor.
 *
 * <p>Las dos son {@code POST} aunque {@link #abrir} parezca una consulta: crea el usuario en el
 * proveedor de identidad y le manda un correo, asi que no es idempotente en el sentido de no tener
 * efectos. Sí lo es en el que importa --abrir el enlace dos veces vuelve a mandar el correo y no
 * rompe nada-- y por eso no consume el token.
 */
@ControladorDeApi
@RestController
@RequestMapping("/invitaciones")
class AccesoPorInvitacionController {

    private final Invitations invitaciones;

    AccesoPorInvitacionController(Invitations invitaciones) {
        this.invitaciones = invitaciones;
    }

    /**
     * Abre el enlace: comprueba que el token sirve y deja a la persona en condiciones de entrar.
     *
     * <p><strong>Es publica</strong>, y lo tiene que ser: quien acaba de recibir la invitacion no
     * tiene con que autenticarse. Lo que la autoriza es el token, que son 256 bits aleatorios de un
     * solo uso con siete dias de vigencia. La ruta esta declarada en {@code ConfiguracionDeSeguridad}
     * junto a las demas que se sirven sin identidad.
     *
     * <p>Devuelve si hubo que mandarle el correo para poner su contraseña. Son los dos casos de
     * Seguridad 6.1 y la pagina necesita distinguirlos para decir "revisa tu correo" o "inicia
     * sesion".
     */
    @PostMapping("/apertura")
    Apertura abrir(@Valid @RequestBody ConToken cuerpo) {
        Invitations.Apertura apertura = invitaciones.open(cuerpo.token());
        return new Apertura(apertura.condominiumId(), apertura.credencialEnviada());
    }

    /**
     * Acepta: sella la invitacion y concede la membresia.
     *
     * <p>Esta <strong>autenticada</strong>. Para llegar aqui la persona ya inicio sesion, y con eso
     * el filtro de identidad del #13 vinculo el sujeto del proveedor con su cuenta de COPROP por el
     * correo verificado. Es lo que hace que entrar con contraseña y entrar con Google caigan en la
     * misma cuenta, que es el segundo criterio del issue, y por eso aceptar no necesita saber como
     * entro.
     */
    @PostMapping("/aceptacion")
    InvitationView aceptar(@Valid @RequestBody ConToken cuerpo, Principal quien) {
        return invitaciones.accept(cuerpo.token(), UUID.fromString(quien.getName()));
    }

    /**
     * El token, y nada mas.
     *
     * <p>Envuelto en un objeto y no como una cadena suelta: deja sitio para lo que la aceptacion
     * pueda necesitar despues --aceptar unos roles y no otros, por ejemplo-- sin cambiar la forma
     * del cuerpo.
     *
     * <p>El tamaño maximo es generoso pero existe: sin el, un cuerpo de un megabyte llegaria a
     * calcular un SHA-256 antes de que nadie lo rechace.
     */
    record ConToken(@NotBlank @Size(max = 200) String token) {}

    /**
     * @param credencialEnviada en cierto, le mandamos un correo para que ponga su contraseña y
     *     todavia no puede iniciar sesion. En falso, ya tiene cuenta en el proveedor --participa en
     *     otro condominio-- y solo le toca entrar.
     */
    record Apertura(UUID condominio, boolean credencialEnviada) {}
}
