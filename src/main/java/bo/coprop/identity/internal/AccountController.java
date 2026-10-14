package bo.coprop.identity.internal;

import bo.coprop.identity.AccountView;
import bo.coprop.identity.People;
import bo.coprop.shared.ControladorDeApi;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consulta de una cuenta de acceso.
 *
 * <p>Existe para que la cabecera {@code Location} del alta de cuenta apunte a una ruta que de
 * verdad responde: el PR #72 tuvo que corregir precisamente ese defecto en el alta de unidades.
 *
 * <p>Tiene su propia ruta, y no cuelga de la persona, porque la cuenta es un recurso global
 * (Seguridad 2). La pantalla de "mi perfil" --{@code GET /api/v1/me}-- es el issue
 * <strong>#14</strong> y no es esto: esa resuelve la cuenta a partir del token, no de un
 * identificador en la ruta.
 */
@ControladorDeApi
@RestController
@RequestMapping("/cuentas")
class AccountController {

    private final People personas;

    AccountController(People personas) {
        this.personas = personas;
    }

    /**
     * La cuenta propia, o cualquiera si quien pregunta administra algun condominio.
     *
     * <p>Lleva el correo, el telefono y si tiene segundo factor, asi que no puede quedar abierta a
     * cualquiera que este autenticado: con el identificador en la mano, eso seria el directorio de
     * la plataforma. Quien la abre --el administrador que invita-- la necesita; el resto, solo la
     * suya, y el perfil propio completo ya lo da {@code GET /api/v1/me} sin pedir identificador.
     *
     * <p>Niega con <strong>403</strong> y no con 404: la ruta es global, no cuelga de ningun
     * condominio, y la respuesta depende solo de los roles de quien pregunta --para cualquier
     * cuenta que no sea la suya recibe lo mismo--, asi que no hay existencia que filtrar.
     */
    @PreAuthorize("@acceso.puedeVerLaCuenta(authentication, #accountId)")
    @GetMapping("/{accountId}")
    AccountView consultar(@PathVariable UUID accountId) {
        return personas.findAccount(accountId);
    }
}
