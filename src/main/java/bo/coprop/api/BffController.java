package bo.coprop.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El arranque del login de la web, tal como lo dibuja Seguridad 5.1.
 *
 * <p>La SPA manda al navegador a {@code GET /bff/login} y a partir de ahi no vuelve a ver nada del
 * protocolo: el backend corre el intercambio con Keycloak, guarda los tokens en la sesion del
 * servidor y le devuelve una cookie. Esta ruta existe para que la SPA tenga una direccion estable
 * que no dependa de como Spring Security nombre hoy su endpoint de autorizacion.
 *
 * <p>No lleva {@code ApiController}: no es parte del contrato de la API, no responde JSON y no
 * va bajo {@code /api/v1}. Es la puerta del navegador.
 *
 * <p>No hay un {@code /bff/logout} aqui porque el cierre de sesion lo sirve Spring Security en
 * {@code POST /logout}, que ademas cierra la sesion en Keycloak. Montar otra ruta encima solo
 * añadiria un sitio mas donde equivocarse.
 */
@RestController
class BffController {

    static final String LOGIN = "/bff/login";

    @GetMapping(LOGIN)
    void iniciarSesion(HttpServletResponse respuesta) throws IOException {
        respuesta.sendRedirect("/oauth2/authorization/" + SecurityConfiguration.REGISTRO_WEB);
    }
}
