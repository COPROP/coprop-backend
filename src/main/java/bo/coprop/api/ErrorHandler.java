package bo.coprop.api;

import static java.util.Objects.requireNonNullElse;

import bo.coprop.shared.DomainError;
import bo.coprop.shared.ErrorCode;
import bo.coprop.shared.ReauthenticationRequired;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Manejo central de errores: todo lo que sale con error sale en problem+json.
 *
 * <p>Extiende {@link ResponseEntityExceptionHandler} para quedarse tambien con los fallos que
 * levanta el propio Spring MVC, no solo con los del dominio.
 */
@RestControllerAdvice
class ErrorHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    /** Un campo rechazado por la validacion. El codigo es el de la anotacion que fallo. */
    record ApiFieldError(String field, String code, String message) {}

    @ExceptionHandler(DomainError.class)
    ResponseEntity<ProblemDetail> manejarDominio(DomainError error, HttpServletRequest request) {
        // getMessage() es @Nullable por contrato de Throwable, aunque nuestros constructores lo
        // exijan: nada impide que una subclase futura pase null.
        ProblemDetail problem = ProblemCatalog.problem(
                error.code(),
                requireNonNullElse(error.getMessage(), "La operacion fue rechazada."),
                request.getRequestURI());
        return ResponseEntity.status(ProblemCatalog.statusOf(error.code())).body(problem);
    }

    /**
     * Operacion sensible sin segundo factor reciente. Issue <strong>#22</strong>, Seguridad 7.3.
     *
     * <p>Tiene manejador propio --y no le basta el de {@link DomainError}, del que hereda-- por
     * la <strong>cabecera</strong>. El RFC 9470 define el desafio {@code insufficient_user_authentication}
     * con su {@code max_age}, y es lo que convierte un 401 en una instruccion: sin ella el cliente
     * solo sabe que algo fallo, y con ella sabe que tiene que mandar al usuario al proveedor con
     * {@code max_age=0} y repetir la misma peticion. El {@code codigo} del cuerpo dice lo mismo
     * para quien ramifique por el, que es como ramifica el resto de la API.
     *
     * <p>Spring ordena los manejadores por cercania en la jerarquia, asi que este gana sobre el de
     * {@link DomainError} sin que haya que declarar nada.
     *
     * <p>El desafio sale con el esquema {@code Bearer} tambien en la cadena del BFF, donde no hay
     * token: es el esquema que el RFC define para esto, y un segundo nombre para el mismo desafio
     * obligaria a la SPA y a la app movil a leer cosas distintas ante la misma negativa.
     */
    @ExceptionHandler(ReauthenticationRequired.class)
    ResponseEntity<ProblemDetail> handleReauthentication(ReauthenticationRequired error, HttpServletRequest request) {
        ProblemDetail problem = ProblemCatalog.problem(
                error.code(),
                requireNonNullElse(error.getMessage(), "Hace falta confirmar tu identidad."),
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer error=\"insufficient_user_authentication\", error_description=\"se requiere un segundo factor recent\", max_age="
                                + error.validity().toSeconds())
                .body(problem);
    }

    /**
     * Denegacion que llega desde la seguridad a nivel de metodo, ya dentro del controlador. La que
     * ocurre antes, en la cadena de filtros, la atiende la configuracion de seguridad.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException error, HttpServletRequest request) {
        ProblemDetail problem = ProblemCatalog.problem(
                ErrorCode.NO_PERMISSION, "Tu rol no alcanza para esta operacion.", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    /**
     * Red de seguridad. El detalle se descarta a proposito: un mensaje de excepcion puede llevar
     * nombres de tabla o fragmentos de consulta. Al log si va entero, con su traceId.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> manejarNoPrevisto(Exception error, HttpServletRequest request) {
        log.error("Error no previsto atendiendo {}", request.getRequestURI(), error);
        ProblemDetail problem = ProblemCatalog.problem(
                ErrorCode.INTERNAL,
                "Ocurrio un error inesperado. Si vuelve a pasar, reporta el traceId.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /**
     * Validacion de entrada. Spring responderia 400; el contrato pide 422 con la lista de campos,
     * para que el cliente pueda marcar cada uno en su formulario sin parsear texto.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException error, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ApiFieldError> fields = error.getBindingResult().getFieldErrors().stream()
                .map(ErrorHandler::aErrorDeCampo)
                .toList();
        ProblemDetail problem =
                ProblemCatalog.problem(ErrorCode.VALIDATION, "Revisa los campos indicados.", rutaDe(request));
        problem.setProperty("errors", fields);
        return ResponseEntity.unprocessableContent().body(problem);
    }

    /**
     * Cuerpo que no se pudo deserializar. Spring responderia 400 con un problem+json sin
     * {@code codigo}, porque este caso no pasa por ninguno de los manejadores de arriba, y el
     * contrato dice que todo error lleva codigo.
     *
     * <p>Se parte en dos, porque son problemas distintos:
     *
     * <ul>
     *   <li>El JSON es correcto pero un valor no se admite --un enum que no existe, un UUID mal
     *       escrito--. Jackson falla antes de que haya objeto que validar, pero el cliente
     *       necesita exactamente lo mismo que en cualquier otra validacion: <strong>422</strong>,
     *       {@code VALIDATION} y la lista de campos para marcar el formulario.
     *   <li>El JSON esta roto y no hay campo que señalar: <strong>400</strong> y
     *       {@code UNREADABLE_BODY}.
     * </ul>
     *
     * <p>El RFC 9110 reserva el 400 para el cuerpo mal formado y el 422 para el bien formado que
     * no se puede procesar, asi que el 400 que daba Spring para el primer caso era ademas el
     * estado equivocado.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException error, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (error.getCause() instanceof MismatchedInputException mismatch) {
            ProblemDetail problem =
                    ProblemCatalog.problem(ErrorCode.VALIDATION, "Revisa los campos indicados.", rutaDe(request));
            problem.setProperty("errors", List.of(aErrorDeCampo(mismatch)));
            return ResponseEntity.unprocessableContent().body(problem);
        }

        // Sin detalle del fallo: el mensaje de Jackson lleva el fragmento de JSON que no pudo
        // leer, y devolverlo seria hacer eco de lo que mando el cliente.
        ProblemDetail problem =
                ProblemCatalog.problem(ErrorCode.UNREADABLE_BODY, "El cuerpo no es JSON valido.", rutaDe(request));
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Traduce un desajuste de Jackson a la misma forma que un fallo de validacion.
     *
     * <p>El mensaje <strong>no</strong> es el de la excepcion: ese lleva el nombre completo de la
     * clase destino y el fragmento del cuerpo. Para un enum si se enumeran los valores admitidos,
     * que son parte del contrato --el cliente los manda-- y es lo que de verdad necesita saber.
     */
    private static ApiFieldError aErrorDeCampo(MismatchedInputException mismatch) {
        String field = rutaDe(mismatch);
        if (mismatch instanceof InvalidFormatException format) {
            Class<?> destino = format.getTargetType();
            if (destino != null && destino.isEnum()) {
                String allowed = Arrays.stream(destino.getEnumConstants())
                        .map(valor -> ((Enum<?>) valor).name())
                        .collect(Collectors.joining(", "));
                return new ApiFieldError(field, "VALUE_NOT_ALLOWED", "Valores admitidos: " + allowed + ".");
            }
        }
        return new ApiFieldError(field, "FORMAT", "El valor no tiene el formato esperado.");
    }

    /**
     * La ruta del campo tal como la da Jackson, en el mismo formato que usa
     * {@link FieldError#getField()}: nombres separados por punto e indices entre corchetes, por
     * ejemplo {@code unidades[0].code}. Vacia si Jackson no supo donde estaba.
     */
    private static String rutaDe(MismatchedInputException mismatch) {
        StringBuilder path = new StringBuilder();
        for (var reference : mismatch.getPath()) {
            String name = reference.getPropertyName();
            if (name != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(name);
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    /**
     * El codigo y el mensaje de un {@link FieldError} son @Nullable: el codigo falta cuando el
     * rechazo no viene de una anotacion, y el mensaje cuando no hay plantilla resuelta. El cliente
     * ramifica por el codigo, asi que es mejor un valor explicito que un hueco en el JSON.
     */
    private static ApiFieldError aErrorDeCampo(FieldError failure) {
        return new ApiFieldError(
                failure.getField(),
                requireNonNullElse(failure.getCode(), "INVALID"),
                requireNonNullElse(failure.getDefaultMessage(), "El valor no es valido."));
    }

    private static String rutaDe(WebRequest request) {
        return request.getDescription(false).replaceFirst("^uri=", "");
    }
}
