package bo.coprop.condominium;

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
        Instant occurredAt) {}
