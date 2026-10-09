package bo.coprop.condominium;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/** Los endpoints de unidades sobre la aplicacion entera. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "emma")
class UnitControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Condominiums condominios;

    private UUID lasPalmas;

    @BeforeEach
    void darDeAltaUnCondominio() {
        lasPalmas = condominios
                .register(
                        new NewCondominium(
                                "Las Palmas", "1234567890", CondominiumType.EDIFICIO, "America/La_Paz", 1, 15),
                        "emma")
                .id();
    }

    private String unidad(String code) {
        return """
                {"code":"%s","type":"DEPARTAMENTO","aliquot":"1.25","areaM2":"82.50"}
                """
                .formatted(code);
    }

    @Test
    @DisplayName("el alta de una unidad devuelve 201 con su identificador")
    void elAltaDevuelve201() throws Exception {
        mockMvc.perform(post("/api/v1/condominios/{id}/unidades", lasPalmas)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unidad("302")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("302"))
                .andExpect(jsonPath("$.status").value("ACTIVA"));
    }

    @Test
    @DisplayName("el alta masiva crea el lote entero en una llamada")
    void elAltaMasivaCreaElLote() throws Exception {
        String lote =
                """
                {"unidades":[
                  {"code":"301","type":"DEPARTAMENTO","aliquot":"1.25"},
                  {"code":"302","type":"DEPARTAMENTO","aliquot":"1.25"},
                  {"code":"P-01","type":"PARQUEO","aliquot":"0.1"}
                ]}
                """;

        mockMvc.perform(post("/api/v1/condominios/{id}/unidades/lote", lasPalmas)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lote))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].type").value("PARQUEO"));
    }

    @Test
    @DisplayName("un codigo repetido sale como regla de negocio en problem+json, no como un 500")
    void elCodigoRepetidoDa422() throws Exception {
        mockMvc.perform(post("/api/v1/condominios/{id}/unidades", lasPalmas)
                .contentType(MediaType.APPLICATION_JSON)
                .content(unidad("302")));

        mockMvc.perform(post("/api/v1/condominios/{id}/unidades", lasPalmas)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unidad("302")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("REGLA_DE_NEGOCIO"));
    }

    @Test
    @DisplayName("una alicuota por encima de 100 la para la validacion, no la base")
    void laAlicuotaFueraDeRangoDa422() throws Exception {
        String imposible =
                """
                {"code":"302","type":"DEPARTAMENTO","aliquot":"150.0"}
                """;

        mockMvc.perform(post("/api/v1/condominios/{id}/unidades", lasPalmas)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(imposible))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value("aliquot"));
    }

    @Test
    @DisplayName("dar de alta en un condominio que no existe da 404")
    void enUnCondominioInexistenteDa404() throws Exception {
        mockMvc.perform(post("/api/v1/condominios/{id}/unidades", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unidad("302")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }
}
