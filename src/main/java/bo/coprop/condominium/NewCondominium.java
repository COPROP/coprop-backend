package bo.coprop.condominium;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Lo minimo para dar de alta un condominio, que es el criterio de aceptacion del issue #9.
 *
 * <p>La moneda no se pide: el analisis 2.1 y 12.2 fijan BOB para todo el MVP, y un campo que solo
 * admite un valor es una invitacion a que alguien meta otro sin que nada lo valide.
 *
 * @param name nombre del condominio
 * @param nit numero de identificacion tributaria
 * @param type edificio, casas o mixto
 * @param timeZone zona horaria IANA; de ella dependen los vencimientos y la mora (analisis 12.2)
 * @param issueDay dia del mes en que se emiten las obligaciones
 * @param dueDay dia del mes en que vencen
 */
public record NewCondominium(
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 20) String nit,
        @NotNull CondominiumType type,
        @NotBlank @Size(max = 64) String timeZone,
        @Min(1) @Max(28) int issueDay,
        @Min(1) @Max(28) int dueDay) {}
