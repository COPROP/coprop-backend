package bo.coprop.condominium;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se devuelve de un condominio.
 *
 * <p>Es un record y no la entidad: la entidad es interna al modulo y exponerla ataria el contrato
 * de la API a como este guardado el dato.
 *
 * @param address nula mientras nadie la haya escrito: el alta no la pide (issue #90). Un cliente que
 *     la pinte tiene que contar con que falte, y en el listado de condominios es justo la señal de
 *     un alta a medio terminar
 */
public record CondominiumView(
        UUID id,
        String name,
        String nit,
        @Nullable String address,
        CondominiumType type,
        String timeZone,
        String currency,
        CondominiumStatus status,
        int issueDay,
        int dueDay) {}
