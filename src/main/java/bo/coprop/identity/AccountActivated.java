package bo.coprop.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Alguien entro por primera vez con una cuenta: queda vinculada al proveedor de identidad y pasa
 * de {@link AccountStatus#INVITADO} a {@link AccountStatus#ACTIVO}.
 *
 * <p>Ocurre una sola vez por cuenta. Los accesos siguientes no publican nada: lo que interesa de
 * ellos es la fecha del ultimo, que se guarda en la propia cuenta, no un evento por request.
 *
 * <p>El actor es el sujeto que entra, asi que no viaja aparte: es {@code idpSubject}.
 */
public record AccountActivated(UUID accountId, UUID personId, String idpSubject, Instant occurredAt) {}
