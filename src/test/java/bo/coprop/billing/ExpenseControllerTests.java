package bo.coprop.billing;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.condominium.CondominiumType;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import bo.coprop.condominium.NewUnit;
import bo.coprop.condominium.UnitRelations;
import bo.coprop.condominium.UnitType;
import bo.coprop.condominium.Units;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
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

/**
 * Los endpoints de expensas sobre la aplicacion entera. Issue #24.
 *
 * <p>El rol es {@code FINANCE_ADMIN} porque es el que tiene los dos permisos que estas rutas piden
 * --{@code CONFIGURE_CHARGES} para el monto y {@code ISSUE_DUES} para emitir--. Que los dos esten
 * separados y que nadie mas pueda esta en {@code ApiPermissionsTests}.
 *
 * <p>El reparto y sus reglas se prueban en {@code ExpensesTests}, sin HTTP. Aqui lo que se prueba es
 * el contrato: los codigos, los cuerpos y la forma de la respuesta.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "emma", roles = "FINANCE_ADMIN")
class ExpenseControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Condominiums condominiums;

    @Autowired
    private ChargeConcepts concepts;

    @Autowired
    private Units units;

    @Autowired
    private UnitRelations relations;

    @Autowired
    private People people;

    private UUID lasPalmas;
    private UUID expensas;

    @BeforeEach
    void registerACondominiumWithOneUnit() {
        lasPalmas = condominiums
                .register(
                        new NewCondominium(
                                "Las Palmas " + UUID.randomUUID(),
                                nitNuevo(),
                                CondominiumType.BUILDING,
                                "America/La_Paz",
                                1,
                                15),
                        "emma")
                .id();
        expensas = concepts.register(
                        lasPalmas,
                        new NewChargeConcept("EXP", "Expensas ordinarias", ChargeConceptType.DUES, PaymentGroup.DUES),
                        "emma")
                .id();
        UUID unit = units.register(
                        lasPalmas,
                        new NewUnit("A-301", UnitType.APARTMENT, new BigDecimal("100.000000"), null, null, null),
                        "emma")
                .id();
        UUID person =
                people.register(new NewPerson("Ana", "Quispe", null), "emma").id();
        relations.assignBilling(lasPalmas, unit, person, null, "emma");
    }

    private static String nitNuevo() {
        return String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_0000L));
    }

    private String configuracion(String method, String amount) {
        return "{\"conceptId\":\"%s\",\"method\":\"%s\",\"amount\":\"%s\"}".formatted(expensas, method, amount);
    }

    private String mes(int year, int month) {
        return "{\"conceptId\":\"%s\",\"year\":%d,\"month\":%d}".formatted(expensas, year, month);
    }

    private void configurar(String amount) throws Exception {
        mockMvc.perform(post("/api/v1/condominiums/{id}/expense-settings", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configuracion("FIXED_PER_UNIT", amount)))
                .andExpect(status().isCreated());
    }

    private void calcular() throws Exception {
        mockMvc.perform(post("/api/v1/condominiums/{id}/expense-runs", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mes(2026, 9)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("la configuracion devuelve 201 con la moneda del condominio y sin cabecera Location")
    void theSettingReturns201() throws Exception {
        mockMvc.perform(post("/api/v1/condominiums/{id}/expense-settings", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configuracion("BY_ALIQUOT", "14700.00")))
                .andExpect(status().isCreated())
                // Sin Location a proposito: no hay ruta que consulte una configuracion por su
                // identificador, igual que en el catalogo de conceptos y en el periodo.
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                                result.getResponse().getHeader("Location"))
                        .isNull())
                .andExpect(jsonPath("$.method").value("BY_ALIQUOT"))
                .andExpect(jsonPath("$.amount").value("14700.00"))
                .andExpect(jsonPath("$.currency").value("BOB"))
                // Ni siquiera aparece: la serializacion omite los nulos, y en la vigente este lo es.
                .andExpect(jsonPath("$.validTo").doesNotExist());
    }

    @Test
    @DisplayName("configurar expensas sobre un concepto de agua responde 422")
    void aWaterConceptReturns422() throws Exception {
        UUID agua = concepts.register(
                        lasPalmas,
                        new NewChargeConcept("AGUA", "Agua", ChargeConceptType.WATER, PaymentGroup.WATER),
                        "emma")
                .id();

        mockMvc.perform(post("/api/v1/condominiums/{id}/expense-settings", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conceptId\":\"%s\",\"method\":\"FIXED_PER_UNIT\",\"amount\":\"50.00\"}"
                                .formatted(agua)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    @DisplayName("un monto de cero responde 422 con el campo señalado")
    void aNonPositiveAmountReturns422() throws Exception {
        mockMvc.perform(post("/api/v1/condominiums/{id}/expense-settings", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configuracion("FIXED_PER_UNIT", "0.00")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
    }

    @Test
    @DisplayName("el listado trae la vigente y el historial las dos")
    void theListingAndTheHistoryAnswerDifferentQuestions() throws Exception {
        configurar("300.00");
        configurar("350.00");

        mockMvc.perform(get("/api/v1/condominiums/{id}/expense-settings", lasPalmas))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount").value("350.00"));

        // El tercer criterio del ticket: el historial de vigencias se puede consultar.
        mockMvc.perform(get("/api/v1/condominiums/{id}/expense-settings/history", lasPalmas))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].amount").value("350.00"))
                .andExpect(jsonPath("$[1].amount").value("300.00"))
                .andExpect(jsonPath("$[1].validTo").value(org.hamcrest.Matchers.notNullValue()));
    }

    @Test
    @DisplayName("el calculo devuelve 200 con el reparto en borrador y el total")
    void theRunReturnsTheDraft() throws Exception {
        configurar("350.00");

        mockMvc.perform(post("/api/v1/condominiums/{id}/expense-runs", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mes(2026, 9)))
                // 200 y no 201: no es un recurso nuevo con su propia direccion, es el estado
                // calculado de un mes, y repetir la llamada da lo mismo en vez de crear otro.
                .andExpect(status().isOk())
                // El monto viaja como cadena y no como numero, que es lo que fija ARCHITECTURE.md:
                // un numero JSON en JavaScript es un double, y ahi el redondeo vuelve.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"total\":\"350.00\"")))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.year").value(2026))
                .andExpect(jsonPath("$.month").value(9))
                .andExpect(jsonPath("$.total").value("350.00"))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].unitCode").value("A-301"))
                .andExpect(jsonPath("$.lines[0].number").value("EXP-2026-09-A-301"))
                // El mes siguiente, el dia 15 de la configuracion del condominio (analisis 7.1).
                .andExpect(jsonPath("$.lines[0].dueDate").value("2026-10-15"));
    }

    @Test
    @DisplayName("la emision devuelve 200 con todo a cobro, y la segunda vez 409")
    void issuingTwiceReturns409() throws Exception {
        configurar("350.00");
        calcular();

        mockMvc.perform(post("/api/v1/condominiums/{id}/expense-runs/issue", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mes(2026, 9)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.total").value("350.00"));

        // El primer criterio del ticket, visto desde HTTP: no crea nada y lo informa.
        mockMvc.perform(post("/api/v1/condominiums/{id}/expense-runs/issue", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mes(2026, 9)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("emitir sin haber calculado responde 422")
    void issuingWithoutCalculatingReturns422() throws Exception {
        configurar("350.00");

        mockMvc.perform(post("/api/v1/condominiums/{id}/expense-runs/issue", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mes(2026, 9)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    @DisplayName("un mes fuera de 1 a 12 responde 422 con el campo señalado")
    void theMonthOutOfRangeReturns422() throws Exception {
        configurar("350.00");

        // El periodo se abre desde aqui cuando falta, asi que un mes que la base rechazaria tiene
        // que fallar antes y decir cual es el rango.
        mockMvc.perform(post("/api/v1/condominiums/{id}/expense-runs", lasPalmas)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mes(2026, 13)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.errors[0].field").value("month"));
    }

    @Test
    @DisplayName("las expensas de un condominio que no existe responden 404")
    void theExpensesOfAnUnknownCondominiumReturn404() throws Exception {
        mockMvc.perform(get("/api/v1/condominiums/{id}/expense-settings", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
