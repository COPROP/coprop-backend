package bo.coprop.condominium;

import bo.coprop.shared.AuditableEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Cambio el dia de emision o el de vencimiento de un condominio.
 *
 * <p>Es el cambio que mas falta hace tener auditado de todo este modulo: mueve la fecha en que
 * vencen las obligaciones y, con ella, cuando empieza a correr la mora. La fila de configuracion
 * cerrada ya dice <em>cuando</em> cambio; este evento dice <strong>quien</strong> lo cambio y
 * <strong>desde que valores</strong>, que es lo que el analisis 12.2 pide de la bitacora (actor,
 * accion, antes y despues). Lo consumira el issue <strong>#21</strong>.
 *
 * @param previousIssueDay el dia de emision que estaba vigente hasta ahora
 * @param previousDueDay el dia de vencimiento que estaba vigente hasta ahora
 * @param issueDay el nuevo dia de emision
 * @param dueDay el nuevo dia de vencimiento
 * @param actor quien lo cambio
 * @param occurredAt cuando
 */
public record CondominiumConfigChanged(
        UUID condominiumId,
        int previousIssueDay,
        int previousDueDay,
        int issueDay,
        int dueDay,
        String actor,
        Instant occurredAt)
        implements AuditableEvent {

    @Override
    public String action() {
        return "CONFIGURATION_CHANGED";
    }

    @Override
    public String entity() {
        return "configuracion";
    }

    /**
     * El condominio, no la fila de configuracion.
     *
     * <p>La configuracion lleva vigencia: cada cambio cierra una fila y abre otra, asi que su
     * identificador es distinto en cada cambio y no serviria para seguir la historia. Lo que se
     * audita es la configuracion <em>de este condominio</em>, y por eso la entidad es el condominio.
     */
    @Override
    public UUID entityId() {
        return condominiumId;
    }

    /**
     * Este es el evento que mejor muestra para que sirven antes y despues: cambia algo que ya
     * existia, asi que las dos mitades estan.
     */
    @Override
    public java.util.Map<String, String> oldValues() {
        return java.util.Map.of("issueDay", String.valueOf(previousIssueDay), "dueDay", String.valueOf(previousDueDay));
    }

    @Override
    public java.util.Map<String, String> newValues() {
        return java.util.Map.of("issueDay", String.valueOf(issueDay), "dueDay", String.valueOf(dueDay));
    }
}
