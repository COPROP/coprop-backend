package bo.coprop.condominium;

import bo.coprop.shared.EventoAuditable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Se registro una titularidad sobre una unidad.
 *
 * <p>Es un cambio con consecuencias economicas --de aqui sale a quien se le cobra-- asi que la
 * bitacora tiene que poder decir quien lo hizo y con que porcentaje.
 *
 * <p>Entro en la bitacora con el issue <strong>#19</strong>, que es el que trajo el cierre de las
 * tres relaciones. Se hizo entonces y no en el #21 por una razon concreta: una bitacora que
 * registra el fin de una relacion y no su principio es peor que una que no registre ninguno, porque
 * quien la lea creera que la relacion nacio sola.
 */
public record OwnershipGranted(
        UUID condominiumId, UUID unitId, UUID personId, BigDecimal share, String actor, Instant occurredAt)
        implements EventoAuditable {

    @Override
    public String action() {
        return "OWNERSHIP_GRANTED";
    }

    @Override
    public String entity() {
        return "titularidad";
    }

    /** La unidad, por lo mismo que en {@link OwnershipTransferred}: la relacion lleva vigencia. */
    @Override
    public UUID entityId() {
        return unitId;
    }

    @Override
    public Map<String, String> newValues() {
        return Map.of("personId", personId.toString(), "share", share.toPlainString());
    }
}
