package bo.coprop.identity;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Una cuenta quedo vinculada a un condominio, con sus roles.
 *
 * <p>Lleva el condominio y los roles dentro porque la bitacora del issue #21 tiene que poder decir
 * que se concedio sin volver a leer la fila, que para entonces puede estar cerrada o con otros
 * roles.
 */
public record MembershipGranted(
        UUID condominiumId,
        UUID membershipId,
        UUID accountId,
        Set<MembershipRole> roles,
        String actor,
        Instant occurredAt) {}
