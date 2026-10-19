package bo.coprop.shared;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Aplica {@link Masker} al mensaje en el patron de consola.
 *
 * <p>Lo registra {@code logback-spring.xml} como la palabra {@code enmascarado}, que sustituye a
 * {@code %m} en el patron. Asi el enmascarado vale tambien en local, donde no se usa el formato
 * JSON y por tanto no pasa por {@link JsonLogLayout}.
 */
public class MaskedConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent evento) {
        return Masker.enmascarar(evento.getFormattedMessage());
    }
}
