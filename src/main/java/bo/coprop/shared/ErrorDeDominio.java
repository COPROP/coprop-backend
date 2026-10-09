package bo.coprop.shared;

/**
 * Raiz de los errores que el dominio levanta y la capa web traduce a problem+json.
 *
 * <p>Lleva su codigo del catalogo y nada mas. En particular no lleva estado HTTP: el dominio no
 * sabe que existe HTTP, y esa traduccion vive en el modulo api.
 */
public abstract class ErrorDeDominio extends RuntimeException {

    private final CodigoDeError codigo;

    protected ErrorDeDominio(CodigoDeError codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }

    public CodigoDeError codigo() {
        return codigo;
    }
}
