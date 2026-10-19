package bo.coprop.condominium;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import java.math.BigDecimal;
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
 * Los endpoints de las tres relaciones de una unidad.
 *
 * <p>Aqui si se usa {@code People} para crear la persona: esto arranca la aplicacion entera, no un
 * modulo, asi que no hay limite que respetar. El test de modulo, que si lo tiene, la inserta con
 * SQL.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UnitRelationsControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Condominiums condominiums;

    @Autowired
    private Units units;

    @Autowired
    private People people;

    @Test
    @WithMockUser(username = "emma", roles = "CONDO_ADMIN")
    @DisplayName("registrar un propietario devuelve 201 con su porcentaje")
    void registrarPropietarioDevuelve201() throws Exception {
        UUID condominium = condominium("4000000001");
        UUID unit = unit(condominium, "302");
        UUID person = person("5000001");

        mockMvc.perform(post(ruta(condominium, unit) + "/propietarios")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"%s\",\"share\":12.5}".formatted(person)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unitId").value(unit.toString()))
                .andExpect(jsonPath("$.personId").value(person.toString()))
                .andExpect(jsonPath("$.share").value(12.5));
    }

    @Test
    @WithMockUser(username = "emma", roles = "CONDO_ADMIN")
    @DisplayName("los propietarios de una unidad se listan con su porcentaje")
    void losPropietariosSeListan() throws Exception {
        UUID condominium = condominium("4000000002");
        UUID unit = unit(condominium, "101");
        UUID una = person("5000002");
        UUID otra = person("5000003");
        registerOwner(condominium, unit, una, "50.0");
        registerOwner(condominium, unit, otra, "50.0");

        mockMvc.perform(get(ruta(condominium, unit) + "/propietarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @WithMockUser(username = "emma", roles = "CONDO_ADMIN")
    @DisplayName("la unidad de otro condominio da 404, no 403")
    void laUnidadAjenaDa404() throws Exception {
        UUID propio = condominium("4000000003");
        UUID ajeno = condominium("4000000004");
        UUID unidadAjena = unit(ajeno, "77");
        UUID person = person("5000004");

        mockMvc.perform(post(ruta(propio, unidadAjena) + "/propietarios")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"%s\",\"share\":10.0}".formatted(person)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(username = "emma", roles = "CONDO_ADMIN")
    @DisplayName("un porcentaje por encima de 100 da 422 con el campo senalado")
    void unPorcentajeImposibleDa422() throws Exception {
        UUID condominium = condominium("4000000005");
        UUID unit = unit(condominium, "201");
        UUID person = person("5000005");

        mockMvc.perform(post(ruta(condominium, unit) + "/propietarios")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"%s\",\"share\":150.0}".formatted(person)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].field").value("share"));
    }

    @Test
    @WithMockUser(username = "emma", roles = "CONDO_ADMIN")
    @DisplayName("el inquilino ocupa y el propietario responde por el pago, en dos llamadas")
    void elInquilinoOcupaYElPropietarioPaga() throws Exception {
        UUID condominium = condominium("4000000006");
        UUID unit = unit(condominium, "501");
        UUID propietaria = person("5000006");
        UUID inquilino = person("5000007");
        registerOwner(condominium, unit, propietaria, "100.0");

        mockMvc.perform(post(ruta(condominium, unit) + "/ocupantes")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"%s\",\"role\":\"TENANT\"}".formatted(inquilino)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("TENANT"));

        mockMvc.perform(post(ruta(condominium, unit) + "/responsables-de-pago")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"%s\"}".formatted(propietaria)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.personId").value(propietaria.toString()))
                .andExpect(jsonPath("$.conceptId").doesNotExist());
    }

    @Test
    @WithMockUser(username = "emma", roles = "CONDO_ADMIN")
    @DisplayName("repetir la titularidad da 409 o 422 segun la regla, nunca un 500")
    void repetirLaTitularidadNoDa500() throws Exception {
        UUID condominium = condominium("4000000007");
        UUID unit = unit(condominium, "601");
        UUID person = person("5000008");
        registerOwner(condominium, unit, person, "50.0");

        mockMvc.perform(post(ruta(condominium, unit) + "/propietarios")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"%s\",\"share\":50.0}".formatted(person)))
                .andExpect(jsonPath("$.codigo").value("REGLA_DE_NEGOCIO"));
    }

    @Test
    @DisplayName("sin autenticar no se registran relaciones")
    void sinAutenticarNoSeRegistra() throws Exception {
        mockMvc.perform(post(ruta(UUID.randomUUID(), UUID.randomUUID()) + "/propietarios")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"%s\",\"share\":10.0}".formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }

    private static String ruta(UUID condominiumId, UUID unitId) {
        return "/api/v1/condominios/" + condominiumId + "/unidades/" + unitId;
    }

    private void registerOwner(UUID condominiumId, UUID unitId, UUID personId, String share) throws Exception {
        mockMvc.perform(post(ruta(condominiumId, unitId) + "/propietarios")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"%s\",\"share\":%s}".formatted(personId, share)))
                .andExpect(status().isCreated());
    }

    private UUID condominium(String nit) {
        return condominiums
                .register(
                        new NewCondominium("Condominio", nit, CondominiumType.BUILDING, "America/La_Paz", 1, 15),
                        "admin")
                .id();
    }

    private UUID unit(UUID condominiumId, String code) {
        return units.register(
                        condominiumId,
                        new NewUnit(code, UnitType.APARTMENT, new BigDecimal("1.000000"), null, null, null),
                        "admin")
                .id();
    }

    private UUID person(String carnet) {
        return people.register(new NewPerson("Emma", "Tola", carnet), "admin").id();
    }
}
