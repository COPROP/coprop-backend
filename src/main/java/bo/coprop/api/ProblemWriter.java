package bo.coprop.api;

import bo.coprop.shared.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

/**
 * Escribe un problem+json directamente en la respuesta.
 *
 * <p>Hace falta porque los enganches de Spring Security --el que rechaza por falta de token, el que
 * rechaza por falta de permiso y el filtro de identidad-- actuan <strong>antes de que exista un
 * controlador</strong>, asi que el manejador de excepciones no interviene. Sin esto, la API tendria
 * dos formatos de error segun donde falle: problem+json en el dominio y un cuerpo vacio en la
 * seguridad.
 */
class ProblemWriter {

    private final ObjectMapper mapeador;

    ProblemWriter(ObjectMapper mapeador) {
        this.mapeador = mapeador;
    }

    void escribir(HttpServletRequest peticion, HttpServletResponse response, ErrorCode code, String detalle)
            throws IOException {
        ProblemDetail problema = ProblemCatalog.problema(code, detalle, peticion.getRequestURI());
        response.setStatus(ProblemCatalog.statusOf(code).value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapeador.writeValue(response.getOutputStream(), problema);
    }
}
