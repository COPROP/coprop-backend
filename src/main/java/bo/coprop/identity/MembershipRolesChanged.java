package bo.coprop.identity;

import bo.coprop.shared.AuditableEvent;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Los roles de una membresia cambiaron. Issue <strong>#20</strong>.
 *
 * <p><strong>Es la bitacora, y no la fila, la que guarda la historia de los roles.</strong> El
 * cambio edita {@code membership_role} en el sitio, asi que la tabla solo dice que roles hay hoy;
 * quien tuvo cual y hasta cuando se reconstruye con estas filas, que son inmodificables. Seguridad
 * 8 pide los cambios de rol en la bitacora y esto es el caso central: la baja del #19 era el
 * extremo --quitarlos todos--, y este es el de todos los dias.
 *
 * <p>Lleva las dos mitades, los de antes y los de despues, porque son lo que de verdad se pregunta
 * despues: no "que roles tiene" --eso lo dice la fila-- sino "que le quitaron y quien".
 *
 * @param beforeTheRoles los que tenia; nunca vacio, una membresia no existe sin roles
 * @param roles los que tiene a partir de ahora
 * @param concentratesDuties si la misma persona queda con {@code CONDO_ADMIN} y
 *     {@code FINANCE_ADMIN}. Es el aviso que pide el alcance del issue, y va aqui porque Seguridad
 *     10.1 permite la concentracion a condicion de que quede registrada
 * @param closedSessions si se le cerraron las sesiones, que pasa cuando el cambio le retira un
 *     rol administrativo (Seguridad 8)
 */
public record MembershipRolesChanged(
        UUID condominiumId,
        UUID membershipId,
        UUID accountId,
        Set<MembershipRole> beforeTheRoles,
        Set<MembershipRole> roles,
        String reason,
        boolean concentratesDuties,
        boolean closedSessions,
        String actor,
        Instant occurredAt)
        implements AuditableEvent {

    @Override
    public String action() {
        return "MEMBERSHIP_ROLES_CHANGED";
    }

    @Override
    public String entity() {
        return "membresia";
    }

    @Override
    public UUID entityId() {
        return membershipId;
    }

    /** Lo que habia. La cuenta va en las dos mitades: la fila se lee sin consultar nada mas. */
    @Override
    public Map<String, String> oldValues() {
        return Map.of("accountId", accountId.toString(), "roles", escritos(beforeTheRoles));
    }

    @Override
    public Map<String, String> newValues() {
        return Map.of(
                "roles",
                escritos(roles),
                "motivo",
                reason,
                "concentraFunciones",
                String.valueOf(concentratesDuties),
                "sesionesCerradas",
                String.valueOf(closedSessions));
    }

    /** Ordenados, para que dos cambios con los mismos roles se vean iguales. */
    private static String escritos(Set<MembershipRole> roles) {
        return roles.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }
}
