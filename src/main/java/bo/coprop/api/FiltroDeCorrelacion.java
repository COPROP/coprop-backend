package bo.coprop.api;

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
 * Da a cada request un identificador de correlacion y lo deja en el MDC y en la respuesta.
 *
 * <p>Si el cliente manda el suyo se respeta, para poder seguir una operacion que empieza en la app
 * movil. El analisis 12.2 lo pide de punta a punta, desde la creacion del QR hasta el comprobante.
 *
 * <p>Va de los primeros de la cadena a proposito: un error de autenticacion ocurre antes que
 * cualquier controlador y tambien tiene que salir con su correlationId.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class FiltroDeCorrelacion extends OncePerRequestFilter {

    static final String CLAVE = "correlationId";
    static final String CABECERA = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        String recibido = peticion.getHeader(CABECERA);
        String correlacion =
                StringUtils.hasText(recibido) ? recibido : UUID.randomUUID().toString();
        MDC.put(CLAVE, correlacion);
        respuesta.setHeader(CABECERA, correlacion);
        try {
            cadena.doFilter(peticion, respuesta);
        } finally {
            MDC.remove(CLAVE);
        }
    }
}
