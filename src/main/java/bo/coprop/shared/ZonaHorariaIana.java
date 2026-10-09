package bo.coprop.shared;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.ZoneId;
import org.jspecify.annotations.Nullable;

/**
 * El valor tiene que ser una zona horaria IANA conocida por la JVM, como {@code America/La_Paz}.
 *
 * <p>Existe porque el analisis 12.2 calcula los vencimientos y la mora <strong>en la zona del
 * condominio</strong>: una zona mal escrita --{@code America/LaPaz}, sin la barra baja-- entra en
 * la base sin que nada proteste y el sintoma aparece meses despues, en M2, como vencimientos
 * corridos de hora. Vale mas rechazarla en el alta.
 *
 * <p>Rechaza tambien los desplazamientos fijos ({@code Z}, {@code -04:00}) aunque {@link
 * ZoneId#of} los acepte: un desplazamiento no sabe de horario de verano, y la zona del condominio
 * se usa justamente para fechas que caen en cualquier epoca del ano.
 *
 * <p>Un valor nulo o en blanco lo da por bueno: de eso se ocupa {@code @NotBlank}, y una sola
 * causa por anotacion deja mensajes de error que dicen una sola cosa.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ZonaHorariaIana.Validador.class)
public @interface ZonaHorariaIana {

    String message() default "no es una zona horaria IANA conocida";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Anidado en la anotacion: no tiene vida propia y asi no hay que buscarlo en otro archivo. */
    class Validador implements ConstraintValidator<ZonaHorariaIana, String> {

        @Override
        public boolean isValid(@Nullable String valor, ConstraintValidatorContext contexto) {
            return valor == null
                    || valor.isBlank()
                    || ZoneId.getAvailableZoneIds().contains(valor);
        }
    }
}
