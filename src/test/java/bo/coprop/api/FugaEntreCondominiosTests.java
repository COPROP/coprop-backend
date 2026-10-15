package bo.coprop.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.condominium.CondominiumType;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import bo.coprop.condominium.NewUnit;
import bo.coprop.condominium.UnitType;
import bo.coprop.condominium.Units;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.DoblesDeInvitacion;
import bo.coprop.identity.Invitations;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.Memberships;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.identity.PersonView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * La suite de fuga del issue #17: para cada endpoint acotado a un condominio, alguien del A
 * intenta leer y escribir en el B.
 *
 * <p>El criterio es que todos respondan <strong>404 y no 403</strong>. La diferencia no es
 * cosmetica: un 403 confirma que ese condominio existe, y con eso quien tenga una cuenta puede
 * enumerar los condominios de la plataforma probando identificadores. Esto cierra tambien el tercer
 * criterio del issue #5, que se derivo aqui porque entonces no habia ni condominios ni membresias.
 *
 * <p><strong>Cada caso se corre dos veces</strong>: contra el condominio ajeno, donde tiene que dar
 * 404, y contra el propio, donde tiene que dar cualquier cosa menos 404. Sin esa segunda mitad la
 * suite pasaria igual con las rutas mal escritas, que es la forma mas facil de tener una bateria de
 * seguridad que no comprueba nada.
 */
@Import({TestcontainersConfiguration.class, ProveedorDeTokensDePrueba.class, DoblesDeInvitacion.class})
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FugaEntreCondominiosTests {

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
    private Invitations invitaciones;

    /** El condominio de quien pide, donde si tiene membresia. */
    private UUID propio;

    /** El condominio del vecino. Todo lo que se pida de aqui tiene que responder 404. */
    private UUID ajeno;

    private UUID unidadPropia;
    private UUID unidadAjena;
    private UUID bloquePropio;
    private UUID bloqueAjeno;
    private UUID membresiaPropia;
    private UUID membresiaAjena;
    private UUID invitacionPropia;
    private UUID invitacionAjena;
    private UUID personaSuelta;
    private UUID cuentaParaInvitar;
    private String autorizacion;

    @BeforeAll
    void prepararLosDosCondominios() {
        propio = altaDeCondominio("FUGA-PROPIO");
        ajeno = altaDeCondominio("FUGA-AJENO");

        unidadPropia = altaDeUnidad(propio, "P-101");
        unidadAjena = altaDeUnidad(ajeno, "A-101");
        bloquePropio = unidades.addBlock(propio, "Bloque propio", "admin").id();
        bloqueAjeno = unidades.addBlock(ajeno, "Bloque ajeno", "admin").id();

        // Quien pide: cuenta con membresia ACTIVA en el propio y ninguna en el ajeno.
        AccountView cuenta = cuentaNueva();
        membresiaPropia = membresias
                .grant(propio, cuenta.id(), Set.of(MembershipRole.CONDO_ADMIN), null, "admin")
                .id();

        // Una membresia del otro condominio, para pedirla por identificador desde el propio.
        membresiaAjena = membresias
                .grant(ajeno, cuentaNueva().id(), Set.of(MembershipRole.OWNER), null, "admin")
                .id();

        personaSuelta =
                personas.register(new NewPerson("Ana", "Perez", null), "admin").id();

        // Una cuenta de verdad a la que invitar: con una inventada, el alta de membresia daria 404
        // por la cuenta y no por el condominio. Lo cazo el control de la suite.
        cuentaParaInvitar = cuentaNueva().id();

        // Una invitacion abierta en cada condominio, para pedirlas por identificador. Se emiten por
        // el puerto y no por HTTP porque lo que esta suite comprueba es el aislamiento, no la
        // emision; el correo lo recoge el doble.
        invitacionPropia = invitaciones
                .issue(
                        propio,
                        cuentaNueva().id(),
                        Set.of(MembershipRole.OWNER),
                        cuenta.id().toString())
                .id();
        invitacionAjena = invitaciones
                .issue(
                        ajeno,
                        cuentaNueva().id(),
                        Set.of(MembershipRole.OWNER),
                        cuenta.id().toString())
                .id();

        String sujeto = ProveedorDeTokensDePrueba.sujetoNuevo();
        autorizacion = "Bearer " + ProveedorDeTokensDePrueba.valido(sujeto, cuenta.email());
    }

    private UUID altaDeCondominio(String nombre) {
        return condominios
                .register(
                        new NewCondominium(nombre, nitNuevo(), CondominiumType.EDIFICIO, "America/La_Paz", 5, 10),
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
                        new NewUnit(codigo, UnitType.DEPARTAMENTO, new BigDecimal("1.000000"), null, null, null),
                        "admin")
                .id();
    }

    private AccountView cuentaNueva() {
        PersonView persona = personas.register(new NewPerson("Emma", "Tola", null), "admin");
        return personas.openAccount(persona.id(), ProveedorDeTokensDePrueba.correoNuevo(), null, "admin");
    }

    /**
     * Un endpoint acotado a un condominio, con lo que haga falta para invocarlo.
     *
     * <p>La ruta se arma con los identificadores del condominio que toque en cada corrida, para que
     * el mismo caso sirva contra el propio y contra el ajeno.
     */
    private record Endpoint(String nombre, HttpMethod metodo, String plantilla, @Nullable String cuerpo) {

        MockHttpServletRequestBuilder peticion(
                UUID condominio, UUID unidad, UUID bloque, UUID membresia, UUID invitacion) {
            var constructor = request(metodo, plantilla.formatted(condominio, unidad, bloque, membresia, invitacion))
                    .with(SecurityMockMvcRequestPostProcessors.csrf());
            String json = cuerpo;
            if (json != null) {
                constructor.contentType(MediaType.APPLICATION_JSON).content(json);
            }
            return constructor;
        }
    }

    /**
     * Los veinte endpoints que cuelgan de un condominio.
     *
     * <p>Son todos los que hay: los demas --personas, cuentas, el perfil propio y las dos llamadas
     * de quien llega con un token de invitacion-- son globales a proposito (Seguridad 2 y 6.1) y no
     * tienen condominio del que aislarse. Cuando entre uno nuevo bajo {@code /condominios/{id}}, su
     * caso entra aqui; los cinco de invitaciones entraron asi con el issue #18, y la auditoria con el #21.
     */
    private List<Endpoint> endpoints() {
        String persona = personaSuelta.toString();
        return List.of(
                new Endpoint("consultar el condominio", HttpMethod.GET, "/api/v1/condominios/%s", null),
                new Endpoint(
                        "cambiar la configuracion",
                        HttpMethod.PUT,
                        "/api/v1/condominios/%s/configuracion",
                        "{\"issueDay\":5,\"dueDay\":10}"),
                new Endpoint(
                        "dar de alta una unidad",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/unidades",
                        "{\"code\":\"X-1\",\"type\":\"DEPARTAMENTO\",\"aliquot\":\"1.000000\"}"),
                new Endpoint(
                        "alta masiva de unidades",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/unidades/lote",
                        "{\"unidades\":[{\"code\":\"X-2\",\"type\":\"DEPARTAMENTO\",\"aliquot\":\"1.000000\"}]}"),
                new Endpoint("consultar una unidad", HttpMethod.GET, "/api/v1/condominios/%s/unidades/%s", null),
                new Endpoint(
                        "dar de alta un bloque",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/bloques",
                        "{\"name\":\"Torre X\"}"),
                new Endpoint(
                        "dar de alta un piso",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/bloques/%3$s/pisos",
                        "{\"number\":3}"),
                new Endpoint(
                        "registrar un propietario",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/unidades/%s/propietarios",
                        "{\"personId\":\"" + persona + "\",\"share\":10.0}"),
                new Endpoint(
                        "listar los propietarios",
                        HttpMethod.GET,
                        "/api/v1/condominios/%s/unidades/%s/propietarios",
                        null),
                new Endpoint(
                        "registrar un ocupante",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/unidades/%s/ocupantes",
                        "{\"personId\":\"" + persona + "\",\"role\":\"INQUILINO\"}"),
                new Endpoint(
                        "registrar un responsable de pago",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/unidades/%s/responsables-de-pago",
                        "{\"personId\":\"" + persona + "\"}"),
                new Endpoint(
                        "conceder una membresia",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/membresias",
                        "{\"accountId\":\"" + cuentaParaInvitar + "\",\"roles\":[\"OWNER\"]}"),
                new Endpoint("listar las membresias", HttpMethod.GET, "/api/v1/condominios/%s/membresias", null),
                new Endpoint("consultar una membresia", HttpMethod.GET, "/api/v1/condominios/%s/membresias/%4$s", null),
                new Endpoint(
                        "emitir una invitacion",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/invitaciones",
                        "{\"accountId\":\"" + cuentaParaInvitar + "\",\"roles\":[\"OWNER\"]}"),
                new Endpoint("listar las invitaciones", HttpMethod.GET, "/api/v1/condominios/%s/invitaciones", null),
                new Endpoint(
                        "consultar una invitacion", HttpMethod.GET, "/api/v1/condominios/%s/invitaciones/%5$s", null),
                new Endpoint(
                        "reenviar una invitacion",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/invitaciones/%5$s/reenvio",
                        null),
                new Endpoint(
                        "revocar una invitacion",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/invitaciones/%5$s/revocacion",
                        null),
                new Endpoint("consultar la auditoria", HttpMethod.GET, "/api/v1/condominios/%s/auditoria", null));
    }

    @ParameterizedTest(name = "{0} en el condominio ajeno da 404")
    @MethodSource("endpoints")
    @DisplayName("ningun endpoint del condominio ajeno responde nada que no sea 404")
    void elCondominioAjenoNoExiste(Endpoint endpoint) throws Exception {
        mockMvc.perform(endpoint.peticion(ajeno, unidadAjena, bloqueAjeno, membresiaAjena, invitacionAjena)
                        .header(HttpHeaders.AUTHORIZATION, autorizacion))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @ParameterizedTest(name = "{0} en el condominio propio no da 404")
    @MethodSource("endpoints")
    @DisplayName("los mismos endpoints responden en el condominio propio")
    void elCondominioPropioSiResponde(Endpoint endpoint) throws Exception {
        // El control de la suite. Sin esto, una ruta mal escrita daria 404 en las dos mitades y la
        // bateria entera pasaria sin comprobar nada.
        mockMvc.perform(endpoint.peticion(propio, unidadPropia, bloquePropio, membresiaPropia, invitacionPropia)
                        .header(HttpHeaders.AUTHORIZATION, autorizacion))
                .andExpect(resultado -> {
                    int estado = resultado.getResponse().getStatus();
                    if (estado == 404) {
                        throw new AssertionError(
                                "El endpoint '%s' dio 404 en el condominio propio: la ruta esta mal escrita o le falta algo al montaje, y entonces su mitad de fuga no prueba nada."
                                        .formatted(endpoint.nombre()));
                    }
                });
    }

    @Test
    @DisplayName("una cuenta sin ninguna membresia tampoco ve el condominio")
    void sinNingunaMembresiaNoVeNada() throws Exception {
        // Es la otra mitad del criterio del issue #13: con cuenta en COPROP pero sin membresia en
        // ningun condominio, se autentica y no ve nada.
        AccountView cuenta = cuentaNueva();
        String token = ProveedorDeTokensDePrueba.valido(ProveedorDeTokensDePrueba.sujetoNuevo(), cuenta.email());

        mockMvc.perform(request(HttpMethod.GET, "/api/v1/condominios/" + propio)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("sin token la respuesta sigue siendo 401, no el 404 del aislamiento")
    void sinTokenSigueSiendo401() throws Exception {
        // El filtro de condominio no puede adelantarse al de identidad: un 404 aqui taparia que lo
        // que falta es iniciar sesion.
        mockMvc.perform(request(HttpMethod.GET, "/api/v1/condominios/" + ajeno))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }
}
