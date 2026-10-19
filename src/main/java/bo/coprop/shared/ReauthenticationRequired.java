package bo.coprop.shared;

import java.time.Duration;

/**
 * La operacion es sensible y pide volver a demostrar quien eres. Issue <strong>#22</strong>,
 * Seguridad 7.3.
 *
 * <p>Se levanta cuando la autenticacion con la que llego la peticion no muestra segundo factor, o
 * lo muestra pero de hace demasiado. No es un fallo del cliente: es el flujo normal de una
 * operacion sensible la primera vez que se pide en una sesion, y el cliente sabe que hacer con
 * ella --mandar al usuario a reautenticarse y repetir la misma peticion--.
 *
 * <p><strong>Vive en {@code shared} y no en {@code api}</strong> aunque hoy solo la levante la capa
 * web, por el mismo motivo que las demas de esta familia: las operaciones sensibles que Seguridad
 * 7.3 enumera son casi todas de {@code payments} --credenciales del proveedor, devoluciones de
 * saldo, reversiones-- y cuando lleguen tienen que poder exigirlo desde el dominio sin depender de
 * {@code api}, que la regla 6 prohibe.
 *
 * <p>No lleva estado HTTP, como ninguna de esta familia. Que su codigo salga como 401 con el
 * desafio del RFC 9470 lo decide {@code ProblemCatalog}, en {@code api}.
 */
public class ReauthenticationRequired extends DomainError {

    /**
     * Cuanto de reciente tiene que ser la autenticacion, en segundos.
     *
     * <p>Viaja con la excepcion porque es el {@code max_age} del desafio: el cliente necesita el
     * numero para pedirselo al proveedor de identidad, y dejarlo escrito a mano en la capa web lo
     * separaria del sitio donde se comprueba.
     */
    private final Duration validity;

    public ReauthenticationRequired(String message, Duration validity) {
        super(ErrorCode.REAUTENTICACION_REQUERIDA, message);
        this.validity = validity;
    }

    public Duration validity() {
        return validity;
    }
}
