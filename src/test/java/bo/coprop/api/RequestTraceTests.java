package bo.coprop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.shared.ApiController;
import bo.coprop.shared.LogKeys;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El primer criterio de aceptacion del issue #6: toda linea de log de una request lleva el mismo
 * traceId.
 *
 * <p>Se comprueba capturando los eventos reales con un {@link ListAppender}, no leyendo el MDC a
 * mano: lo que importa es lo que acaba en el log, y el MDC se podria estar poniendo y perdiendo
 * entre medias sin que un test de la variable lo notara.
 */
@Import({TestcontainersConfiguration.class, RequestTraceTests.ControladorQueRegistra.class})
@SpringBootTest
@AutoConfigureMockMvc
class RequestTraceTests {

    @Autowired
    private MockMvc mockMvc;

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private Logger registroDelControlador;

    @BeforeEach
    void captureLogs() {
        registroDelControlador = (Logger) LoggerFactory.getLogger(ControladorQueRegistra.Endpoints.class);
        captured.start();
        registroDelControlador.addAppender(captured);
        registroDelControlador.setLevel(Level.INFO);
    }

    @AfterEach
    void releaseLogs() {
        registroDelControlador.detachAppender(captured);
        captured.stop();
    }

    @Test
    @WithMockUser
    @DisplayName("las tres lineas de una request comparten traceId, y es el de la cabecera")
    void allLinesShareTheTrace() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/trazas/varias-lineas")).andReturn();

        String ofTheHeader = result.getResponse().getHeader("X-Trace-Id");
        assertThat(ofTheHeader).isNotBlank();

        List<ILoggingEvent> eventos = List.copyOf(captured.list);
        assertThat(eventos).hasSize(3);
        assertThat(eventos)
                .allSatisfy(evento -> assertThat(evento.getMDCPropertyMap()).containsEntry(LogKeys.TRAZA, ofTheHeader));
    }

    @Test
    @WithMockUser
    @DisplayName("dos requests distintas no comparten traceId")
    void twoRequestsDoNotShareATrace() throws Exception {
        String first = mockMvc.perform(get("/api/v1/trazas/varias-lineas"))
                .andReturn()
                .getResponse()
                .getHeader("X-Trace-Id");
        String segunda = mockMvc.perform(get("/api/v1/trazas/varias-lineas"))
                .andReturn()
                .getResponse()
                .getHeader("X-Trace-Id");

        assertThat(first).isNotEqualTo(segunda);

        Set<String> traces = captured.list.stream()
                .map(evento -> evento.getMDCPropertyMap().get(LogKeys.TRAZA))
                .collect(java.util.stream.Collectors.toSet());
        assertThat(traces).containsExactlyInAnyOrder(first, segunda);
    }

    /** Controlador de mentira que escribe varias lineas durante una misma peticion. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ControladorQueRegistra {

        @ApiController
        @RestController
        @RequestMapping("/trazas")
        static class Endpoints {

            private static final org.slf4j.Logger log = LoggerFactory.getLogger(Endpoints.class);

            @GetMapping("/varias-lineas")
            void severalLines() {
                log.info("primera linea");
                log.info("segunda linea, newValues de hacer algo");
                log.info("tercera linea, oldValues de responder");
            }
        }
    }
}
