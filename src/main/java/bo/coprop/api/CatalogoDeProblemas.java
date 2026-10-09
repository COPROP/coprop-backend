package bo.coprop.api;

import bo.coprop.shared.ClavesDeLog;
import bo.coprop.shared.CodigoDeError;
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
final class CatalogoDeProblemas {

    /** Base de los {@code type} del RFC 9457. No se resuelve por red: identifica, no navega. */
    private static final String BASE_DE_TIPOS = "https://coprop.bo/errores/";

    private CatalogoDeProblemas() {}

    /**
     * Estado HTTP de cada codigo.
     *
     * <p>Es un switch y no un mapa a proposito: cuando entre un codigo nuevo en el catalogo, esto
     * deja de compilar en vez de devolver null en produccion. Lo señalo NullAway al introducir el
     * analisis estatico, sobre la version con mapa.
     */
    static HttpStatus estadoDe(CodigoDeError codigo) {
        return switch (codigo) {
            case VALIDACION, REGLA_DE_NEGOCIO -> HttpStatus.UNPROCESSABLE_CONTENT;
            case NO_ENCONTRADO -> HttpStatus.NOT_FOUND;
            case CONFLICTO -> HttpStatus.CONFLICT;
            case NO_AUTENTICADO -> HttpStatus.UNAUTHORIZED;
            case SIN_PERMISO -> HttpStatus.FORBIDDEN;
            case INTERNO -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * Construye el cuerpo problem+json. El {@code traceId} entra siempre que exista: es lo
     * que permite a soporte buscar en los logs el identificador que le reporta un usuario.
     */
    static ProblemDetail problema(CodigoDeError codigo, String detalle, String ruta) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estadoDe(codigo), detalle);
        problema.setType(URI.create(BASE_DE_TIPOS + identificadorDe(codigo)));
        problema.setTitle(tituloDe(codigo));
        problema.setInstance(URI.create(ruta));
        problema.setProperty("codigo", codigo.name());
        String traza = MDC.get(ClavesDeLog.TRAZA);
        if (traza != null) {
            problema.setProperty("traceId", traza);
        }
        return problema;
    }

    private static String tituloDe(CodigoDeError codigo) {
        return switch (codigo) {
            case VALIDACION -> "La solicitud no es valida";
            case NO_ENCONTRADO -> "No se encontro el recurso";
            case CONFLICTO -> "La operacion choca con el estado actual";
            case REGLA_DE_NEGOCIO -> "Una regla del negocio rechaza la operacion";
            case NO_AUTENTICADO -> "Hace falta iniciar sesion";
            case SIN_PERMISO -> "No tienes permiso para esta operacion";
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
    private static String identificadorDe(CodigoDeError codigo) {
        return codigo.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
