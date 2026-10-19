package bo.coprop.shared;

/**
 * Raiz de los errores que el dominio levanta y la capa web traduce a problem+json.
 *
 * <p>Lleva su codigo del catalogo y nada mas. En particular no lleva estado HTTP: el dominio no
 * sabe que existe HTTP, y esa traduccion vive en el modulo api.
 */
public abstract class DomainError extends RuntimeException {

    private final ErrorCode code;

    protected DomainError(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
