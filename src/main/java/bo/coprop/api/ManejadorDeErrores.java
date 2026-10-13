package bo.coprop.api;

import static java.util.Objects.requireNonNullElse;

import bo.coprop.shared.CodigoDeError;
import bo.coprop.shared.ErrorDeDominio;
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
class ManejadorDeErrores extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ManejadorDeErrores.class);

    /** Un campo rechazado por la validacion. El codigo es el de la anotacion que fallo. */
    record ErrorDeCampo(String campo, String codigo, String mensaje) {}

    @ExceptionHandler(ErrorDeDominio.class)
    ResponseEntity<ProblemDetail> manejarDominio(ErrorDeDominio error, HttpServletRequest peticion) {
        // getMessage() es @Nullable por contrato de Throwable, aunque nuestros constructores lo
        // exijan: nada impide que una subclase futura pase null.
        ProblemDetail problema = CatalogoDeProblemas.problema(
                error.codigo(),
                requireNonNullElse(error.getMessage(), "La operacion fue rechazada."),
                peticion.getRequestURI());
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
     * nombres de tabla o fragmentos de consulta. Al log si va entero, con su traceId.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> manejarNoPrevisto(Exception error, HttpServletRequest peticion) {
        log.error("Error no previsto atendiendo {}", peticion.getRequestURI(), error);
        ProblemDetail problema = CatalogoDeProblemas.problema(
                CodigoDeError.INTERNO,
                "Ocurrio un error inesperado. Si vuelve a pasar, reporta el traceId.",
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
     *       {@code VALIDACION} y la lista de campos para marcar el formulario.
     *   <li>El JSON esta roto y no hay campo que señalar: <strong>400</strong> y
     *       {@code CUERPO_ILEGIBLE}.
     * </ul>
     *
     * <p>El RFC 9110 reserva el 400 para el cuerpo mal formado y el 422 para el bien formado que
     * no se puede procesar, asi que el 400 que daba Spring para el primer caso era ademas el
     * estado equivocado.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException error, HttpHeaders cabeceras, HttpStatusCode estado, WebRequest peticion) {
        if (error.getCause() instanceof MismatchedInputException desajuste) {
            ProblemDetail problema = CatalogoDeProblemas.problema(
                    CodigoDeError.VALIDACION, "Revisa los campos indicados.", rutaDe(peticion));
            problema.setProperty("errores", List.of(aErrorDeCampo(desajuste)));
            return ResponseEntity.unprocessableContent().body(problema);
        }

        // Sin detalle del fallo: el mensaje de Jackson lleva el fragmento de JSON que no pudo
        // leer, y devolverlo seria hacer eco de lo que mando el cliente.
        ProblemDetail problema = CatalogoDeProblemas.problema(
                CodigoDeError.CUERPO_ILEGIBLE, "El cuerpo no es JSON valido.", rutaDe(peticion));
        return ResponseEntity.badRequest().body(problema);
    }

    /**
     * Traduce un desajuste de Jackson a la misma forma que un fallo de validacion.
     *
     * <p>El mensaje <strong>no</strong> es el de la excepcion: ese lleva el nombre completo de la
     * clase destino y el fragmento del cuerpo. Para un enum si se enumeran los valores admitidos,
     * que son parte del contrato --el cliente los manda-- y es lo que de verdad necesita saber.
     */
    private static ErrorDeCampo aErrorDeCampo(MismatchedInputException desajuste) {
        String campo = rutaDe(desajuste);
        if (desajuste instanceof InvalidFormatException formato) {
            Class<?> destino = formato.getTargetType();
            if (destino != null && destino.isEnum()) {
                String admitidos = Arrays.stream(destino.getEnumConstants())
                        .map(valor -> ((Enum<?>) valor).name())
                        .collect(Collectors.joining(", "));
                return new ErrorDeCampo(campo, "VALOR_NO_ADMITIDO", "Valores admitidos: " + admitidos + ".");
            }
        }
        return new ErrorDeCampo(campo, "FORMATO", "El valor no tiene el formato esperado.");
    }

    /**
     * La ruta del campo tal como la da Jackson, en el mismo formato que usa
     * {@link FieldError#getField()}: nombres separados por punto e indices entre corchetes, por
     * ejemplo {@code unidades[0].code}. Vacia si Jackson no supo donde estaba.
     */
    private static String rutaDe(MismatchedInputException desajuste) {
        StringBuilder ruta = new StringBuilder();
        for (var referencia : desajuste.getPath()) {
            String nombre = referencia.getPropertyName();
            if (nombre != null) {
                if (!ruta.isEmpty()) {
                    ruta.append('.');
                }
                ruta.append(nombre);
            } else if (referencia.getIndex() >= 0) {
                ruta.append('[').append(referencia.getIndex()).append(']');
            }
        }
        return ruta.toString();
    }

    /**
     * El codigo y el mensaje de un {@link FieldError} son @Nullable: el codigo falta cuando el
     * rechazo no viene de una anotacion, y el mensaje cuando no hay plantilla resuelta. El cliente
     * ramifica por el codigo, asi que es mejor un valor explicito que un hueco en el JSON.
     */
    private static ErrorDeCampo aErrorDeCampo(FieldError fallo) {
        return new ErrorDeCampo(
                fallo.getField(),
                requireNonNullElse(fallo.getCode(), "INVALIDO"),
                requireNonNullElse(fallo.getDefaultMessage(), "El valor no es valido."));
    }

    private static String rutaDe(WebRequest peticion) {
        return peticion.getDescription(false).replaceFirst("^uri=", "");
    }
}
