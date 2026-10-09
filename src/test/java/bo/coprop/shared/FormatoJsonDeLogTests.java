package bo.coprop.shared;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * El criterio de aceptacion del issue #6: las credenciales de proveedor no aparecen en los logs.
 *
 * <p>Se prueba sobre el formateador real, el mismo que produccion activa con
 * {@code logging.structured.format.console}, y no sobre {@link Enmascarador} a secas: lo que
 * importa no es que la funcion sepa tapar, sino que todo lo que sale de un evento pase por ella,
 * incluidos el MDC y el mensaje de la excepcion.
 */
class FormatoJsonDeLogTests {

    private static final String CREDENCIAL = "s3cr3t-del-banco-que-no-debe-salir";

    private final FormatoJsonDeLog formato = new FormatoJsonDeLog();

    @Test
    @DisplayName("la credencial no sale en el mensaje")
    void noSaleEnElMensaje() {
        String json = formato.format(evento("respuesta del proveedor: {\"client_secret\":\"" + CREDENCIAL + "\"}"));

        assertThat(json).doesNotContain(CREDENCIAL).contains(Enmascarador.TAPADO);
    }

    @Test
    @DisplayName("la credencial no sale desde el MDC")
    void noSaleDesdeElMdc() {
        LoggingEvent evento = evento("pago aplicado");
        evento.setMDCPropertyMap(Map.of(ClavesDeLog.TRAZA, "abc-123", "token", CREDENCIAL));

        String json = formato.format(evento);

        assertThat(json).doesNotContain(CREDENCIAL).contains("\"traceId\":\"abc-123\"");
    }

    @Test
    @DisplayName("la credencial no sale desde el mensaje de la excepcion ni de su traza")
    void noSaleDesdeLaExcepcion() {
        LoggingEvent evento = evento("fallo llamando al proveedor");
        evento.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(
                new IllegalStateException("rechazado con apiKey=" + CREDENCIAL)));

        String json = formato.format(evento);

        assertThat(json).doesNotContain(CREDENCIAL).contains("\"error.type\"");
    }

    @Test
    @DisplayName("sale una sola linea de JSON valido, con las comillas escapadas")
    void saleUnaLineaDeJsonValido() {
        String json = formato.format(evento("texto con \"comillas\" y un salto\nde linea"));

        assertThat(json).endsWith("}\n");
        assertThat(json.substring(0, json.length() - 1)).doesNotContain("\n");
        assertThat(json).contains("\\\"comillas\\\"").contains("\\n");
    }

    private static LoggingEvent evento(String mensaje) {
        Logger logger = (Logger) LoggerFactory.getLogger(FormatoJsonDeLogTests.class);
        LoggingEvent evento = new LoggingEvent(Logger.FQCN, logger, Level.INFO, mensaje, null, null);
        evento.setLoggerContext((LoggerContext) LoggerFactory.getILoggerFactory());
        return evento;
    }
}
