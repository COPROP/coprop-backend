package bo.coprop.shared;

/**
 * El recurso pedido no existe, o el usuario no puede verlo.
 *
 * <p>Las dos cosas responden igual a proposito. Si un propietario pide una unidad de otro
 * condominio, distinguir "no existe" de "no puedes" le diria que ese condominio existe.
 */
public class ResourceNotFound extends DomainError {

    public ResourceNotFound(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }

    /** Mensaje uniforme para el caso corriente: {@code de("Unidad", id)}. */
    public static ResourceNotFound de(String recurso, Object identificador) {
        return new ResourceNotFound("No se encontro %s %s.".formatted(recurso, identificador));
    }
}
