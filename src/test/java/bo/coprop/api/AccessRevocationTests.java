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
import bo.coprop.identity.InvitationDoubles;
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
@Import({TestcontainersConfiguration.class, TestTokenProvider.class, InvitationDoubles.class})
@SpringBootTest
@AutoConfigureMockMvc
class AccessRevocationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Condominiums condominiums;

    @Autowired
    private Units units;

    @Autowired
    private People people;

    @Autowired
    private Memberships memberships;

    @Autowired
    private UnitRelations relaciones;

    @Test
    @DisplayName("tras la baja, el usuario autentica pero no ve el condominio")
    void trasLaBajaAutenticaPeroNoVeElCondominio() throws Exception {
        UUID condominium = altaDeCondominio();
        AccountView account = cuentaNueva();
        MembershipView membership =
                memberships.grant(condominium, account.id(), Set.of(MembershipRole.OWNER), null, "admin");
        String token = tokenDe(account);

        // Antes: el condominio existe para esta persona y sale en su perfil.
        mockMvc.perform(get("/api/v1/condominios/{id}", condominium).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominiums.length()").value(1));

        memberships.revoke(condominium, membership.id(), "vendio su departamento", "admin");

        // Despues, con el MISMO token: sigue autenticando.
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.id").value(account.id().toString()))
                // Y no le queda ningun condominio que ofrecerle en el selector.
                .andExpect(jsonPath("$.condominiums.length()").value(0));

        // Pero el condominio ya no existe para ella. 404 y no 403: un 403 confirmaria que esta ahi.
        mockMvc.perform(get("/api/v1/condominios/{id}", condominium).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("una suspension deja a la persona fuera igual que una baja, y la reactivacion la devuelve")
    void laSuspensionDejaFueraYLaReactivacionDevuelve() throws Exception {
        UUID condominium = altaDeCondominio();
        AccountView account = cuentaNueva();
        MembershipView membership =
                memberships.grant(condominium, account.id(), Set.of(MembershipRole.OWNER), null, "admin");
        String token = tokenDe(account);

        memberships.suspend(condominium, membership.id(), "cuota impaga", "admin");

        mockMvc.perform(get("/api/v1/condominios/{id}", condominium).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
        // El perfil no lo lista: ofrecerlo en el selector seria ofrecer una puerta cerrada.
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominiums.length()").value(0));

        memberships.reinstate(condominium, membership.id(), "pago al dia", "admin");

        // Y vuelve en la siguiente peticion, sin tener que iniciar sesion de nuevo: los roles nunca
        // estuvieron en el token.
        mockMvc.perform(get("/api/v1/condominios/{id}", condominium).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.condominiums.length()").value(1));
    }

    @Test
    @DisplayName("tras la baja, el administrador sigue viendo la membresia cerrada y a quien vendio")
    void elAdministradorSigueViendoLoHistorico() throws Exception {
        UUID condominium = altaDeCondominio();
        UUID unit = altaDeUnidad(condominium, "BAJA-101");

        AccountView admin = cuentaNueva();
        memberships.grant(condominium, admin.id(), Set.of(MembershipRole.CONDO_ADMIN), null, "admin");
        String token = tokenDe(admin);

        AccountView quienSeVa = cuentaNueva();
        PersonView vendedor = people.find(quienSeVa.personId());
        MembershipView suya =
                memberships.grant(condominium, quienSeVa.id(), Set.of(MembershipRole.OWNER), null, "admin");
        relaciones.grantOwnership(condominium, unit, vendedor.id(), new BigDecimal("100.000000"), "admin");

        PersonView comprador = people.register(new NewPerson("Quien", "Compra", null), "admin");
        relaciones.transferOwnership(condominium, unit, vendedor.id(), comprador.id(), "admin");
        memberships.revoke(condominium, suya.id(), "vendio su departamento", "admin");

        // El padron de hoy ya no la trae...
        mockMvc.perform(get("/api/v1/condominios/{id}/membresias", condominium)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + suya.id() + "')]").isEmpty());

        // ...pero el padron completo si, con su fecha de cierre.
        mockMvc.perform(get("/api/v1/condominios/{id}/membresias?cerradas=true", condominium)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + suya.id() + "')].validTo").isNotEmpty());

        // Y el historico de la unidad conserva al vendedor, que es con lo que el administrador le da
        // el comprobante de su epoca que pide Seguridad 6.2.
        mockMvc.perform(get("/api/v1/condominios/{id}/unidades/{unidad}/propietarios/historico", condominium, unit)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.personId=='" + vendedor.id() + "')].validTo")
                        .isNotEmpty())
                .andExpect(jsonPath("$[?(@.personId=='" + comprador.id() + "')].validTo")
                        .isEmpty());

        // El listado de hoy trae solo al comprador.
        mockMvc.perform(get("/api/v1/condominios/{id}/unidades/{unidad}/propietarios", condominium, unit)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].personId").value(comprador.id().toString()));
    }

    private UUID altaDeCondominio() {
        return condominiums
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

    private UUID altaDeUnidad(UUID condominium, String code) {
        return units.register(
                        condominium,
                        new NewUnit(code, UnitType.APARTMENT, new BigDecimal("1.000000"), null, null, null),
                        "admin")
                .id();
    }

    private AccountView cuentaNueva() {
        PersonView person = people.register(new NewPerson("Emma", "Tola", null), "admin");
        return people.openAccount(person.id(), TestTokenProvider.correoNuevo(), null, "admin");
    }

    private static String tokenDe(AccountView account) {
        return "Bearer " + TestTokenProvider.valido(TestTokenProvider.sujetoNuevo(), account.email());
    }
}
