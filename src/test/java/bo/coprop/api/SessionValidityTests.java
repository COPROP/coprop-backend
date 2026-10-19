package bo.coprop.api;

import static org.assertj.core.api.Assertions.assertThat;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.condominium.CondominiumType;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.InvitationDoubles;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.Memberships;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.identity.PersonView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * El primer criterio del issue <strong>#22</strong>: un administrador inactivo una hora queda
 * fuera, y un propietario no.
 *
 * <p>Son <strong>dos mitades y hacen falta las dos</strong>, porque el criterio se rompe por
 * separado en cada una:
 *
 * <ul>
 *   <li>Que el filtro le ponga a la sesion el tiempo que le toca segun los roles de la cuenta. Es
 *       lo unico que esta aplicacion decide, y lo que no se puede poner en un archivo de
 *       configuracion.
 *   <li>Que una sesion con ese tiempo <strong>de verdad caduque</strong>, contra Spring Session y
 *       la tabla de la V6. Sin esta mitad, el filtro podria estar escribiendo un numero que nadie
 *       mira y la suite pasaria igual.
 * </ul>
 *
 * <p>El reloj se mueve en vez de esperar: el tope absoluto es de 24 horas y el criterio habla de
 * una, asi que lo que se cambia es el {@link Clock} que el filtro recibe, que es para lo que el
 * proyecto lo inyecta en todas partes.
 */
@Import({TestcontainersConfiguration.class, InvitationDoubles.class})
@SpringBootTest
class SessionValidityTests {

    @Autowired
    private Memberships memberships;

    @Autowired
    private Condominiums condominiums;

    @Autowired
    private People people;

    @Autowired
    private ProblemWriter problemas;

    @Autowired
    private JdbcIndexedSessionRepository repositorio;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clearTheContext() {
        // El contexto de seguridad es un ThreadLocal y estos tests lo escriben a mano: sin esto, la
        // identidad de un caso se le aparece al siguiente.
        SecurityContextHolder.clearContext();
    }

    // --- Primera mitad: que el filtro ponga el tiempo que toca ------------------------------

    @Test
    @DisplayName("la sesion de quien administra un condominio se queda en una hora")
    void oneHourForTheAdmin() throws Exception {
        UUID condominium = altaDeCondominio();
        AccountView account = cuentaNueva();
        memberships.grant(condominium, account.id(), Set.of(MembershipRole.CONDO_ADMIN), null, "admin");

        MockHttpSession session = pasarPorElFiltro(account, List.of(), Clock.systemUTC());

        assertThat(session.getMaxInactiveInterval())
                .isEqualTo((int) Duration.ofHours(1).toSeconds());
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    @DisplayName("la del financiero tambien: lo que cuenta es que el rol sea administrativo")
    void oneHourForTheFinanceAdmin() throws Exception {
        UUID condominium = altaDeCondominio();
        AccountView account = cuentaNueva();
        memberships.grant(condominium, account.id(), Set.of(MembershipRole.FINANCE_ADMIN), null, "admin");

        MockHttpSession session = pasarPorElFiltro(account, List.of(), Clock.systemUTC());

        assertThat(session.getMaxInactiveInterval())
                .isEqualTo((int) Duration.ofHours(1).toSeconds());
    }

    @Test
    @DisplayName("la de un propietario se queda en veinticuatro horas")
    void twentyFourHoursForTheOwner() throws Exception {
        UUID condominium = altaDeCondominio();
        AccountView account = cuentaNueva();
        memberships.grant(condominium, account.id(), Set.of(MembershipRole.OWNER), null, "admin");

        MockHttpSession session = pasarPorElFiltro(account, List.of(), Clock.systemUTC());

        assertThat(session.getMaxInactiveInterval())
                .isEqualTo((int) Duration.ofHours(24).toSeconds());
    }

    @Test
    @DisplayName("quien administra un edificio y es propietario en otro tiene sesion de administrador")
    void theStrictestOneWins() throws Exception {
        UUID unEdificio = altaDeCondominio();
        UUID otro = altaDeCondominio();
        AccountView account = cuentaNueva();
        memberships.grant(unEdificio, account.id(), Set.of(MembershipRole.OWNER), null, "admin");
        memberships.grant(otro, account.id(), Set.of(MembershipRole.CONDO_ADMIN), null, "admin");

        // La sesion es una sola y vale para los dos edificios: desde ella puede administrar, asi
        // que la politica que le toca es la del administrador. Tomar la del primer condominio que
        // apareciera seria una decision al azar.
        MockHttpSession session = pasarPorElFiltro(account, List.of(), Clock.systemUTC());

        assertThat(session.getMaxInactiveInterval())
                .isEqualTo((int) Duration.ofHours(1).toSeconds());
    }

    @Test
    @DisplayName("quien administra la plataforma tambien, y sin membresia ninguna")
    void oneHourForThePlatformAdmin() throws Exception {
        AccountView account = cuentaNueva();
        new JdbcTemplate(dataSource)
                .update("insert into platform_admin (user_id, granted_at) values (?, now())", account.id());

        // La autoridad de plataforma la pone el filtro de identidad y no cuelga de ningun
        // condominio, asi que esta se lee de la autenticacion y no de la base.
        MockHttpSession session = pasarPorElFiltro(
                account,
                List.of(new SimpleGrantedAuthority("ROLE_" + MembershipRole.PLATFORM_ADMIN.name())),
                Clock.systemUTC());

        assertThat(session.getMaxInactiveInterval())
                .isEqualTo((int) Duration.ofHours(1).toSeconds());
    }

    @Test
    @DisplayName("una membresia administrativa suspendida no endurece nada")
    void theSuspendedOneDoesNotCount() throws Exception {
        UUID condominium = altaDeCondominio();
        AccountView account = cuentaNueva();
        // FINANCE_ADMIN y no CONDO_ADMIN: el #19 no deja suspender la ultima membresia con
        // CONDO_ADMIN del condominio, que es lo que esta seria. El rol financiero es igual de
        // administrativo para lo que aqui se mide.
        var membership =
                memberships.grant(condominium, account.id(), Set.of(MembershipRole.FINANCE_ADMIN), null, "admin");
        memberships.suspend(condominium, membership.id(), "se va de viaje tres meses", "admin");

        // No da acceso a nada, asi que tampoco hay nada que proteger con una hora.
        MockHttpSession session = pasarPorElFiltro(account, List.of(), Clock.systemUTC());

        assertThat(session.getMaxInactiveInterval())
                .isEqualTo((int) Duration.ofHours(24).toSeconds());
    }

    // --- El tope absoluto, que no es inactividad --------------------------------------------

    @Test
    @DisplayName("a las veinticuatro horas la sesion se cierra aunque se este usando")
    void theAbsoluteCapClosesTheSession() throws Exception {
        UUID condominium = altaDeCondominio();
        AccountView account = cuentaNueva();
        memberships.grant(condominium, account.id(), Set.of(MembershipRole.OWNER), null, "admin");

        // El reloj veinticinco horas por delante: la sesion se acaba de crear, asi que para el
        // filtro lleva veinticinco horas abierta. Es justo lo que la inactividad no puede atrapar,
        // porque esta peticion la mantiene viva.
        var dentroDeVeinticincoHoras = Clock.offset(Clock.systemUTC(), Duration.ofHours(25));
        var response = new MockHttpServletResponse();
        MockHttpSession session = pasarPorElFiltro(account, List.of(), dentroDeVeinticincoHoras, response);

        assertThat(session.isInvalid()).isTrue();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("NOT_AUTHENTICATED");
    }

    @Test
    @DisplayName("a las veintitres no: el tope es un tope, no un redondeo")
    void atTwentyThreeHoursNotYet() throws Exception {
        UUID condominium = altaDeCondominio();
        AccountView account = cuentaNueva();
        memberships.grant(condominium, account.id(), Set.of(MembershipRole.OWNER), null, "admin");

        var dentroDeVeintitresHoras = Clock.offset(Clock.systemUTC(), Duration.ofHours(23));
        var response = new MockHttpServletResponse();
        MockHttpSession session = pasarPorElFiltro(account, List.of(), dentroDeVeintitresHoras, response);

        assertThat(session.isInvalid()).isFalse();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // --- Segunda mitad: que una sesion con ese tiempo caduque de verdad ---------------------

    @Test
    @DisplayName("una sesion de una hora, sin usar durante sesenta y un minutos, ya no existe")
    void anAdminIdleForAnHourIsLockedOut() {
        String id = storedSession(Duration.ofHours(1), Duration.ofMinutes(61));

        // Es el criterio del issue, contra la tabla de verdad: Spring Session no devuelve una
        // sesion caducada, asi que la siguiente peticion de ese navegador llega sin identidad y la
        // cadena responde 401.
        assertThat(repositorio.findById(id)).isNull();
    }

    @Test
    @DisplayName("y la de un propietario, con los mismos sesenta y un minutos, sigue viva")
    void theOwnerIsNotLeftOut() {
        // El control de la anterior. Sin el, la suite pasaria igual con las dos duraciones puestas
        // en una hora, que es precisamente el error que el criterio distingue.
        String id = storedSession(Duration.ofHours(24), Duration.ofMinutes(61));

        assertThat(repositorio.findById(id)).isNotNull();
    }

    // --- Armado -----------------------------------------------------------------------------

    /**
     * Una sesion real con esa inactividad y ese tiempo sin usarse, ya guardada.
     *
     * @return su identificador
     */
    private String storedSession(Duration inactivity, Duration sinUsarse) {
        return guardar(repositorio, inactivity, sinUsarse);
    }

    /**
     * Generico porque el tipo de sesion del repositorio JDBC <strong>no es publico</strong>: no se
     * puede nombrar desde aqui, pero el compilador lo infiere por el parametro de tipo. Es la misma
     * maniobra que {@code BffSessionsTests}, y por el mismo motivo.
     */
    private static <S extends Session> String guardar(
            SessionRepository<S> repositorio, Duration inactivity, Duration sinUsarse) {
        S session = repositorio.createSession();
        session.setMaxInactiveInterval(inactivity);
        session.setLastAccessedTime(Instant.now().minus(sinUsarse));
        repositorio.save(session);
        return session.getId();
    }

    private MockHttpSession pasarPorElFiltro(AccountView account, List<GrantedAuthority> authorities, Clock clock)
            throws Exception {
        return pasarPorElFiltro(account, authorities, clock, new MockHttpServletResponse());
    }

    /** Una peticion con sesion y con la identidad ya resuelta, tal como la deja el filtro del #13. */
    private MockHttpSession pasarPorElFiltro(
            AccountView account, List<GrantedAuthority> authorities, Clock clock, MockHttpServletResponse response)
            throws Exception {
        var identity = new AuthenticatedIdentity(
                new AuthenticatedIdentity.Actor(account.id(), account.personId()),
                AuthenticatedIdentity.AuthenticationFacts.DESCONOCIDA,
                authorities);
        SecurityContextHolder.getContext().setAuthentication(identity);

        var request = new MockHttpServletRequest("GET", "/api/v1/me");
        var session = new MockHttpSession();
        request.setSession(session);

        new SessionValidityFilter(memberships, problemas, clock).doFilter(request, response, new MockFilterChain());
        return session;
    }

    private UUID altaDeCondominio() {
        return condominiums
                .register(
                        new NewCondominium(
                                "Vigencia " + UUID.randomUUID(),
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
        return people.openAccount(person.id(), "emma-" + UUID.randomUUID() + "@coprop.bo", null, "admin");
    }
}
