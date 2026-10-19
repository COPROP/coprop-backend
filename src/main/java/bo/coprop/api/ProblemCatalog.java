package bo.coprop.api;

import bo.coprop.shared.ErrorCode;
import bo.coprop.shared.LogKeys;
import java.net.URI;
import java.util.Locale;
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
final class ProblemCatalog {

    /** Base de los {@code type} del RFC 9457. No se resuelve por red: identifica, no navega. */
    private static final String TYPE_BASE = "https://coprop.bo/errors/";

    private ProblemCatalog() {}

    /**
     * Estado HTTP de cada codigo.
     *
     * <p>Es un switch y no un mapa a proposito: cuando entre un codigo nuevo en el catalogo, esto
     * deja de compilar en vez de devolver null en produccion. Lo señalo NullAway al introducir el
     * analisis estatico, sobre la version con mapa.
     */
    static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case VALIDATION, BUSINESS_RULE -> HttpStatus.UNPROCESSABLE_CONTENT;
            case UNREADABLE_BODY -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            // El 401 de una operacion sensible lleva sesion valida, al contrario que el otro.
            // Es el estado que pide el RFC 9470 y el que lleva el desafio: lo que se le dice al
            // cliente es "autenticate mas fuerte y vuelve", no "no puedes".
            case NOT_AUTHENTICATED, REAUTHENTICATION_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case NO_PERMISSION, NO_ACCOUNT -> HttpStatus.FORBIDDEN;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * Construye el cuerpo problem+json. El {@code traceId} entra siempre que exista: es lo
     * que permite a soporte buscar en los logs el identificador que le reporta un usuario.
     */
    static ProblemDetail problem(ErrorCode code, String detalle, String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(statusOf(code), detalle);
        problem.setType(URI.create(TYPE_BASE + identifierOf(code)));
        problem.setTitle(titleOf(code));
        problem.setInstance(URI.create(path));
        problem.setProperty("code", code.name());
        String trace = MDC.get(LogKeys.TRAZA);
        if (trace != null) {
            problem.setProperty("traceId", trace);
        }
        return problem;
    }

    private static String titleOf(ErrorCode code) {
        return switch (code) {
            case VALIDATION -> "La solicitud no es valida";
            case UNREADABLE_BODY -> "No se pudo leer el cuerpo de la solicitud";
            case NOT_FOUND -> "No se encontro el recurso";
            case CONFLICT -> "La operacion choca con el estado actual";
            case BUSINESS_RULE -> "Una regla del negocio rechaza la operacion";
            case NOT_AUTHENTICATED -> "Hace falta iniciar sesion";
            case REAUTHENTICATION_REQUIRED -> "Hace falta confirmar tu identidad";
            case NO_PERMISSION -> "No tienes permiso para esta operacion";
            case NO_ACCOUNT -> "Tu cuenta todavia no tiene acceso";
            case INTERNAL -> "Error interno";
        };
    }

    /**
     * {@code NOT_FOUND} queda en {@code no-encontrado}.
     *
     * <p>Con {@code Locale.ROOT} explicito: con la configuracion regional turca, {@code "I"} no
     * baja a {@code "i"} sino a un caracter distinto, y el {@code type} del error cambiaria segun
     * la maquina donde corra el servidor.
     */
    private static String identifierOf(ErrorCode code) {
        return code.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
