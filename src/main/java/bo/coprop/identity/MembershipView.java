package bo.coprop.identity;

import java.util.Set;
import java.util.UUID;

/**
 * Lo que se devuelve de una membresia: el vinculo de una cuenta con un condominio y sus roles.
 *
 * <p>Los roles vienen como conjunto porque una membresia puede tener varios: un propietario que
 * tambien esta en el directorio (Seguridad 2).
 */
public record MembershipView(
        UUID id, UUID accountId, UUID condominiumId, MembershipStatus status, Set<MembershipRole> roles) {}
