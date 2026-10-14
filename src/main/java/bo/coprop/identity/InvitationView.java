package bo.coprop.identity;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se devuelve de una invitacion.
 *
 * <p><strong>No lleva el token.</strong> El token se genera una vez, viaja al correo de la persona
 * invitada y no vuelve a existir: ni en esta vista, ni en la base, donde solo queda su hash
 * (Seguridad 6.1). Un administrador que liste las invitaciones ve a quien invito y hasta cuando
 * vale, no con que enlace entrar. Si hace falta uno nuevo, se reenvia.
 *
 * <p>No hay campo de estado. Los tres cierres son fechas o el reloj: {@code acceptedAt} y
 * {@code revokedAt} nulos y {@code expiresAt} en el futuro significa que sigue viva. Un estado
 * ademas de las fechas seria un segundo sitio donde mirar lo mismo, y el dia que vence nadie lo
 * actualizaria.
 *
 * @param acceptedAt nulo mientras no se acepte
 * @param revokedAt nulo mientras no se revoque
 */
public record InvitationView(
        UUID id,
        UUID condominiumId,
        UUID accountId,
        Set<MembershipRole> roles,
        Instant expiresAt,
        Instant createdAt,
        @Nullable Instant acceptedAt,
        @Nullable Instant revokedAt,
        UUID invitedBy) {}
