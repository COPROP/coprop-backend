package bo.coprop.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import bo.coprop.identity.AccountView;
import bo.coprop.identity.DoblesDeInvitacion;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.MembershipView;
import bo.coprop.identity.Memberships;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.identity.PersonView;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Los dos criterios de aceptacion del issue <strong>#19</strong>, sobre HTTP y con el mismo token.
 *
 * <p>El primero es el que no se puede probar en un test de modulo: <em>"tras la baja, el usuario
 * autentica pero no ve el condominio"</em>. Son dos afirmaciones y la fuerza esta en que valgan las
 * dos a la vez: la identidad sigue sirviendo --{@code GET /me} responde 200-- y el condominio ha
 * desaparecido para esa persona. Con el <strong>mismo token</strong> antes y despues, que es lo que
 * demuestra que el corte no espera a que nada caduque: el filtro de condominio relee la membresia en
 * cada peticion.
 *
 * <p>El segundo es que lo historico se conserve y lo pueda consultar el administrador. Aqui se
 * comprueba con lo que existe en M1 --la membresia cerrada y la titularidad del vendedor--, porque
 * las obligaciones y los pagos que el criterio nombra son de M2 y M3. Es la misma acotacion con la
 * que se cerro el #15: el criterio se cumple en sustancia y sobre HTTP, sobre lo que hay.
 *
 * <p>No va en una transaccion: los filtros de la cadena de seguridad escriben por su cuenta y un
 * rollback del test no los alcanza. De ahi que cada caso use su propio condominio y su propia
 * cuenta.
 */
@Import({TestcontainersConfiguration.class, ProveedorDeTokensDePrueba.class, DoblesDeInvitacion.class})
@SpringBootTest
@AutoConfigureMockMvc
class BajaDeAccesoTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Condominiums condominios;

    @Autowired
    private Units unidades;

    @Autowired
    private People personas;

    @Autowired
    private Memberships membresias;

    @Autowired
    private UnitRelations relaciones;

    @Test
    @DisplayName("tras la baja, el usuario autentica pero no ve el condominio")
    void trasLaBajaAutenticaPeroNoVeElCondominio() throws Exception {
        UUID condominio = altaDeCondominio();
        AccountView cuenta = cuentaNueva();
        MembershipView membresia =
                membresias.grant(condominio, cuenta.id(), Set.of(MembershipRole.OWNER), null, "admin");
        String token = tokenDe(cuenta);

        // Antes: el condominio existe para esta persona y sale en su perfil.
        mockMvc.perform(get("/api/v1/condominios/{id}", condominio).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominios.length()").value(1));

        membresias.revoke(condominio, membresia.id(), "vendio su departamento", "admin");

        // Despues, con el MISMO token: sigue autenticando.
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cuenta.id").value(cuenta.id().toString()))
                // Y no le queda ningun condominio que ofrecerle en el selector.
                .andExpect(jsonPath("$.condominios.length()").value(0));

        // Pero el condominio ya no existe para ella. 404 y no 403: un 403 confirmaria que esta ahi.
        mockMvc.perform(get("/api/v1/condominios/{id}", condominio).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("una suspension deja a la persona fuera igual que una baja, y la reactivacion la devuelve")
    void laSuspensionDejaFueraYLaReactivacionDevuelve() throws Exception {
        UUID condominio = altaDeCondominio();
        AccountView cuenta = cuentaNueva();
        MembershipView membresia =
                membresias.grant(condominio, cuenta.id(), Set.of(MembershipRole.OWNER), null, "admin");
        String token = tokenDe(cuenta);

        membresias.suspend(condominio, membresia.id(), "cuota impaga", "admin");

        mockMvc.perform(get("/api/v1/condominios/{id}", condominio).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
        // El perfil no lo lista: ofrecerlo en el selector seria ofrecer una puerta cerrada.
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominios.length()").value(0));

        membresias.reinstate(condominio, membresia.id(), "pago al dia", "admin");

        // Y vuelve en la siguiente peticion, sin tener que iniciar sesion de nuevo: los roles nunca
        // estuvieron en el token.
        mockMvc.perform(get("/api/v1/condominios/{id}", condominio).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.condominios.length()").value(1));
    }

    @Test
    @DisplayName("tras la baja, el administrador sigue viendo la membresia cerrada y a quien vendio")
    void elAdministradorSigueViendoLoHistorico() throws Exception {
        UUID condominio = altaDeCondominio();
        UUID unidad = altaDeUnidad(condominio, "BAJA-101");

        AccountView admin = cuentaNueva();
        membresias.grant(condominio, admin.id(), Set.of(MembershipRole.CONDO_ADMIN), null, "admin");
        String token = tokenDe(admin);

        AccountView quienSeVa = cuentaNueva();
        PersonView vendedor = personas.find(quienSeVa.personId());
        MembershipView suya = membresias.grant(condominio, quienSeVa.id(), Set.of(MembershipRole.OWNER), null, "admin");
        relaciones.grantOwnership(condominio, unidad, vendedor.id(), new BigDecimal("100.000000"), "admin");

        PersonView comprador = personas.register(new NewPerson("Quien", "Compra", null), "admin");
        relaciones.transferOwnership(condominio, unidad, vendedor.id(), comprador.id(), "admin");
        membresias.revoke(condominio, suya.id(), "vendio su departamento", "admin");

        // El padron de hoy ya no la trae...
        mockMvc.perform(get("/api/v1/condominios/{id}/membresias", condominio).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + suya.id() + "')]").isEmpty());

        // ...pero el padron completo si, con su fecha de cierre.
        mockMvc.perform(get("/api/v1/condominios/{id}/membresias?cerradas=true", condominio)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + suya.id() + "')].validTo").isNotEmpty());

        // Y el historico de la unidad conserva al vendedor, que es con lo que el administrador le da
        // el comprobante de su epoca que pide Seguridad 6.2.
        mockMvc.perform(get("/api/v1/condominios/{id}/unidades/{unidad}/propietarios/historico", condominio, unidad)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.personId=='" + vendedor.id() + "')].validTo")
                        .isNotEmpty())
                .andExpect(jsonPath("$[?(@.personId=='" + comprador.id() + "')].validTo")
                        .isEmpty());

        // El listado de hoy trae solo al comprador.
        mockMvc.perform(get("/api/v1/condominios/{id}/unidades/{unidad}/propietarios", condominio, unidad)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].personId").value(comprador.id().toString()));
    }

    private UUID altaDeCondominio() {
        return condominios
                .register(
                        new NewCondominium(
                                "Baja " + UUID.randomUUID(),
                                nitNuevo(),
                                CondominiumType.BUILDING,
                                "America/La_Paz",
                                5,
                                10),
                        "admin")
                .id();
    }

    /** El NIT es unico por condominio, y estos tests comparten base con los demas. */
    private static String nitNuevo() {
        return String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_0000L));
    }

    private UUID altaDeUnidad(UUID condominio, String codigo) {
        return unidades.register(
                        condominio,
                        new NewUnit(codigo, UnitType.APARTMENT, new BigDecimal("1.000000"), null, null, null),
                        "admin")
                .id();
    }

    private AccountView cuentaNueva() {
        PersonView persona = personas.register(new NewPerson("Emma", "Tola", null), "admin");
        return personas.openAccount(persona.id(), ProveedorDeTokensDePrueba.correoNuevo(), null, "admin");
    }

    private static String tokenDe(AccountView cuenta) {
        return "Bearer " + ProveedorDeTokensDePrueba.valido(ProveedorDeTokensDePrueba.sujetoNuevo(), cuenta.email());
    }
}
