package bo.coprop.condominium;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
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
 * Los endpoints de condominios, sobre la aplicacion entera.
 *
 * <p>Comprueba lo que el test de modulo no puede: que la ruta lleva el prefijo de version, que la
 * validacion sale en problem+json con la forma que fijo el issue #5, y que el dominio y el HTTP
 * estan bien cosidos.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CondominiumControllerTests {

    private static final String ALTA_VALIDA =
            """
            {"name":"Las Palmas","nit":"9876543210","type":"EDIFICIO",
             "timeZone":"America/La_Paz","issueDay":1,"dueDay":15}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "emma")
    @DisplayName("el alta devuelve 201 con la cabecera Location y el condominio creado")
    void elAltaDevuelve201() throws Exception {
        mockMvc.perform(post("/api/v1/condominios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ALTA_VALIDA))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Las Palmas"))
                .andExpect(jsonPath("$.currency").value("BOB"))
                .andExpect(jsonPath("$.status").value("ACTIVO"));
    }

    @Test
    @WithMockUser(username = "emma")
    @DisplayName("un dia de emision fuera de rango da 422 en problem+json, con el campo senalado")
    void elDiaFueraDeRangoDa422() throws Exception {
        String conDia31 = ALTA_VALIDA.replace("\"issueDay\":1", "\"issueDay\":31");

        mockMvc.perform(post("/api/v1/condominios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conDia31))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value("issueDay"));
    }

    @Test
    @WithMockUser(username = "emma")
    @DisplayName("consultar uno que no existe da 404 con su codigo, no un 500")
    void elQueNoExisteDa404() throws Exception {
        mockMvc.perform(get("/api/v1/condominios/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("sin autenticar no se puede dar de alta un condominio")
    void sinAutenticarNoSePuedeDarDeAlta() throws Exception {
        mockMvc.perform(post("/api/v1/condominios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ALTA_VALIDA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }
}
