package bo.coprop.identity;

import bo.coprop.shared.AuditableEvent;
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
        Instant occurredAt)
        implements AuditableEvent {

    @Override
    public String action() {
        return "MEMBERSHIP_GRANTED";
    }

    @Override
    public String entity() {
        return "membresia";
    }

    @Override
    public UUID entityId() {
        return membershipId;
    }

    /**
     * No hay valores de antes: la membresia nace aqui.
     *
     * <p>Lo que se guarda despues es la cuenta y los roles, que es lo que hay que poder reconstruir
     * dentro de un año sin volver a leer la fila: para entonces la membresia puede estar cerrada o
     * con otros roles. Los roles se ordenan al escribirlos para que dos filas con los mismos roles
     * se vean iguales.
     */
    @Override
    public java.util.Map<String, String> newValues() {
        return java.util.Map.of(
                "accountId", accountId.toString(),
                "roles", roles.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")));
    }
}
