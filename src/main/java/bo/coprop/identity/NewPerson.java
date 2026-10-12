package bo.coprop.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Una persona por dar de alta.
 *
 * <p>No lleva condominio: la persona es global. RF-03 pide que la misma sea propietaria en un
 * condominio e inquilina en otro, asi que la fila se comparte entre edificios. Lo que esta acotado
 * por condominio son la membresia y las relaciones con la unidad.
 *
 * <p>No lleva correo tampoco: el correo es de la cuenta, no de la persona (Seguridad 2). El
 * resumen del issue #11 decia "Person identificada por correo" y es lo que no se siguio.
 *
 * @param firstNames nombres
 * @param lastNames apellidos
 * @param nationalId cedula de identidad; opcional, porque de un copropietario heredado no siempre
 *     se tiene y exigirla bloquearia el alta masiva del edificio
 */
public record NewPerson(
        @NotBlank @Size(max = 120) String firstNames,
        @NotBlank @Size(max = 120) String lastNames,
        @Nullable @Size(max = 20) String nationalId) {}
