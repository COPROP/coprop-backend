package bo.coprop.shared;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

/**
 * Formato de log en JSON, una linea por evento.
 *
 * <p>Se escribe en vez de usar uno de los que trae Spring Boot (ECS, Logstash, GELF) por una razon
 * concreta: **todo lo que sale pasa por {@link Enmascarador}**, incluido el MDC y el mensaje de la
 * excepcion. Con un formato de fabrica habria que envolverlo igualmente, y envolver algo que no
 * controlas para quitarle cosas es mas fragil que escribir las ocho claves que de verdad se usan.
 *
 * <p>Se activa con {@code logging.structured.format.console} apuntando a esta clase. En local no
 * se activa: alli manda la legibilidad, y el enmascarado lo aplica {@code ConversorEnmascarado}
 * sobre el patron de consola.
 */
public class FormatoJsonDeLog implements StructuredLogFormatter<ILoggingEvent> {

    @Override
    public String format(ILoggingEvent evento) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        campo(json, "timestamp", Instant.ofEpochMilli(evento.getTimeStamp()).toString(), true);
        campo(json, "level", evento.getLevel().toString(), false);
        campo(json, "logger", evento.getLoggerName(), false);
        campo(json, "thread", evento.getThreadName(), false);
        campo(json, "message", Enmascarador.enmascarar(evento.getFormattedMessage()), false);

        // El MDC entero, enmascarado: asi una clave nueva (condominiumId, actorId) aparece en los
        // logs sin tocar esta clase.
        for (Map.Entry<String, String> entrada : evento.getMDCPropertyMap().entrySet()) {
            campo(json, entrada.getKey(), Enmascarador.valorDe(entrada.getKey(), entrada.getValue()), false);
        }

        IThrowableProxy fallo = evento.getThrowableProxy();
        if (fallo != null) {
            campo(json, "error.type", fallo.getClassName(), false);
            campo(json, "error.message", Enmascarador.enmascarar(fallo.getMessage()), false);
            campo(json, "error.stack_trace", Enmascarador.enmascarar(ThrowableProxyUtil.asString(fallo)), false);
        }
        json.append("}\n");
        return json.toString();
    }

    private static void campo(StringBuilder json, String clave, @Nullable String valor, boolean primero) {
        if (!primero) {
            json.append(',');
        }
        json.append('"');
        escapar(json, clave);
        json.append("\":\"");
        escapar(json, valor);
        json.append('"');
    }

    /** Escapado JSON de una cadena. Solo cadenas: aqui ningun valor se emite como numero. */
    private static void escapar(StringBuilder json, @Nullable String texto) {
        if (texto == null) {
            return;
        }
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
    }
}
