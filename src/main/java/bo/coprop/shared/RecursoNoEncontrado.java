package bo.coprop.shared;

/**
 * El recurso pedido no existe, o el usuario no puede verlo.
 *
 * <p>Las dos cosas responden igual a proposito. Si un propietario pide una unidad de otro
 * condominio, distinguir "no existe" de "no puedes" le diria que ese condominio existe.
 */
public class RecursoNoEncontrado extends ErrorDeDominio {

    public RecursoNoEncontrado(String mensaje) {
        super(CodigoDeError.NO_ENCONTRADO, mensaje);
    }

    /** Mensaje uniforme para el caso corriente: {@code de("Unidad", id)}. */
    public static RecursoNoEncontrado de(String recurso, Object identificador) {
        return new RecursoNoEncontrado("No se encontro %s %s.".formatted(recurso, identificador));
    }
}
