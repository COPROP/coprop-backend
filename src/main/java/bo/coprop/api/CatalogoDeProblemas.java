package bo.coprop.api;

import bo.coprop.shared.CodigoDeError;
import java.net.URI;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Unico sitio donde un codigo de dominio se convierte en respuesta HTTP.
 *
 * <p>Lo usan tanto el manejador de excepciones como los enganches de Spring Security, que actuan
 * antes de que exista un controlador. Si viviera solo en el manejador, un 401 saldria con un
 * formato distinto al de los demas errores.
 */
final class CatalogoDeProblemas {

    /** Base de los {@code type} del RFC 9457. No se resuelve por red: identifica, no navega. */
    private static final String BASE_DE_TIPOS = "https://coprop.bo/errores/";

    private static final Map<CodigoDeError, HttpStatus> ESTADOS = Map.of(
            CodigoDeError.VALIDACION, HttpStatus.UNPROCESSABLE_CONTENT,
            CodigoDeError.NO_ENCONTRADO, HttpStatus.NOT_FOUND,
            CodigoDeError.CONFLICTO, HttpStatus.CONFLICT,
            CodigoDeError.REGLA_DE_NEGOCIO, HttpStatus.UNPROCESSABLE_CONTENT,
            CodigoDeError.NO_AUTENTICADO, HttpStatus.UNAUTHORIZED,
            CodigoDeError.SIN_PERMISO, HttpStatus.FORBIDDEN,
            CodigoDeError.INTERNO, HttpStatus.INTERNAL_SERVER_ERROR);

    private static final Map<CodigoDeError, String> TITULOS = Map.of(
            CodigoDeError.VALIDACION, "La solicitud no es valida",
            CodigoDeError.NO_ENCONTRADO, "No se encontro el recurso",
            CodigoDeError.CONFLICTO, "La operacion choca con el estado actual",
            CodigoDeError.REGLA_DE_NEGOCIO, "Una regla del negocio rechaza la operacion",
            CodigoDeError.NO_AUTENTICADO, "Hace falta iniciar sesion",
            CodigoDeError.SIN_PERMISO, "No tienes permiso para esta operacion",
            CodigoDeError.INTERNO, "Error interno");

    private CatalogoDeProblemas() {}

    static HttpStatus estadoDe(CodigoDeError codigo) {
        return ESTADOS.get(codigo);
    }

    /**
     * Construye el cuerpo problem+json. El {@code correlationId} entra siempre que exista: es lo
     * que permite a soporte unir un error que le reportan con su traza en los logs.
     */
    static ProblemDetail problema(CodigoDeError codigo, String detalle, String ruta) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estadoDe(codigo), detalle);
        problema.setType(URI.create(BASE_DE_TIPOS + codigo.name().toLowerCase().replace('_', '-')));
        problema.setTitle(TITULOS.get(codigo));
        problema.setInstance(URI.create(ruta));
        problema.setProperty("codigo", codigo.name());
        String correlacion = MDC.get(FiltroDeCorrelacion.CLAVE);
        if (correlacion != null) {
            problema.setProperty("correlationId", correlacion);
        }
        return problema;
    }
}
