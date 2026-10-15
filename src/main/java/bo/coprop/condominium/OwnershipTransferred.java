package bo.coprop.condominium;

import bo.coprop.shared.EventoAuditable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Se vendio una unidad: la titularidad paso de una persona a otra.
 *
 * <p>Es un evento propio y no dos --una titularidad cerrada y otra abierta-- porque lo que hay que
 * poder reconstruir dentro de un año es la venta, con sus dos lados. Dos filas sueltas en la
 * bitacora obligarian a quien la lee a emparejarlas por la fecha y a adivinar si fueron la misma
 * operacion.
 *
 * <p>Lo que decide Seguridad 6.2 es justo lo que este evento registra: el propietario anterior
 * deja de ver la unidad desde la fecha de transferencia. Quien necesite un comprobante de su epoca
 * lo pide al administrador, que para eso conserva la fila cerrada.
 *
 * @param unitId la unidad vendida
 * @param fromPersonId quien vendio
 * @param toPersonId quien compro
 * @param share el porcentaje que cambio de manos, que es el que tenia el vendedor
 * @param relacionesCerradas cuantas relaciones del vendedor con esa unidad se cerraron con la
 *     venta: la titularidad siempre, y ademas su ocupacion y sus responsabilidades de pago si las
 *     tenia. Va en la bitacora porque es lo que explica que el vendedor perdiera el acceso
 */
public record OwnershipTransferred(
        UUID condominiumId,
        UUID unitId,
        UUID fromPersonId,
        UUID toPersonId,
        BigDecimal share,
        int relacionesCerradas,
        String actor,
        Instant occurredAt)
        implements EventoAuditable {

    @Override
    public String accion() {
        return "TITULARIDAD_TRANSFERIDA";
    }

    @Override
    public String entidad() {
        return "titularidad";
    }

    /**
     * La unidad, no la fila de titularidad.
     *
     * <p>Mismo motivo que en {@link CondominiumConfigChanged}: la titularidad lleva vigencia, asi
     * que cada venta cierra una fila y abre otra y su identificador cambia en cada una. Lo que se
     * sigue en la bitacora es la historia de <em>esta unidad</em>.
     */
    @Override
    public UUID entidadId() {
        return unitId;
    }

    @Override
    public Map<String, String> antes() {
        return Map.of("personId", fromPersonId.toString(), "share", share.toPlainString());
    }

    @Override
    public Map<String, String> despues() {
        return Map.of(
                "personId",
                toPersonId.toString(),
                "share",
                share.toPlainString(),
                "relacionesCerradas",
                String.valueOf(relacionesCerradas));
    }
}
