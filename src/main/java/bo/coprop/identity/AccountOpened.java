package bo.coprop.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Se abrio una cuenta de acceso para una persona.
 *
 * <p>Nace en estado {@link AccountStatus#INVITED}: tiene cuenta y no ha entrado nunca. El envio
 * de la invitacion con su token es el issue <strong>#18</strong>, y el primer login --que es
 * cuando aparece el {@code idpSubject}-- el <strong>#13</strong>.
 */
public record AccountOpened(UUID accountId, UUID personId, String actor, Instant occurredAt) {}
