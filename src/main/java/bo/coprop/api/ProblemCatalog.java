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
    private static final String BASE_DE_TIPOS = "https://coprop.bo/errores/";

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
            case VALIDACION, REGLA_DE_NEGOCIO -> HttpStatus.UNPROCESSABLE_CONTENT;
            case CUERPO_ILEGIBLE -> HttpStatus.BAD_REQUEST;
            case NO_ENCONTRADO -> HttpStatus.NOT_FOUND;
            case CONFLICTO -> HttpStatus.CONFLICT;
            // El 401 de una operacion sensible lleva sesion valida, al contrario que el otro.
            // Es el estado que pide el RFC 9470 y el que lleva el desafio: lo que se le dice al
            // cliente es "autenticate mas fuerte y vuelve", no "no puedes".
            case NO_AUTENTICADO, REAUTENTICACION_REQUERIDA -> HttpStatus.UNAUTHORIZED;
            case SIN_PERMISO, SIN_CUENTA -> HttpStatus.FORBIDDEN;
            case INTERNO -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * Construye el cuerpo problem+json. El {@code traceId} entra siempre que exista: es lo
     * que permite a soporte buscar en los logs el identificador que le reporta un usuario.
     */
    static ProblemDetail problema(ErrorCode code, String detalle, String ruta) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(statusOf(code), detalle);
        problema.setType(URI.create(BASE_DE_TIPOS + identificadorDe(code)));
        problema.setTitle(tituloDe(code));
        problema.setInstance(URI.create(ruta));
        problema.setProperty("codigo", code.name());
        String trace = MDC.get(LogKeys.TRAZA);
        if (trace != null) {
            problema.setProperty("traceId", trace);
        }
        return problema;
    }

    private static String tituloDe(ErrorCode code) {
        return switch (code) {
            case VALIDACION -> "La solicitud no es valida";
            case CUERPO_ILEGIBLE -> "No se pudo leer el cuerpo de la solicitud";
            case NO_ENCONTRADO -> "No se encontro el recurso";
            case CONFLICTO -> "La operacion choca con el estado actual";
            case REGLA_DE_NEGOCIO -> "Una regla del negocio rechaza la operacion";
            case NO_AUTENTICADO -> "Hace falta iniciar sesion";
            case REAUTENTICACION_REQUERIDA -> "Hace falta confirmar tu identidad";
            case SIN_PERMISO -> "No tienes permiso para esta operacion";
            case SIN_CUENTA -> "Tu cuenta todavia no tiene acceso";
            case INTERNO -> "Error interno";
        };
    }

    /**
     * {@code NO_ENCONTRADO} queda en {@code no-encontrado}.
     *
     * <p>Con {@code Locale.ROOT} explicito: con la configuracion regional turca, {@code "I"} no
     * baja a {@code "i"} sino a un caracter distinto, y el {@code type} del error cambiaria segun
     * la maquina donde corra el servidor.
     */
    private static String identificadorDe(ErrorCode code) {
        return code.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
