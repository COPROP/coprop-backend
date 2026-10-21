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
 * Los endpoints del periodo de facturacion sobre la aplicacion entera.
 *
 * <p>El rol es {@code FINANCE_ADMIN} porque las dos operaciones piden {@code CONFIGURE_CHARGES}. Que
 * el administrador del condominio no pueda esta en {@code ApiPermissionsTests}, con el resto de la
 * separacion de funciones.
 *
 * <p>Las transiciones no se prueban aqui porque no tienen ruta: viven en {@code BillingPeriods} y las
 * llamara el #24 al emitir. Sus casos estan en {@code BillingPeriodsTests}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "emma", roles = "FINANCE_ADMIN")
class BillingPeriodControllerTests {

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

    private static String periodo(int year, int month) {
        return "{\"year\":%d,\"month\":%d}".formatted(year, month);
    }

    @Test
    @DisplayName("la apertura devuelve 201 con el periodo en DRAFT y sin cabecera Location")
    void theOpeningReturns201() throws Exception {
        mockMvc.perform(post("/api/v1/condominiums/{id}/billing-periods", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(periodo(2026, 10)))
                .andExpect(status().isCreated())
                // Sin Location a proposito: no hay ruta que consulte un periodo por su
                // identificador, igual que en el catalogo de conceptos.
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                                result.getResponse().getHeader("Location"))
                        .isNull())
                .andExpect(jsonPath("$.year").value(2026))
                .andExpect(jsonPath("$.month").value(10))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    @DisplayName("el mismo mes dos veces responde 409")
    void theDuplicateMonthReturns409() throws Exception {
        mockMvc.perform(post("/api/v1/condominiums/{id}/billing-periods", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(periodo(2026, 10)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/condominiums/{id}/billing-periods", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(periodo(2026, 10)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("un mes fuera de 1 a 12 responde 422 con el campo señalado")
    void theMonthOutOfRangeReturns422() throws Exception {
        mockMvc.perform(post("/api/v1/condominiums/{id}/billing-periods", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(periodo(2026, 13)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.errors[0].field").value("month"));
    }

    @Test
    @DisplayName("el listado devuelve los periodos del mas reciente al mas antiguo")
    void theListingReturnsThePeriodsNewestFirst() throws Exception {
        mockMvc.perform(post("/api/v1/condominiums/{id}/billing-periods", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(periodo(2026, 9)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/condominiums/{id}/billing-periods", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(periodo(2026, 10)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/condominiums/{id}/billing-periods", lasPalmas))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].month").value(10))
                .andExpect(jsonPath("$[1].month").value(9));
    }

    @Test
    @DisplayName("los periodos de un condominio que no existe responden 404")
    void thePeriodsOfAnUnknownCondominiumReturn404() throws Exception {
        mockMvc.perform(get("/api/v1/condominiums/{id}/billing-periods", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
