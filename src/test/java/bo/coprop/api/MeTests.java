package bo.coprop.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.condominium.CondominiumType;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import bo.coprop.condominium.NewUnit;
import bo.coprop.condominium.OccupancyRole;
import bo.coprop.condominium.UnitRelations;
import bo.coprop.condominium.UnitType;
import bo.coprop.condominium.Units;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.MembershipStatus;
import bo.coprop.identity.Memberships;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.identity.PersonView;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/v1/me}: los dos criterios del issue #14 y las formas en que puede filtrar.
 *
 * <p>Los criterios son dos --dos membresias se listan con su rol, y un condominio sin membresia da
 * 404--, pero lo que de verdad hay que sostener aqui es lo que <strong>no</strong> devuelve: es la
 * unica respuesta de la API que habla de varios condominios a la vez, asi que es el sitio natural
 * por donde se escaparia la lista de condominios de la plataforma. De ahi que haya mas casos de lo
 * que no sale que de lo que sale.
 */
@Import({TestcontainersConfiguration.class, TestTokenProvider.class})
@SpringBootTest
@AutoConfigureMockMvc
class MeTests {

    private static final String ME = "/api/v1/me";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Condominiums condominiums;

    @Autowired
    private Units units;

    @Autowired
    private UnitRelations relations;

    @Autowired
    private People people;

    @Autowired
    private Memberships memberships;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Con dos membresias, la respuesta lista las dos con su rol")
    void withTwoMembershipsBothAreListedWithTheirRole() throws Exception {
        UUID aurora = condominiumNamed("Aurora");
        UUID bolivar = condominiumNamed("Bolivar");
        Quien emma = cuentaNueva();
        memberships.grant(aurora, emma.account(), Set.of(MembershipRole.CONDO_ADMIN), null, "admin");
        memberships.grant(bolivar, emma.account(), Set.of(MembershipRole.OWNER, MembershipRole.BOARD), null, "admin");

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, emma.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominiums.length()").value(2))
                // Ordenados por nombre, no por el orden en que se concedieron las membresias.
                .andExpect(jsonPath("$.condominiums[0].condominium.name").value("Aurora"))
                .andExpect(jsonPath("$.condominiums[0].roles").value("CONDO_ADMIN"))
                .andExpect(jsonPath("$.condominiums[1].condominium.name").value("Bolivar"))
                .andExpect(jsonPath("$.condominiums[1].roles.length()").value(2))
                .andExpect(jsonPath("$.person.firstNames").value("Emma"))
                .andExpect(jsonPath("$.account.email").value(emma.email()))
                .andExpect(jsonPath("$.managesThePlatform").value(false));
    }

    @Test
    @DisplayName("Un request a un condominio sin membresia devuelve 404")
    void aRequestToACondominiumWithoutMembershipReturns404() throws Exception {
        UUID propio = condominiumNamed("Propio");
        UUID ajeno = condominiumNamed("Ajeno");
        Quien emma = cuentaNueva();
        memberships.grant(propio, emma.account(), Set.of(MembershipRole.CONDO_ADMIN), null, "admin");

        mockMvc.perform(get("/api/v1/condominiums/" + ajeno).header(HttpHeaders.AUTHORIZATION, emma.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // La otra mitad del criterio: que el 404 no venga de una ruta mal escrita.
        mockMvc.perform(get("/api/v1/condominiums/" + propio).header(HttpHeaders.AUTHORIZATION, emma.token()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("El perfil no nombra un condominio en el que no hay membresia")
    void theProfileDoesNotNameACondominiumWithoutMembership() throws Exception {
        UUID propio = condominiumNamed("Propio");
        condominiumNamed("Del vecino");
        Quien emma = cuentaNueva();
        memberships.grant(propio, emma.account(), Set.of(MembershipRole.OWNER), null, "admin");

        // El 404 del criterio tapa el condominio cuando se pide por su ruta. Aqui no hay ruta que
        // tapar: si este endpoint devolviera de mas, enumeraria la plataforma entera de una vez.
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, emma.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominiums.length()").value(1))
                .andExpect(jsonPath("$.condominiums[0].condominium.id").value(propio.toString()));
    }

    @Test
    @DisplayName("Una membresia suspendida no se lista")
    void aSuspendedMembershipIsNotListed() throws Exception {
        UUID condominium = condominiumNamed("Suspendido");
        Quien emma = cuentaNueva();
        UUID membership = memberships
                .grant(condominium, emma.account(), Set.of(MembershipRole.OWNER), null, "admin")
                .id();

        // Por SQL porque suspender es una operacion del issue #19 y todavia no existe. Lo que se
        // prueba no es como se suspende, sino que una suspendida no se ofrezca en el selector: el
        // filtro de aislamiento le responderia 404 a todo lo de ese condominio.
        new JdbcTemplate(dataSource)
                .update("update membership set status = ? where id = ?", MembershipStatus.SUSPENDED.name(), membership);

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, emma.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominiums").isEmpty());
    }

    @Test
    @DisplayName("Quien no tiene ninguna membresia recibe 200 con la lista vacia")
    void someoneWithNoMembershipGets200WithAnEmptyList() throws Exception {
        condominiumNamed("Ninguno mio");
        Quien justArrived = cuentaNueva();

        // Ni 404 ni 403: la pregunta es sobre uno mismo y la respuesta correcta es "existes y no
        // estas en ningun condominio". Es el caso del superadministrador que abre el arranque, que
        // es justo quien tiene que poder crear el primero.
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, justArrived.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominiums").isEmpty())
                .andExpect(jsonPath("$.person.id").value(justArrived.person().toString()));
    }

    @Test
    @DisplayName("Cada unidad aparece una vez con todos sus papeles")
    void eachUnitAppearsOnceWithAllItsRoles() throws Exception {
        UUID condominium = condominiumNamed("Con unidades");
        Quien emma = cuentaNueva();
        memberships.grant(condominium, emma.account(), Set.of(MembershipRole.OWNER), null, "admin");
        UUID suya = unit(condominium, "A-1");
        UUID queOcupa = unit(condominium, "B-2");

        // El reparto normal de un alquiler: de la suya es propietaria y responde por los cobros; la
        // otra solo la ocupa.
        // Las dos relaciones de la suya salen de una sola llamada: el alta de la titularidad abre
        // tambien la responsabilidad de pago por defecto (#29). Antes habia aqui un assignBilling.
        relations.grantOwnership(condominium, suya, emma.person(), new BigDecimal("100.00"), "admin");
        relations.grantOccupancy(condominium, queOcupa, emma.person(), OccupancyRole.TENANT, "admin");

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, emma.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominiums[0].units.length()").value(2))
                .andExpect(jsonPath("$.condominiums[0].units[0].code").value("A-1"))
                .andExpect(jsonPath("$.condominiums[0].units[0].relations.length()")
                        .value(2))
                .andExpect(jsonPath("$.condominiums[0].units[1].code").value("B-2"))
                .andExpect(jsonPath("$.condominiums[0].units[1].relations").value("OCCUPANT"));
    }

    @Test
    @DisplayName("Las unidades de un condominio no se cuelgan de otro")
    void theUnitsOfOneCondominiumDoNotHangFromAnother() throws Exception {
        UUID uno = condominiumNamed("Uno");
        UUID otro = condominiumNamed("Otro");
        Quien emma = cuentaNueva();
        memberships.grant(uno, emma.account(), Set.of(MembershipRole.OWNER), null, "admin");
        memberships.grant(otro, emma.account(), Set.of(MembershipRole.OWNER), null, "admin");
        relations.grantOwnership(uno, unit(uno, "U-1"), emma.person(), new BigDecimal("100.00"), "admin");

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, emma.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominiums[0].condominium.name").value("Otro"))
                .andExpect(jsonPath("$.condominiums[0].units").isEmpty())
                .andExpect(jsonPath("$.condominiums[1].condominium.name").value("Uno"))
                .andExpect(jsonPath("$.condominiums[1].units[0].code").value("U-1"));
    }

    @Test
    @DisplayName("El superadministrador se anuncia aparte y no como rol de un condominio")
    void thePlatformAdminIsAnnouncedApartAndNotAsACondominiumRole() throws Exception {
        UUID condominium = condominiumNamed("De la plataforma");
        Quien principal = cuentaNueva();
        new JdbcTemplate(dataSource)
                .update("insert into platform_admin (user_id, granted_at) values (?, now())", principal.account());
        memberships.grant(condominium, principal.account(), Set.of(MembershipRole.CONDO_ADMIN), null, "admin");

        // Aparte y no dentro de los roles del condominio: su alcance es la plataforma (Seguridad 3),
        // y meterlo ahi le haria creer al cliente que ese condominio se lo concedio.
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, principal.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managesThePlatform").value(true))
                .andExpect(jsonPath("$.condominiums[0].roles").value("CONDO_ADMIN"));
    }

    @Test
    @DisplayName("Sin un token valido no hay perfil")
    void withoutAValidTokenThereIsNoProfile() throws Exception {
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer no-es-un-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NOT_AUTHENTICATED"));
    }

    @Test
    @DisplayName("Un token valido de quien no tiene cuenta en COPROP no da perfil")
    void aValidTokenFromSomeoneWithoutACopropAccountGivesNoProfile() throws Exception {
        String token =
                "Bearer " + TestTokenProvider.valid(TestTokenProvider.sujetoNuevo(), TestTokenProvider.correoNuevo());

        // Lo rechaza el filtro de identidad, no este endpoint: autenticarse no reparte acceso
        // (Seguridad 10.1). Esta aqui porque es el primer sitio al que llega un cliente recien
        // logueado, y es donde se notaria si ese filtro dejara de correr.
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_ACCOUNT"));
    }

    /** Una cuenta recien abierta, con su persona y el token con el que entra. */
    private record Quien(UUID person, UUID account, String email, String token) {}

    private Quien cuentaNueva() {
        PersonView person = people.register(new NewPerson("Emma", "Tola", null), "admin");
        AccountView account = people.openAccount(person.id(), TestTokenProvider.correoNuevo(), null, "admin");
        String token = "Bearer " + TestTokenProvider.valid(TestTokenProvider.sujetoNuevo(), account.email());
        return new Quien(person.id(), account.id(), account.email(), token);
    }

    private UUID condominiumNamed(String name) {
        return condominiums
                .register(
                        new NewCondominium(name, nitNuevo(), CondominiumType.BUILDING, "America/La_Paz", 5, 10),
                        "admin")
                .id();
    }

    private UUID unit(UUID condominium, String code) {
        return units.register(
                        condominium,
                        new NewUnit(code, UnitType.APARTMENT, new BigDecimal("1.000000"), null, null, null),
                        "admin")
                .id();
    }

    private static String nitNuevo() {
        return String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_0000L));
    }
}
