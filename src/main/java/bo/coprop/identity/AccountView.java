package bo.coprop.identity;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se devuelve de una cuenta de acceso.
 *
 * <p>No incluye el {@code idpSubject}: es el puente interno hacia Keycloak y no tiene nada que
 * hacer en una respuesta. Tampoco hay contraseña que devolver, porque no se guarda ninguna.
 *
 * @param personId la persona a la que identifica
 * @param phone nulo cuando no se conoce
 */
public record AccountView(
        UUID id, UUID personId, String email, @Nullable String phone, AccountStatus status, boolean mfaEnabled) {}
