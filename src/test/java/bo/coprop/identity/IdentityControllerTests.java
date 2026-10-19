package bo.coprop.identity;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los endpoints de personas, cuentas y membresias, sobre la aplicacion entera.
 *
 * <p>Comprueba lo que el test de modulo no puede: el prefijo de version, que la validacion sale en
 * problem+json con la forma del issue #5, y que la cabecera {@code Location} apunta a una ruta que
 * de verdad responde --que fue uno de los defectos que corrigio el PR #72.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class IdentityControllerTests {

    private static final String PERSONA =
            """
            {"firstNames":"Emma","lastNames":"Tola","nationalId":"8123456"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "emma", roles = "CONDO_ADMIN")
    @DisplayName("el alta de persona devuelve 201 con Location y la persona creada")
    void elAltaDePersonaDevuelve201() throws Exception {
        mockMvc.perform(post("/api/v1/personas")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PERSONA))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.firstNames").value("Emma"))
                .andExpect(jsonPath("$.nationalId").value("8123456"));
    }

    @Test
    @WithMockUser(username = "emma", roles = "CONDO_ADMIN")
    @DisplayName("el alta de una persona que ya existe tambien devuelve 201, para no delatarla")
    void elAltaRepetidaTambienDevuelve201() throws Exception {
        mockMvc.perform(post("/api/v1/personas")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PERSONA))
                .andExpect(status().isCreated());

        // Un 200 para "ya existia" y un 201 para "la cree" le diria al administrador de un
        // condominio que esa persona esta en otro. Decision 7.
        mockMvc.perform(post("/api/v1/personas")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PERSONA))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = "emma", roles = "CONDO_ADMIN")
    @DisplayName("un nombre en blanco da 422 en problem+json, con el campo senalado")
    void elNombreEnBlancoDa422() throws Exception {
        String sinNombre = PERSONA.replace("\"firstNames\":\"Emma\"", "\"firstNames\":\"\"");

        mockMvc.perform(post("/api/v1/personas")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sinNombre))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].field").value("firstNames"));
    }

    @Test
    @WithMockUser(username = "emma", roles = "CONDO_ADMIN")
    @DisplayName("una persona que no existe da 404 en problem+json")
    void unaPersonaQueNoExisteDa404() throws Exception {
        mockMvc.perform(get("/api/v1/personas/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(username = "emma", roles = "CONDO_ADMIN")
    @DisplayName("la cuenta se abre invitada y su Location apunta a una ruta que responde")
    void laCuentaSeAbreYSuLocationResponde() throws Exception {
        String personaId = idDe(mockMvc.perform(post("/api/v1/personas")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PERSONA))
                .andReturn()
                .getResponse()
                .getContentAsString());

        String cuentaId = idDe(mockMvc.perform(post("/api/v1/personas/" + personaId + "/cuenta")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"emma@coprop.bo\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/cuentas/")))
                .andReturn()
                .getResponse()
                .getContentAsString());

        // Lo que el PR #72 tuvo que corregir en el alta de unidades: que Location no apunte al
        // vacio. Se sigue la cabecera de verdad en vez de confiar en que este bien formada.
        mockMvc.perform(get("/api/v1/cuentas/" + cuentaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVITED"))
                .andExpect(jsonPath("$.mfaEnabled").value(false));
    }

    @Test
    @WithMockUser(username = "emma", roles = "CONDO_ADMIN")
    @DisplayName("un correo mal formado da 422")
    void unCorreoMalFormadoDa422() throws Exception {
        String personaId = idDe(mockMvc.perform(post("/api/v1/personas")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PERSONA))
                .andReturn()
                .getResponse()
                .getContentAsString());

        mockMvc.perform(post("/api/v1/personas/" + personaId + "/cuenta")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"no-es-un-email\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
    }

    @Test
    @WithMockUser(username = "emma", roles = "CONDO_ADMIN")
    @DisplayName("una membresia sin roles da 422, no un error de dominio")
    void unaMembresiaSinRolesDa422() throws Exception {
        mockMvc.perform(post("/api/v1/condominios/" + UUID.randomUUID() + "/membresias")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"roles\":[]}".formatted(UUID.randomUUID())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].field").value("roles"));
    }

    @Test
    @WithMockUser(username = "emma", roles = "CONDO_ADMIN")
    @DisplayName("un rol fuera del catalogo da 422 y dice cuales se admiten")
    void unRolDesconocidoDa422() throws Exception {
        // El JSON esta bien formado; lo que no se admite es el valor, asi que el cliente recibe lo
        // mismo que en cualquier otra validacion: 422, VALIDACION y el campo para marcar. Antes
        // daba 400 y sin codigo, porque Jackson falla antes de que haya objeto que validar.
        mockMvc.perform(post("/api/v1/condominios/" + UUID.randomUUID() + "/membresias")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"roles\":[\"JEFE\"]}".formatted(UUID.randomUUID())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].field").value("roles[0]"))
                .andExpect(jsonPath("$.errores[0].code").value("VALOR_NO_ADMITIDO"))
                .andExpect(jsonPath("$.errores[0].message").value(org.hamcrest.Matchers.containsString("OWNER")));
    }

    @Test
    @WithMockUser(username = "emma", roles = "CONDO_ADMIN")
    @DisplayName("una cuenta que no existe da 404 al conceder membresia")
    void cuentaInexistenteDa404() throws Exception {
        mockMvc.perform(post("/api/v1/condominios/" + UUID.randomUUID() + "/membresias")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"roles\":[\"OWNER\"]}".formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("sin autenticar, los endpoints de identidad dan 401 con su codigo")
    void sinAutenticarDa401() throws Exception {
        mockMvc.perform(post("/api/v1/personas")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PERSONA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }

    private static String idDe(String cuerpoJson) {
        return JsonPath.read(cuerpoJson, "$.id");
    }
}
