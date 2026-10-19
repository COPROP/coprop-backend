package bo.coprop.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.condominium.CondominiumType;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.Memberships;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.identity.PersonView;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

/**
 * La matriz del issue #16, pero vista desde HTTP: que un rol que no puede, no pueda.
 *
 * <p>{@code PermissionMatrixTests} prueba la decision --quien puede que-- sin levantar nada. Esto
 * prueba lo otro: que cada endpoint pregunta por el permiso que le toca. Son dos cosas distintas y
 * se rompen por separado; una matriz impecable no sirve de nada si un controlador se olvido de
 * mirarla.
 *
 * <p>Las anotaciones {@code @PreAuthorize} son <strong>cadenas</strong>: el compilador no las
 * revisa. Un permiso mal escrito o un bean renombrado no rompen el build, revientan en ejecucion.
 * Por eso cada endpoint protegido tiene aqui su caso.
 */
@Import({TestcontainersConfiguration.class, TestTokenProvider.class})
@SpringBootTest
@AutoConfigureMockMvc
class ApiPermissionsTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Condominiums condominiums;

    @Autowired
    private People people;

    @Autowired
    private Memberships memberships;

    @Autowired
    private DataSource dataSource;

    private UUID condominioNuevo() {
        return condominiums
                .register(
                        new NewCondominium(
                                "Torre " + UUID.randomUUID(),
                                nitNuevo(),
                                CondominiumType.BUILDING,
                                "America/La_Paz",
                                5,
                                10),
                        "admin")
                .id();
    }

    private static String nitNuevo() {
        return String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_0000L));
    }

    private AccountView cuentaNueva() {
        PersonView person = people.register(new NewPerson("Emma", "Tola", null), "admin");
        return people.openAccount(person.id(), TestTokenProvider.correoNuevo(), null, "admin");
    }

    /** Una cuenta con esos roles en ese condominio, y el token con el que entra. */
    private String tokenDe(UUID condominium, MembershipRole... roles) {
        AccountView account = cuentaNueva();
        memberships.grant(condominium, account.id(), Set.of(roles), null, "admin");
        return "Bearer " + TestTokenProvider.valid(TestTokenProvider.sujetoNuevo(), account.email());
    }

    /**
     * Una cuenta con alcance de plataforma, y el token con el que entra.
     *
     * <p>Se concede por SQL porque usar la operacion del issue #20 exigiria ya tener un
     * superadministrador con el que autenticarse: el primero de todos no lo concede nadie, lo abre
     * el arranque, y eso tiene su propio test. La operacion tiene los suyos en
     * {@code PlatformAdminsTests} y {@code AdminRolesTests}.
     */
    private String tokenDeSuperadministrador() {
        AccountView account = cuentaNueva();
        new JdbcTemplate(dataSource)
                .update("insert into platform_admin (user_id, granted_at) values (?, now())", account.id());
        return "Bearer " + TestTokenProvider.valid(TestTokenProvider.sujetoNuevo(), account.email());
    }

    private static final String UNIDAD = "{\"code\":\"A-1\",\"type\":\"APARTMENT\",\"aliquot\":\"1.000000\"}";

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizedRequest(
            HttpMethod metodo, String path, String authorizationHeader, String cuerpo) {
        var constructor = request(metodo, path)
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader);
        if (cuerpo != null) {
            constructor.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        }
        return constructor;
    }

    @Test
    @DisplayName("el propietario no da de alta unidades: eso es del administrador del condominio")
    void theOwnerDoesNotRegisterUnits() throws Exception {
        UUID condominium = condominioNuevo();

        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST,
                        "/api/v1/condominiums/" + condominium + "/units",
                        tokenDe(condominium, MembershipRole.OWNER),
                        UNIDAD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_PERMISSION"));
    }

    @Test
    @DisplayName("el administrador del condominio si da de alta unidades")
    void theAdminDoesRegisterUnits() throws Exception {
        UUID condominium = condominioNuevo();

        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST,
                        "/api/v1/condominiums/" + condominium + "/units",
                        tokenDe(condominium, MembershipRole.CONDO_ADMIN),
                        UNIDAD))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("el administrador del condominio no toca lo que se cobra: separacion de funciones")
    void theAdminDoesNotTouchWhatIsCharged() throws Exception {
        UUID condominium = condominioNuevo();

        mockMvc.perform(authorizedRequest(
                        HttpMethod.PUT,
                        "/api/v1/condominiums/" + condominium + "/configuration",
                        tokenDe(condominium, MembershipRole.CONDO_ADMIN),
                        "{\"issueDay\":7,\"dueDay\":15}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_PERMISSION"));

        mockMvc.perform(authorizedRequest(
                        HttpMethod.PUT,
                        "/api/v1/condominiums/" + condominium + "/configuration",
                        tokenDe(condominium, MembershipRole.FINANCE_ADMIN),
                        "{\"issueDay\":7,\"dueDay\":15}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("el financiero no reparte roles: la otra mitad de la separacion de funciones")
    void theFinanceAdminDoesNotHandOutRoles() throws Exception {
        UUID condominium = condominioNuevo();
        AccountView invited = cuentaNueva();

        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST,
                        "/api/v1/condominiums/" + condominium + "/memberships",
                        tokenDe(condominium, MembershipRole.FINANCE_ADMIN),
                        "{\"accountId\":\"" + invited.id() + "\",\"roles\":[\"OWNER\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_PERMISSION"));
    }

    @Test
    @DisplayName("crear condominios es del superadministrador, y de nadie mas")
    void creatingCondominiumsBelongsToThePlatformAdmin() throws Exception {
        UUID condominium = condominioNuevo();
        String alta = "{\"name\":\"Torre body\",\"nit\":\"" + nitNuevo()
                + "\",\"type\":\"BUILDING\",\"timeZone\":\"America/La_Paz\",\"issueDay\":5,\"dueDay\":10}";

        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST,
                        "/api/v1/condominiums",
                        tokenDe(condominium, MembershipRole.CONDO_ADMIN),
                        alta))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_PERMISSION"));

        mockMvc.perform(authorizedRequest(HttpMethod.POST, "/api/v1/condominiums", tokenDeSuperadministrador(), alta))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("el superadministrador pone el primer administrador de un condominio donde no es miembro")
    void thePlatformAdminAppointsTheFirstAdmin() throws Exception {
        // Es el arranque de un condominio, y la razon de que el filtro del #17 lo deje pasar sin
        // membresia: sin esto, un condominio recien creado no tendria forma de tener a nadie dentro.
        UUID condominium = condominioNuevo();
        AccountView first = cuentaNueva();

        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST,
                        "/api/v1/condominiums/" + condominium + "/memberships",
                        tokenDeSuperadministrador(),
                        "{\"accountId\":\"" + first.id() + "\",\"roles\":[\"CONDO_ADMIN\"]}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("el superadministrador solo reparte roles administrativos")
    void thePlatformAdminHandsOutOnlyAdminRoles() throws Exception {
        // La celda dice "Solo administradores". Que pueda poner un CONDO_ADMIN no lo convierte en
        // alguien que reparte propietarios: eso es operar el condominio, y 10.1 dice que no opera.
        UUID condominium = condominioNuevo();
        AccountView alguien = cuentaNueva();

        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST,
                        "/api/v1/condominiums/" + condominium + "/memberships",
                        tokenDeSuperadministrador(),
                        "{\"accountId\":\"" + alguien.id() + "\",\"roles\":[\"OWNER\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_PERMISSION"));
    }

    @Test
    @DisplayName("el administrador del condominio si puede conceder los dos roles administrativos")
    void theAdminGrantsBothAdminRoles() throws Exception {
        // Decision explicita de Seguridad 10.1: en condominios pequenos una sola persona hace las
        // dos cosas. Lo que no puede es pasar desapercibido, y de eso se encarga la auditoria.
        UUID condominium = condominioNuevo();
        AccountView invited = cuentaNueva();

        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST,
                        "/api/v1/condominiums/" + condominium + "/memberships",
                        tokenDe(condominium, MembershipRole.CONDO_ADMIN),
                        "{\"accountId\":\"" + invited.id() + "\",\"roles\":[\"CONDO_ADMIN\",\"FINANCE_ADMIN\"]}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PLATFORM_ADMIN no se concede como membresia de un condominio")
    void thePlatformRoleIsNotAMembership() throws Exception {
        // Su alcance es la plataforma y vive en su propia tabla. Concederlo aqui daria un
        // "superadministrador de un condominio", que no significa nada, y dejaria dos sitios donde
        // mirar quien administra la plataforma.
        UUID condominium = condominioNuevo();
        AccountView alguien = cuentaNueva();

        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST,
                        "/api/v1/condominiums/" + condominium + "/memberships",
                        tokenDeSuperadministrador(),
                        "{\"accountId\":\"" + alguien.id() + "\",\"roles\":[\"PLATFORM_ADMIN\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    @DisplayName("el superadministrador que ademas es miembro conserva los dos juegos de roles")
    void aPlatformAdminWhoIsAlsoAMemberKeepsBoth() throws Exception {
        // Es como arranca un condominio de verdad: el superadministrador lo crea y se pone de
        // primer administrador hasta que haya a quien invitar. Si al entrar perdiera los roles de
        // su membresia --porque el filtro se conformara con que es de plataforma-- no podria
        // administrar el condominio que acaba de abrir, y el fallo saldria como un 403 raro.
        UUID condominium = condominioNuevo();
        AccountView account = cuentaNueva();
        new JdbcTemplate(dataSource)
                .update("insert into platform_admin (user_id, granted_at) values (?, now())", account.id());
        memberships.grant(condominium, account.id(), Set.of(MembershipRole.CONDO_ADMIN), null, "admin");
        String token = "Bearer " + TestTokenProvider.valid(TestTokenProvider.sujetoNuevo(), account.email());

        // De la membresia.
        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST, "/api/v1/condominiums/" + condominium + "/units", token, UNIDAD))
                .andExpect(status().isCreated());

        // De la plataforma, en la misma sesion.
        mockMvc.perform(
                        authorizedRequest(
                                HttpMethod.POST,
                                "/api/v1/condominiums",
                                token,
                                "{\"name\":\"Otra torre\",\"nit\":\"" + nitNuevo()
                                        + "\",\"type\":\"BUILDING\",\"timeZone\":\"America/La_Paz\",\"issueDay\":5,\"dueDay\":10}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("dar de alta una persona pide administrar algun condominio")
    void registeringAPersonRequiresManagingSomeCondominium() throws Exception {
        UUID condominium = condominioNuevo();
        String person = "{\"firstNames\":\"Ana\",\"lastNames\":\"Perez\"}";

        // La ruta es global --la persona no cuelga de un condominio-- pero quien la da de alta lo
        // hace para su edificio. Un propietario no administra ninguno.
        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST, "/api/v1/people", tokenDe(condominium, MembershipRole.OWNER), person))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_PERMISSION"));

        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST, "/api/v1/people", tokenDe(condominium, MembershipRole.CONDO_ADMIN), person))
                .andExpect(status().isCreated());
    }
}
