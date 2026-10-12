package bo.coprop.identity;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se devuelve de una persona.
 *
 * @param nationalId nulo cuando no se conoce
 */
public record PersonView(UUID id, String firstNames, String lastNames, @Nullable String nationalId) {}
