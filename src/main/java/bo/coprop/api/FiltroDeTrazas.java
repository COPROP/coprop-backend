package bo.coprop.api;

import bo.coprop.shared.ClavesDeLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Da a cada request un identificador de traza y lo deja en el MDC y en la respuesta.
 *
 * <p>Ese identificador es lo que cumple el criterio del issue #6: todas las lineas de log de una
 * misma peticion lo llevan igual, asi que buscar por el devuelve la peticion entera. El mismo
 * valor viaja en el cuerpo de un error (problem+json) y en la cabecera, de modo que lo que reporta
 * un usuario se puede buscar tal cual en los logs.
 *
 * <p>Si el cliente manda el suyo se respeta, para poder seguir una operacion que empieza en la app
 * movil. El analisis 12.2 lo pide de punta a punta, desde la creacion del QR hasta el comprobante.
 *
 * <p><strong>Por que no hay libreria de trazas.</strong> Esto es un monolito y no hay colector al
 * que exportar nada, asi que Micrometer Tracing solo aportaria el identificador que este filtro ya
 * genera, a cambio de una dependencia y de cuidar el orden de los filtros. El dia que haya un
 * segundo servicio o un colector OTel, el cambio es sustituir este filtro por el puente de
 * tracing: la clave del MDC ({@link ClavesDeLog#TRAZA}) y el nombre del campo no cambian.
 *
 * <p>Va de los primeros de la cadena a proposito: un error de autenticacion ocurre antes que
 * cualquier controlador y tambien tiene que salir con su traza.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class FiltroDeTrazas extends OncePerRequestFilter {

    static final String CABECERA = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        String recibido = peticion.getHeader(CABECERA);
        String traza =
                StringUtils.hasText(recibido) ? recibido : UUID.randomUUID().toString();
        MDC.put(ClavesDeLog.TRAZA, traza);
        respuesta.setHeader(CABECERA, traza);
        try {
            cadena.doFilter(peticion, respuesta);
        } finally {
            MDC.remove(ClavesDeLog.TRAZA);
        }
    }
}
