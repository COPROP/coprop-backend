package bo.coprop.condominium;

import java.util.UUID;

/**
 * Lo que se devuelve de un condominio.
 *
 * <p>Es un record y no la entidad: la entidad es interna al modulo y exponerla ataria el contrato
 * de la API a como este guardado el dato.
 */
public record CondominiumView(
        UUID id,
        String name,
        String nit,
        CondominiumType type,
        String timeZone,
        String currency,
        CondominiumStatus status,
        int issueDay,
        int dueDay) {}
