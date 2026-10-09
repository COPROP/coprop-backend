package bo.coprop.shared;

/**
 * La peticion esta bien formada y el estado la admite, pero una regla del dominio la rechaza.
 *
 * <p>Se separa de {@link ConflictoDeEstado} porque el cliente hace cosas distintas: ante un
 * conflicto conviene recargar y reintentar; ante una regla violada, no.
 */
public class ReglaDeNegocioViolada extends ErrorDeDominio {

    public ReglaDeNegocioViolada(String mensaje) {
        super(CodigoDeError.REGLA_DE_NEGOCIO, mensaje);
    }
}
