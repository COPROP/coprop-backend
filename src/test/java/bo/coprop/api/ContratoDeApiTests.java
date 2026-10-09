package bo.coprop.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.shared.ConflictoDeEstado;
import bo.coprop.shared.ControladorDeApi;
import bo.coprop.shared.RecursoNoEncontrado;
import bo.coprop.shared.ReglaDeNegocioViolada;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifica el contrato del issue #5 sobre un controlador que solo existe en los tests.
 *
 * <p>Se prueba contra un controlador de mentira a proposito: el contrato tiene que valer antes de
 * que exista el primer endpoint de verdad, que es justamente el motivo del issue.
 */
@Import({TestcontainersConfiguration.class, ContratoDeApiTests.ControladorDePrueba.class})
@SpringBootTest
@AutoConfigureMockMvc
class ContratoDeApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    @DisplayName("la validacion devuelve 422 con la lista de campos, no el 400 de Spring")
    void validacionDevuelve422ConLaListaDeCampos() throws Exception {
        mockMvc.perform(post("/api/v1/pruebas/validacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"numero\":\"\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value("numero"))
                .andExpect(jsonPath("$.errores[0].codigo").value("NotBlank"));
    }

    @Test
    @WithMockUser
    @DisplayName("un recurso inexistente devuelve 404 con su codigo estable")
    void recursoNoEncontradoDevuelve404() throws Exception {
        mockMvc.perform(get("/api/v1/pruebas/no-encontrado"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"))
                .andExpect(jsonPath("$.type").value("https://coprop.bo/errores/no-encontrado"));
    }

    @Test
    @WithMockUser
    @DisplayName("un choque con el estado actual devuelve 409 y una regla violada 422")
    void conflictoYReglaSeDistinguen() throws Exception {
        mockMvc.perform(get("/api/v1/pruebas/conflicto"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CONFLICTO"));

        mockMvc.perform(get("/api/v1/pruebas/regla"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("REGLA_DE_NEGOCIO"));
    }

    @Test
    @WithMockUser
    @DisplayName("un fallo no previsto devuelve 500 sin filtrar el mensaje de la excepcion")
    void errorNoPrevistoNoFiltraElDetalle() throws Exception {
        mockMvc.perform(get("/api/v1/pruebas/estalla"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.codigo").value("INTERNO"))
                .andExpect(jsonPath("$.detail").value(not(containsString("tabla_secreta"))));
    }

    @Test
    @DisplayName("sin autenticar devuelve 401 en problem+json, no un cuerpo vacio")
    void sinAutenticarDevuelve401EnProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/pruebas/no-encontrado"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }

    /**
     * El health se consulta con el indicador de correo apagado (ver la anotacion de la clase).
     *
     * <p>No es para que pase el test: spring-boot-starter-mail registra un indicador que intenta
     * conectar al SMTP, asi que sin Mailpit delante el health agrega 503 aunque la aplicacion este
     * perfectamente viva. Eso hacia fallar el test en CI, donde no hay Mailpit, mientras pasaba en
     * local. Si el correo caido debe o no tumbar el health es una decision de observabilidad, y va
     * con el issue #6; aqui solo se comprueba lo que este test dice comprobar, que la seguridad no
     * exige autenticacion en esa ruta.
     */
    @Test
    @DisplayName("el health y la especificacion OpenAPI no piden autenticacion")
    void elHealthYLaEspecificacionSonPublicos() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("COPROP"));
    }

    @Test
    @WithMockUser
    @DisplayName("el prefijo de version lo pone la configuracion, no el controlador")
    void elPrefijoDeVersionSeAplicaSolo() throws Exception {
        mockMvc.perform(get("/pruebas/no-encontrado")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/pruebas/no-encontrado"))
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser
    @DisplayName("el traceId viaja en la cabecera y dentro del cuerpo del error")
    void elTraceIdViajaEnLaRespuesta() throws Exception {
        mockMvc.perform(get("/api/v1/pruebas/no-encontrado").header("X-Trace-Id", "abc-123"))
                .andExpect(header().string("X-Trace-Id", "abc-123"))
                .andExpect(jsonPath("$.traceId").value("abc-123"));
    }

    /**
     * Controlador de mentira: existe solo para que haya algo que falle de cada manera.
     *
     * <p>La clase anidada se registra sola por ser {@code @RestController}; declararla ademas como
     * {@code @Bean} la duplicaria y el mapeo saldria ambiguo.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ControladorDePrueba {

        @ControladorDeApi
        @RestController
        @RequestMapping("/pruebas")
        static class Endpoints {

            record Entrada(@NotBlank String numero) {}

            @GetMapping("/no-encontrado")
            void noEncontrado() {
                throw RecursoNoEncontrado.de("Unidad", 7);
            }

            @GetMapping("/conflicto")
            void conflicto() {
                throw new ConflictoDeEstado("La obligacion ya estaba pagada.");
            }

            @GetMapping("/regla")
            void regla() {
                throw new ReglaDeNegocioViolada("No se puede pagar un mes mas nuevo primero.");
            }

            @GetMapping("/estalla")
            void estalla() {
                throw new IllegalStateException("fallo al leer tabla_secreta");
            }

            @PostMapping("/validacion")
            void validacion(@Valid @RequestBody Entrada entrada) {}
        }
    }
}
