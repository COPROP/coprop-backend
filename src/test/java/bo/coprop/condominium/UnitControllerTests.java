package bo.coprop.condominium;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import java.math.BigDecimal;
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
@WithMockUser(username = "emma", roles = "CONDO_ADMIN")
class UnitControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Condominiums condominiums;

    @Autowired
    private Units units;

    private UUID lasPalmas;

    @BeforeEach
    void registerACondominium() {
        lasPalmas = condominiums
                .register(
                        new NewCondominium(
                                "Las Palmas", "1234567890", CondominiumType.BUILDING, "America/La_Paz", 1, 15),
                        "emma")
                .id();
    }

    private String unit(String code) {
        return """
                {"code":"%s","type":"APARTMENT","aliquot":"1.25","areaM2":"82.50"}
                """
                .formatted(code);
    }

    @Test
    @DisplayName("el alta de una unidad devuelve 201 con su identificador")
    void theRegistrationReturns201() throws Exception {
        mockMvc.perform(post("/api/v1/condominiums/{id}/units", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unit("302")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("302"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("el alta masiva crea el lote entero en una llamada")
    void theBatchRegistrationCreatesTheBatch() throws Exception {
        String batch =
                """
                {"units":[
                  {"code":"301","type":"APARTMENT","aliquot":"1.25"},
                  {"code":"302","type":"APARTMENT","aliquot":"1.25"},
                  {"code":"P-01","type":"PARKING","aliquot":"0.1"}
                ]}
                """;

        mockMvc.perform(post("/api/v1/condominiums/{id}/units/batch", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].type").value("PARKING"));
    }

    @Test
    @DisplayName("un codigo repetido sale como regla de negocio en problem+json, no como un 500")
    void aRepeatedCodeGives422() throws Exception {
        mockMvc.perform(post("/api/v1/condominiums/{id}/units", lasPalmas)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(unit("302")));

        mockMvc.perform(post("/api/v1/condominiums/{id}/units", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unit("302")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    @DisplayName("una alicuota por encima de 100 la para la validacion, no la base")
    void anAliquotOutOfRangeGives422() throws Exception {
        String imposible = """
                {"code":"302","type":"APARTMENT","aliquot":"150.0"}
                """;

        mockMvc.perform(post("/api/v1/condominiums/{id}/units", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(imposible))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.errors[0].field").value("aliquot"));
    }

    @Test
    @DisplayName("dar de alta en un condominio que no existe da 404")
    void inANonExistentCondominiumItGives404() throws Exception {
        mockMvc.perform(post("/api/v1/condominiums/{id}/units", UUID.randomUUID())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unit("302")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("una unidad se consulta por la ruta de su condominio")
    void theUnitIsQueriedByItsRoute() throws Exception {
        UnitView propia = units.register(lasPalmas, body("302"), "emma");

        mockMvc.perform(get("/api/v1/condominiums/{id}/units/{unitId}", lasPalmas, propia.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("302"));
    }

    @Test
    @DisplayName("la unidad de otro condominio da 404 desde la ruta de este, no la devuelve")
    void theOtherCondominiumsUnitGives404() throws Exception {
        UUID villaSol = condominiums
                .register(
                        new NewCondominium("Villa Sol", "9999999999", CondominiumType.HOUSES, "America/La_Paz", 1, 15),
                        "emma")
                .id();
        UnitView ajena = units.register(villaSol, body("302"), "emma");

        // El condominio de la ruta no es decorativo: 404 y no la unidad de al lado.
        mockMvc.perform(get("/api/v1/condominiums/{id}/units/{unitId}", lasPalmas, ajena.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private static NewUnit body(String code) {
        return new NewUnit(code, UnitType.APARTMENT, new BigDecimal("1.25"), null, null, null);
    }
}
