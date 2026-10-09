package bo.coprop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.shared.ClavesDeLog;
import bo.coprop.shared.ControladorDeApi;
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
@Import({TestcontainersConfiguration.class, TrazasDeRequestTests.ControladorQueRegistra.class})
@SpringBootTest
@AutoConfigureMockMvc
class TrazasDeRequestTests {

    @Autowired
    private MockMvc mockMvc;

    private final ListAppender<ILoggingEvent> capturados = new ListAppender<>();
    private Logger registroDelControlador;

    @BeforeEach
    void capturarLogs() {
        registroDelControlador = (Logger) LoggerFactory.getLogger(ControladorQueRegistra.Endpoints.class);
        capturados.start();
        registroDelControlador.addAppender(capturados);
        registroDelControlador.setLevel(Level.INFO);
    }

    @AfterEach
    void soltarLogs() {
        registroDelControlador.detachAppender(capturados);
        capturados.stop();
    }

    @Test
    @WithMockUser
    @DisplayName("las tres lineas de una request comparten traceId, y es el de la cabecera")
    void todasLasLineasCompartenTraza() throws Exception {
        MvcResult resultado =
                mockMvc.perform(get("/api/v1/trazas/varias-lineas")).andReturn();

        String deLaCabecera = resultado.getResponse().getHeader("X-Trace-Id");
        assertThat(deLaCabecera).isNotBlank();

        List<ILoggingEvent> eventos = List.copyOf(capturados.list);
        assertThat(eventos).hasSize(3);
        assertThat(eventos).allSatisfy(evento -> assertThat(evento.getMDCPropertyMap())
                .containsEntry(ClavesDeLog.TRAZA, deLaCabecera));
    }

    @Test
    @WithMockUser
    @DisplayName("dos requests distintas no comparten traceId")
    void dosRequestsNoCompartenTraza() throws Exception {
        String primera = mockMvc.perform(get("/api/v1/trazas/varias-lineas"))
                .andReturn()
                .getResponse()
                .getHeader("X-Trace-Id");
        String segunda = mockMvc.perform(get("/api/v1/trazas/varias-lineas"))
                .andReturn()
                .getResponse()
                .getHeader("X-Trace-Id");

        assertThat(primera).isNotEqualTo(segunda);

        Set<String> trazas = capturados.list.stream()
                .map(evento -> evento.getMDCPropertyMap().get(ClavesDeLog.TRAZA))
                .collect(java.util.stream.Collectors.toSet());
        assertThat(trazas).containsExactlyInAnyOrder(primera, segunda);
    }

    /** Controlador de mentira que escribe varias lineas durante una misma peticion. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ControladorQueRegistra {

        @ControladorDeApi
        @RestController
        @RequestMapping("/trazas")
        static class Endpoints {

            private static final org.slf4j.Logger log = LoggerFactory.getLogger(Endpoints.class);

            @GetMapping("/varias-lineas")
            void variasLineas() {
                log.info("primera linea");
                log.info("segunda linea, despues de hacer algo");
                log.info("tercera linea, antes de responder");
            }
        }
    }
}
