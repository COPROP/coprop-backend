package bo.coprop.billing;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.condominium.CondominiumType;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
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

/**
 * Los endpoints del catalogo de conceptos sobre la aplicacion entera.
 *
 * <p>El rol es {@code FINANCE_ADMIN} porque las dos operaciones piden {@code CONFIGURE_CHARGES}, que
 * la matriz de Seguridad 3 le da solo a el. Que el administrador del condominio no pueda esta en
 * {@code ApiPermissionsTests}, con el resto de la separacion de funciones.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "emma", roles = "FINANCE_ADMIN")
class ChargeConceptControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Condominiums condominiums;

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

    private static String concepto(String code) {
        return """
                {"code":"%s","name":"Expensas ordinarias","type":"DUES","paymentGroup":"DUES"}
                """
                .formatted(code);
    }

    @Test
    @DisplayName("el alta devuelve 201 con el concepto y sin cabecera Location")
    void theRegistrationReturns201() throws Exception {
        mockMvc.perform(post("/api/v1/condominiums/{id}/charge-concepts", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(concepto("EXP")))
                .andExpect(status().isCreated())
                // Sin Location a proposito: no hay ruta que consulte un concepto por su
                // identificador, y una cabecera que apunta a un 404 es peor que ninguna.
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                                result.getResponse().getHeader("Location"))
                        .isNull())
                .andExpect(jsonPath("$.code").value("EXP"))
                .andExpect(jsonPath("$.type").value("DUES"))
                .andExpect(jsonPath("$.paymentGroup").value("DUES"));
    }

    @Test
    @DisplayName("el mismo codigo dos veces responde 409")
    void theDuplicateCodeReturns409() throws Exception {
        mockMvc.perform(post("/api/v1/condominiums/{id}/charge-concepts", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(concepto("EXP")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/condominiums/{id}/charge-concepts", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(concepto("EXP")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("un cuerpo sin grupo de pago no pasa la validacion")
    void theBodyWithoutPaymentGroupIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/condominiums/{id}/charge-concepts", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"EXP\",\"name\":\"Expensas\",\"type\":\"DUES\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    @DisplayName("el listado devuelve el catalogo del condominio por codigo")
    void theListingReturnsTheCatalog() throws Exception {
        mockMvc.perform(post("/api/v1/condominiums/{id}/charge-concepts", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(concepto("EXP")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/condominiums/{id}/charge-concepts", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"AGUA\",\"name\":\"Agua\",\"type\":\"WATER\",\"paymentGroup\":\"WATER\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/condominiums/{id}/charge-concepts", lasPalmas))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("AGUA"))
                .andExpect(jsonPath("$[1].code").value("EXP"));
    }

    @Test
    @DisplayName("el catalogo de un condominio que no existe responde 404")
    void theCatalogOfAnUnknownCondominiumReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/condominiums/{id}/charge-concepts", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
