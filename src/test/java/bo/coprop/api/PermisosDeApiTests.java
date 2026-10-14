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
 * <p>{@code MatrizDePermisosTests} prueba la decision --quien puede que-- sin levantar nada. Esto
 * prueba lo otro: que cada endpoint pregunta por el permiso que le toca. Son dos cosas distintas y
 * se rompen por separado; una matriz impecable no sirve de nada si un controlador se olvido de
 * mirarla.
 *
 * <p>Las anotaciones {@code @PreAuthorize} son <strong>cadenas</strong>: el compilador no las
 * revisa. Un permiso mal escrito o un bean renombrado no rompen el build, revientan en ejecucion.
 * Por eso cada endpoint protegido tiene aqui su caso.
 */
@Import({TestcontainersConfiguration.class, ProveedorDeTokensDePrueba.class})
@SpringBootTest
@AutoConfigureMockMvc
class PermisosDeApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Condominiums condominios;

    @Autowired
    private People personas;

    @Autowired
    private Memberships membresias;

    @Autowired
    private DataSource dataSource;

    private UUID condominioNuevo() {
        return condominios
                .register(
                        new NewCondominium(
                                "Torre " + UUID.randomUUID(),
                                nitNuevo(),
                                CondominiumType.EDIFICIO,
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
        PersonView persona = personas.register(new NewPerson("Emma", "Tola", null), "admin");
        return personas.openAccount(persona.id(), ProveedorDeTokensDePrueba.correoNuevo(), null, "admin");
    }

    /** Una cuenta con esos roles en ese condominio, y el token con el que entra. */
    private String tokenDe(UUID condominio, MembershipRole... roles) {
        AccountView cuenta = cuentaNueva();
        membresias.grant(condominio, cuenta.id(), Set.of(roles), null, "admin");
        return "Bearer " + ProveedorDeTokensDePrueba.valido(ProveedorDeTokensDePrueba.sujetoNuevo(), cuenta.email());
    }

    /**
     * Una cuenta con alcance de plataforma, y el token con el que entra.
     *
     * <p>Se concede por SQL porque todavia no hay operacion que lo haga: conceder y revocar el rol
     * de plataforma a otra cuenta es lo que falta del issue #20. El primero lo abre el arranque, y
     * eso tiene su propio test.
     */
    private String tokenDeSuperadministrador() {
        AccountView cuenta = cuentaNueva();
        new JdbcTemplate(dataSource)
                .update("insert into platform_admin (user_id, granted_at) values (?, now())", cuenta.id());
        return "Bearer " + ProveedorDeTokensDePrueba.valido(ProveedorDeTokensDePrueba.sujetoNuevo(), cuenta.email());
    }

    private static final String UNIDAD = "{\"code\":\"A-1\",\"type\":\"DEPARTAMENTO\",\"aliquot\":\"1.000000\"}";

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder peticion(
            HttpMethod metodo, String ruta, String autorizacion, String cuerpo) {
        var constructor = request(metodo, ruta)
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .header(HttpHeaders.AUTHORIZATION, autorizacion);
        if (cuerpo != null) {
            constructor.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        }
        return constructor;
    }

    @Test
    @DisplayName("el propietario no da de alta unidades: eso es del administrador del condominio")
    void elPropietarioNoDaDeAltaUnidades() throws Exception {
        UUID condominio = condominioNuevo();

        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/condominios/" + condominio + "/unidades",
                        tokenDe(condominio, MembershipRole.OWNER),
                        UNIDAD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
    }

    @Test
    @DisplayName("el administrador del condominio si da de alta unidades")
    void elAdministradorSiDaDeAltaUnidades() throws Exception {
        UUID condominio = condominioNuevo();

        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/condominios/" + condominio + "/unidades",
                        tokenDe(condominio, MembershipRole.CONDO_ADMIN),
                        UNIDAD))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("el administrador del condominio no toca lo que se cobra: separacion de funciones")
    void elAdministradorNoTocaLoQueSeCobra() throws Exception {
        UUID condominio = condominioNuevo();

        mockMvc.perform(peticion(
                        HttpMethod.PUT,
                        "/api/v1/condominios/" + condominio + "/configuracion",
                        tokenDe(condominio, MembershipRole.CONDO_ADMIN),
                        "{\"issueDay\":7,\"dueDay\":15}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));

        mockMvc.perform(peticion(
                        HttpMethod.PUT,
                        "/api/v1/condominios/" + condominio + "/configuracion",
                        tokenDe(condominio, MembershipRole.FINANCE_ADMIN),
                        "{\"issueDay\":7,\"dueDay\":15}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("el financiero no reparte roles: la otra mitad de la separacion de funciones")
    void elFinancieroNoReparteRoles() throws Exception {
        UUID condominio = condominioNuevo();
        AccountView invitada = cuentaNueva();

        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/condominios/" + condominio + "/membresias",
                        tokenDe(condominio, MembershipRole.FINANCE_ADMIN),
                        "{\"accountId\":\"" + invitada.id() + "\",\"roles\":[\"OWNER\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
    }

    @Test
    @DisplayName("crear condominios es del superadministrador, y de nadie mas")
    void crearCondominiosEsDelSuperadministrador() throws Exception {
        UUID condominio = condominioNuevo();
        String alta = "{\"name\":\"Torre nueva\",\"nit\":\"" + nitNuevo()
                + "\",\"type\":\"EDIFICIO\",\"timeZone\":\"America/La_Paz\",\"issueDay\":5,\"dueDay\":10}";

        mockMvc.perform(peticion(
                        HttpMethod.POST, "/api/v1/condominios", tokenDe(condominio, MembershipRole.CONDO_ADMIN), alta))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));

        mockMvc.perform(peticion(HttpMethod.POST, "/api/v1/condominios", tokenDeSuperadministrador(), alta))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("el superadministrador pone el primer administrador de un condominio donde no es miembro")
    void elSuperadministradorPoneAlPrimerAdministrador() throws Exception {
        // Es el arranque de un condominio, y la razon de que el filtro del #17 lo deje pasar sin
        // membresia: sin esto, un condominio recien creado no tendria forma de tener a nadie dentro.
        UUID condominio = condominioNuevo();
        AccountView primero = cuentaNueva();

        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/condominios/" + condominio + "/membresias",
                        tokenDeSuperadministrador(),
                        "{\"accountId\":\"" + primero.id() + "\",\"roles\":[\"CONDO_ADMIN\"]}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("el superadministrador solo reparte roles administrativos")
    void elSuperadministradorSoloRepartoAdministrativo() throws Exception {
        // La celda dice "Solo administradores". Que pueda poner un CONDO_ADMIN no lo convierte en
        // alguien que reparte propietarios: eso es operar el condominio, y 10.1 dice que no opera.
        UUID condominio = condominioNuevo();
        AccountView alguien = cuentaNueva();

        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/condominios/" + condominio + "/membresias",
                        tokenDeSuperadministrador(),
                        "{\"accountId\":\"" + alguien.id() + "\",\"roles\":[\"OWNER\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
    }

    @Test
    @DisplayName("el administrador del condominio si puede conceder los dos roles administrativos")
    void elAdministradorConcedeAmbosRolesAdministrativos() throws Exception {
        // Decision explicita de Seguridad 10.1: en condominios pequenos una sola persona hace las
        // dos cosas. Lo que no puede es pasar desapercibido, y de eso se encarga la auditoria.
        UUID condominio = condominioNuevo();
        AccountView invitada = cuentaNueva();

        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/condominios/" + condominio + "/membresias",
                        tokenDe(condominio, MembershipRole.CONDO_ADMIN),
                        "{\"accountId\":\"" + invitada.id() + "\",\"roles\":[\"CONDO_ADMIN\",\"FINANCE_ADMIN\"]}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PLATFORM_ADMIN no se concede como membresia de un condominio")
    void elRolDePlataformaNoEsUnaMembresia() throws Exception {
        // Su alcance es la plataforma y vive en su propia tabla. Concederlo aqui daria un
        // "superadministrador de un condominio", que no significa nada, y dejaria dos sitios donde
        // mirar quien administra la plataforma.
        UUID condominio = condominioNuevo();
        AccountView alguien = cuentaNueva();

        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/condominios/" + condominio + "/membresias",
                        tokenDeSuperadministrador(),
                        "{\"accountId\":\"" + alguien.id() + "\",\"roles\":[\"PLATFORM_ADMIN\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("REGLA_DE_NEGOCIO"));
    }

    @Test
    @DisplayName("el superadministrador que ademas es miembro conserva los dos juegos de roles")
    void elSuperadministradorMiembroConservaAmbos() throws Exception {
        // Es como arranca un condominio de verdad: el superadministrador lo crea y se pone de
        // primer administrador hasta que haya a quien invitar. Si al entrar perdiera los roles de
        // su membresia --porque el filtro se conformara con que es de plataforma-- no podria
        // administrar el condominio que acaba de abrir, y el fallo saldria como un 403 raro.
        UUID condominio = condominioNuevo();
        AccountView cuenta = cuentaNueva();
        new JdbcTemplate(dataSource)
                .update("insert into platform_admin (user_id, granted_at) values (?, now())", cuenta.id());
        membresias.grant(condominio, cuenta.id(), Set.of(MembershipRole.CONDO_ADMIN), null, "admin");
        String token =
                "Bearer " + ProveedorDeTokensDePrueba.valido(ProveedorDeTokensDePrueba.sujetoNuevo(), cuenta.email());

        // De la membresia.
        mockMvc.perform(peticion(HttpMethod.POST, "/api/v1/condominios/" + condominio + "/unidades", token, UNIDAD))
                .andExpect(status().isCreated());

        // De la plataforma, en la misma sesion.
        mockMvc.perform(
                        peticion(
                                HttpMethod.POST,
                                "/api/v1/condominios",
                                token,
                                "{\"name\":\"Otra torre\",\"nit\":\"" + nitNuevo()
                                        + "\",\"type\":\"EDIFICIO\",\"timeZone\":\"America/La_Paz\",\"issueDay\":5,\"dueDay\":10}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("dar de alta una persona pide administrar algun condominio")
    void darDeAltaUnaPersonaPideAdministrarAlguno() throws Exception {
        UUID condominio = condominioNuevo();
        String persona = "{\"firstNames\":\"Ana\",\"lastNames\":\"Perez\"}";

        // La ruta es global --la persona no cuelga de un condominio-- pero quien la da de alta lo
        // hace para su edificio. Un propietario no administra ninguno.
        mockMvc.perform(peticion(
                        HttpMethod.POST, "/api/v1/personas", tokenDe(condominio, MembershipRole.OWNER), persona))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));

        mockMvc.perform(peticion(
                        HttpMethod.POST, "/api/v1/personas", tokenDe(condominio, MembershipRole.CONDO_ADMIN), persona))
                .andExpect(status().isCreated());
    }
}
