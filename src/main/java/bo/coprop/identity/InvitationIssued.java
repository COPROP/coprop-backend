package bo.coprop.identity;

import bo.coprop.shared.AuditableEvent;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Se emitio una invitacion a un condominio.
 *
 * <p>Lo quiere la bitacora del issue <strong>#21</strong>, que es el tercer criterio de aceptacion
 * del #18: toda invitacion, aceptacion y revocacion queda registrada.
 *
 * <p><strong>No lleva el token, ni en claro ni en hash.</strong> Es el motivo por el que el correo
 * de la invitacion NO se envia reaccionando a este evento: los eventos de este sistema se persisten
 * --el registro de la migracion V2-- asi que meter el token aqui lo dejaria legible en la base, que
 * es exactamente lo que Seguridad 6.1 evita guardando solo su hash. El envio ocurre en la emision,
 * que es el unico momento en que el token existe en memoria.
 *
 * @param reenviada si sustituye a una invitacion anterior que se revoco en el acto; el
 *     administrador que reenvia y el que emite por primera vez hacen lo mismo, pero la bitacora
 *     tiene que poder distinguirlos
 */
public record InvitationIssued(
        UUID condominiumId,
        UUID invitationId,
        UUID accountId,
        Set<MembershipRole> roles,
        Instant expiresAt,
        boolean resent,
        String actor,
        Instant occurredAt)
        implements AuditableEvent {

    /**
     * Un reenvio y una primera emision son <strong>dos acciones distintas</strong> en la bitacora,
     * no la misma con una bandera. Quien la lee busca "a quien se invito" o "a quien hubo que
     * insistirle", y eso con una bandera dentro del JSON no se filtra.
     */
    @Override
    public String action() {
        return resent ? "INVITATION_RESENT" : "INVITATION_ISSUED";
    }

    @Override
    public String entity() {
        return "invitacion";
    }

    @Override
    public UUID entityId() {
        return invitationId;
    }

    /** Sin el token ni su hash: el evento no los lleva, y la bitacora tampoco tiene por que. */
    @Override
    public java.util.Map<String, String> newValues() {
        return java.util.Map.of(
                "accountId",
                accountId.toString(),
                "roles",
                roles.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")),
                "expiresAt",
                expiresAt.toString());
    }
}
