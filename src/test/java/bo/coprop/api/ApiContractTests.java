package bo.coprop.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.shared.ApiController;
import bo.coprop.shared.BusinessRuleViolated;
import bo.coprop.shared.ResourceNotFound;
import bo.coprop.shared.StateConflict;
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
@Import({TestcontainersConfiguration.class, ApiContractTests.TestController.class})
@SpringBootTest
@AutoConfigureMockMvc
class ApiContractTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    @DisplayName("la validacion devuelve 422 con la lista de campos, no el 400 de Spring")
    void validationReturns422WithTheFieldList() throws Exception {
        mockMvc.perform(post("/api/v1/tests/validation")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.errors[0].field").value("number"))
                .andExpect(jsonPath("$.errors[0].code").value("NotBlank"));
    }

    @Test
    @WithMockUser
    @DisplayName("un recurso inexistente devuelve 404 con su codigo estable")
    void resourceNotFoundReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/tests/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.type").value("https://coprop.bo/errors/not-found"));
    }

    @Test
    @WithMockUser
    @DisplayName("un choque con el estado actual devuelve 409 y una regla violada 422")
    void conflictAndRuleAreDistinguished() throws Exception {
        mockMvc.perform(get("/api/v1/tests/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        mockMvc.perform(get("/api/v1/tests/rule"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    @WithMockUser
    @DisplayName("un fallo no previsto devuelve 500 sin filtrar el mensaje de la excepcion")
    void anUnexpectedErrorDoesNotLeakTheDetail() throws Exception {
        mockMvc.perform(get("/api/v1/tests/blows-up"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(jsonPath("$.detail").value(not(containsString("tabla_secreta"))));
    }

    @Test
    @DisplayName("sin autenticar devuelve 401 en problem+json, no un cuerpo vacio")
    void withoutAuthenticatingItReturns401InProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/tests/not-found"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_AUTHENTICATED"));
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
    void healthAndTheSpecArePublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("COPROP"));
    }

    @Test
    @WithMockUser
    @DisplayName("el prefijo de version lo pone la configuracion, no el controlador")
    void theVersionPrefixIsAppliedOnItsOwn() throws Exception {
        mockMvc.perform(get("/tests/not-found")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/tests/not-found"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser
    @DisplayName("un cuerpo que no es JSON da 400 con su codigo, no un problem+json mudo")
    void aBrokenBodyGives400WithACode() throws Exception {
        // Antes este caso no pasaba por ningun manejador nuestro: contestaba
        // ResponseEntityExceptionHandler y el cuerpo salia sin codigo, contra lo que promete el
        // contrato. Tanto, que el interceptor del frontend tiene que inventarse uno cuando falta.
        mockMvc.perform(post("/api/v1/tests/enum")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{esto no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNREADABLE_BODY"))
                .andExpect(jsonPath("$.type").value("https://coprop.bo/errors/unreadable-body"))
                // Sin eco de lo que mando el cliente ni del fragmento que Jackson no supo leer.
                .andExpect(jsonPath("$.detail").value(not(containsString("esto no es json"))));
    }

    @Test
    @WithMockUser
    @DisplayName("un valor de enum inadmisible da 422 y enumera los admitidos")
    void anUnacceptableEnumGives422() throws Exception {
        // El JSON esta bien formado, asi que el 422 es el estado correcto (RFC 9110) y el cliente
        // recibe la misma forma que en cualquier otra validacion: campo, codigo y mensaje.
        mockMvc.perform(post("/api/v1/tests/enum")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"color\":\"AZUL\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.errors[0].field").value("color"))
                .andExpect(jsonPath("$.errors[0].code").value("VALUE_NOT_ALLOWED"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("ROJO, VERDE")))
                // El nombre de la clase destino no sale: es ruido para el cliente.
                .andExpect(jsonPath("$.errors[0].message").value(not(containsString("Color"))));
    }

    @Test
    @WithMockUser
    @DisplayName("un tipo que no encaja da 422 y señala el campo, aunque no sea un enum")
    void aTypeThatDoesNotFitGives422() throws Exception {
        mockMvc.perform(post("/api/v1/tests/validation")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":{}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.errors[0].field").value("number"))
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
    }

    @Test
    @WithMockUser
    @DisplayName("el traceId viaja en la cabecera y dentro del cuerpo del error")
    void theTraceIdTravelsInTheResponse() throws Exception {
        mockMvc.perform(get("/api/v1/tests/not-found").header("X-Trace-Id", "abc-123"))
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
    static class TestController {

        @ApiController
        @RestController
        @RequestMapping("/tests")
        static class Endpoints {

            record Entry(@NotBlank String number) {}

            @GetMapping("/not-found")
            void notFound() {
                throw ResourceNotFound.de("Unidad", 7);
            }

            @GetMapping("/conflict")
            void conflict() {
                throw new StateConflict("La obligacion ya estaba pagada.");
            }

            @GetMapping("/rule")
            void rule() {
                throw new BusinessRuleViolated("No se puede pagar un mes mas nuevo primero.");
            }

            @GetMapping("/blows-up")
            void itBlowsUp() {
                throw new IllegalStateException("fallo al leer tabla_secreta");
            }

            @PostMapping("/validation")
            void validation(@Valid @RequestBody Entry entry) {}

            /** Para los dos caminos del cuerpo ilegible: un enum y un desajuste de tipo. */
            enum Color {
                ROJO,
                VERDE
            }

            record EnumEntry(Color color) {}

            @PostMapping("/enum")
            void conEnum(@Valid @RequestBody EnumEntry entry) {}
        }
    }
}
