package bo.coprop.api;

import bo.coprop.shared.CodigoDeError;
import bo.coprop.shared.ErrorDeDominio;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Manejo central de errores: todo lo que sale con error sale en problem+json.
 *
 * <p>Extiende {@link ResponseEntityExceptionHandler} para quedarse tambien con los fallos que
 * levanta el propio Spring MVC, no solo con los del dominio.
 */
@RestControllerAdvice
class ManejadorDeErrores extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ManejadorDeErrores.class);

    /** Un campo rechazado por la validacion. El codigo es el de la anotacion que fallo. */
    record ErrorDeCampo(String campo, String codigo, String mensaje) {}

    @ExceptionHandler(ErrorDeDominio.class)
    ResponseEntity<ProblemDetail> manejarDominio(ErrorDeDominio error, HttpServletRequest peticion) {
        ProblemDetail problema =
                CatalogoDeProblemas.problema(error.codigo(), error.getMessage(), peticion.getRequestURI());
        return ResponseEntity.status(CatalogoDeProblemas.estadoDe(error.codigo()))
                .body(problema);
    }

    /**
     * Denegacion que llega desde la seguridad a nivel de metodo, ya dentro del controlador. La que
     * ocurre antes, en la cadena de filtros, la atiende la configuracion de seguridad.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> manejarAccesoDenegado(AccessDeniedException error, HttpServletRequest peticion) {
        ProblemDetail problema = CatalogoDeProblemas.problema(
                CodigoDeError.SIN_PERMISO, "Tu rol no alcanza para esta operacion.", peticion.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problema);
    }

    /**
     * Red de seguridad. El detalle se descarta a proposito: un mensaje de excepcion puede llevar
     * nombres de tabla o fragmentos de consulta. Al log si va entero, con su correlationId.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> manejarNoPrevisto(Exception error, HttpServletRequest peticion) {
        log.error("Error no previsto atendiendo {}", peticion.getRequestURI(), error);
        ProblemDetail problema = CatalogoDeProblemas.problema(
                CodigoDeError.INTERNO,
                "Ocurrio un error inesperado. Si vuelve a pasar, reporta el correlationId.",
                peticion.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problema);
    }

    /**
     * Validacion de entrada. Spring responderia 400; el contrato pide 422 con la lista de campos,
     * para que el cliente pueda marcar cada uno en su formulario sin parsear texto.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException error, HttpHeaders cabeceras, HttpStatusCode estado, WebRequest peticion) {
        List<ErrorDeCampo> campos = error.getBindingResult().getFieldErrors().stream()
                .map(ManejadorDeErrores::aErrorDeCampo)
                .toList();
        ProblemDetail problema = CatalogoDeProblemas.problema(
                CodigoDeError.VALIDACION, "Revisa los campos indicados.", rutaDe(peticion));
        problema.setProperty("errores", campos);
        return ResponseEntity.unprocessableContent().body(problema);
    }

    private static ErrorDeCampo aErrorDeCampo(FieldError fallo) {
        return new ErrorDeCampo(fallo.getField(), fallo.getCode(), fallo.getDefaultMessage());
    }

    private static String rutaDe(WebRequest peticion) {
        return peticion.getDescription(false).replaceFirst("^uri=", "");
    }
}
