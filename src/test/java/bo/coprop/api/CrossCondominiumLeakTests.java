package bo.coprop.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
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
import bo.coprop.identity.InvitationDoubles;
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
@Import({TestcontainersConfiguration.class, TestTokenProvider.class, InvitationDoubles.class})
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossCondominiumLeakTests {

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
    private Invitations invitations;

    @Autowired
    private UnitRelations relaciones;

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

    /**
     * Las cuatro personas que el issue #19 necesita con una relacion ya puesta en la unidad propia.
     *
     * <p>Se registran aqui y no se reutiliza {@code personaSuelta} porque los endpoints que cierran
     * una relacion <strong>exigen que exista</strong>: sin ella responderian 404 tambien en el
     * condominio propio y el control de la suite --que es la mitad que de verdad comprueba algo--
     * no distinguiria ese 404 del que se busca.
     */
    private UUID vendedor;

    private UUID comprador;
    private UUID ocupanteParaMudar;
    private UUID responsableParaCerrar;

    @BeforeAll
    void prepararLosDosCondominios() {
        propio = altaDeCondominio("FUGA-OWN");
        ajeno = altaDeCondominio("FUGA-AJENO");

        unidadPropia = altaDeUnidad(propio, "P-101");
        unidadAjena = altaDeUnidad(ajeno, "A-101");
        bloquePropio = units.addBlock(propio, "Bloque propio", "admin").id();
        bloqueAjeno = units.addBlock(ajeno, "Bloque ajeno", "admin").id();

        // Quien pide: cuenta con membresia ACTIVE en el propio y ninguna en el ajeno.
        AccountView account = cuentaNueva();
        membresiaPropia = memberships
                .grant(propio, account.id(), Set.of(MembershipRole.CONDO_ADMIN), null, "admin")
                .id();

        // Una membresia del otro condominio, para pedirla por identificador desde el propio.
        membresiaAjena = memberships
                .grant(ajeno, cuentaNueva().id(), Set.of(MembershipRole.OWNER), null, "admin")
                .id();

        personaSuelta =
                people.register(new NewPerson("Ana", "Perez", null), "admin").id();

        // Una cuenta de verdad a la que invitar: con una inventada, el alta de membresia daria 404
        // por la cuenta y no por el condominio. Lo cazo el control de la suite.
        cuentaParaInvitar = cuentaNueva().id();

        // Una invitacion abierta en cada condominio, para pedirlas por identificador. Se emiten por
        // el puerto y no por HTTP porque lo que esta suite comprueba es el aislamiento, no la
        // emision; el correo lo recoge el doble.
        invitacionPropia = invitations
                .issue(
                        propio,
                        cuentaNueva().id(),
                        Set.of(MembershipRole.OWNER),
                        account.id().toString())
                .id();
        invitacionAjena = invitations
                .issue(
                        ajeno,
                        cuentaNueva().id(),
                        Set.of(MembershipRole.OWNER),
                        account.id().toString())
                .id();

        // Las relaciones que los cierres del issue #19 van a cerrar, puestas de antemano en la
        // unidad propia. En la ajena no hace falta ninguna: ahi el filtro de condominio responde
        // antes de que nadie mire si la relacion existe, que es justo lo que la suite comprueba.
        vendedor =
                people.register(new NewPerson("Vende", "Perez", null), "admin").id();
        comprador =
                people.register(new NewPerson("Compra", "Perez", null), "admin").id();
        ocupanteParaMudar =
                people.register(new NewPerson("Ocupa", "Perez", null), "admin").id();
        responsableParaCerrar =
                people.register(new NewPerson("Paga", "Perez", null), "admin").id();
        relaciones.grantOwnership(propio, unidadPropia, vendedor, new BigDecimal("100.000000"), "admin");
        relaciones.grantOccupancy(propio, unidadPropia, ocupanteParaMudar, OccupancyRole.TENANT, "admin");
        relaciones.assignBilling(propio, unidadPropia, responsableParaCerrar, null, "admin");

        String tokenSubject = TestTokenProvider.sujetoNuevo();
        autorizacion = "Bearer " + TestTokenProvider.valido(tokenSubject, account.email());
    }

    private UUID altaDeCondominio(String name) {
        return condominiums
                .register(
                        new NewCondominium(name, nitNuevo(), CondominiumType.BUILDING, "America/La_Paz", 5, 10),
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

    /**
     * Un endpoint acotado a un condominio, con lo que haga falta para invocarlo.
     *
     * <p>La ruta se arma con los identificadores del condominio que toque en cada corrida, para que
     * el mismo caso sirva contra el propio y contra el ajeno.
     */
    private record Endpoint(String name, HttpMethod metodo, String plantilla, @Nullable String cuerpo) {

        MockHttpServletRequestBuilder peticion(
                UUID condominium, UUID unit, UUID block, UUID membership, UUID invitation) {
            var constructor = request(metodo, plantilla.formatted(condominium, unit, block, membership, invitation))
                    .with(SecurityMockMvcRequestPostProcessors.csrf());
            String json = cuerpo;
            if (json != null) {
                constructor.contentType(MediaType.APPLICATION_JSON).content(json);
            }
            return constructor;
        }
    }

    /**
     * El cuerpo de la edicion de la ficha.
     *
     * <p>En un bloque de texto para no escapar las comillas, y con un NIT propio de esta suite: el
     * NIT es unico por condominio y la rejilla de autorizacion usa el suyo contra la misma base.
     */
    private static String edicionDelCondominio() {
        return """
                {"name": "Fuga editada", "nit": "7777778001", "address": "Av. Ballivian 456"}""";
    }

    /**
     * Los veintinueve endpoints que cuelgan de un condominio.
     *
     * <p>Son todos los que hay: los demas --personas, cuentas, el perfil propio, los
     * superadministradores, la bitacora de plataforma y las dos llamadas de quien llega con un token
     * de invitacion-- son globales a proposito (Seguridad 2 y 6.1) y no tienen condominio del que
     * aislarse. Cuando entre uno nuevo bajo {@code /condominios/{id}}, su caso entra aqui; los cinco
     * de invitaciones entraron asi con el issue #18, la auditoria con el #21, los siete de bajas,
     * suspensiones y cierres de relacion con el #19, el cambio de roles con el #20 y la edicion de la
     * ficha con el #90.
     */
    private List<Endpoint> endpoints() {
        String person = personaSuelta.toString();
        return List.of(
                new Endpoint("consultar el condominio", HttpMethod.GET, "/api/v1/condominios/%s", null),
                // La ficha del condominio. El NIT va inventado y distinto del que usa la rejilla de
                // autorizacion, para no chocar con el suyo: el NIT es unico y las dos suites
                // comparten base.
                new Endpoint("editar el condominio", HttpMethod.PUT, "/api/v1/condominios/%s", edicionDelCondominio()),
                new Endpoint(
                        "cambiar la configuracion",
                        HttpMethod.PUT,
                        "/api/v1/condominios/%s/configuracion",
                        "{\"issueDay\":5,\"dueDay\":10}"),
                new Endpoint(
                        "dar de alta una unidad",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/unidades",
                        "{\"code\":\"X-1\",\"type\":\"APARTMENT\",\"aliquot\":\"1.000000\"}"),
                new Endpoint(
                        "alta masiva de unidades",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/unidades/lote",
                        "{\"units\":[{\"code\":\"X-2\",\"type\":\"APARTMENT\",\"aliquot\":\"1.000000\"}]}"),
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
                        "{\"personId\":\"" + person + "\",\"share\":10.0}"),
                new Endpoint(
                        "listar los propietarios",
                        HttpMethod.GET,
                        "/api/v1/condominios/%s/unidades/%s/propietarios",
                        null),
                new Endpoint(
                        "registrar un ocupante",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/unidades/%s/ocupantes",
                        "{\"personId\":\"" + person + "\",\"role\":\"TENANT\"}"),
                new Endpoint(
                        "registrar un responsable de pago",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/unidades/%s/responsables-de-pago",
                        "{\"personId\":\"" + person + "\"}"),
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
                new Endpoint("consultar la auditoria", HttpMethod.GET, "/api/v1/condominios/%s/auditoria", null),
                // Los siete del issue #19: la baja, la suspension y la reactivacion de una
                // membresia, y en la unidad la venta, el historico de propietarios y los dos
                // cierres de relacion.
                new Endpoint(
                        "dar de baja una membresia",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/membresias/%4$s/baja",
                        "{\"reason\":\"prueba de aislamiento\"}"),
                new Endpoint(
                        "suspender una membresia",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/membresias/%4$s/suspension",
                        "{\"reason\":\"prueba de aislamiento\"}"),
                new Endpoint(
                        "reactivar una membresia",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/membresias/%4$s/reactivacion",
                        "{\"reason\":\"prueba de aislamiento\"}"),
                // El cambio de roles del issue #20. Como los tres anteriores, en el condominio
                // propio cae sobre la membresia de quien pide y se rechaza con un 422 --nadie se
                // cambia los roles a si mismo--, que es lo que el control necesita: cualquier cosa
                // menos 404.
                new Endpoint(
                        "cambiar los roles de una membresia",
                        HttpMethod.PUT,
                        "/api/v1/condominios/%s/membresias/%4$s/roles",
                        "{\"roles\":[\"OWNER\"],\"reason\":\"prueba de aislamiento\"}"),
                new Endpoint(
                        "ver el historico de propietarios",
                        HttpMethod.GET,
                        "/api/v1/condominios/%s/unidades/%s/propietarios/historico",
                        null),
                new Endpoint(
                        "transferir una unidad",
                        HttpMethod.POST,
                        "/api/v1/condominios/%s/unidades/%s/transferencia",
                        "{\"fromPersonId\":\"" + vendedor + "\",\"toPersonId\":\"" + comprador + "\"}"),
                new Endpoint(
                        "terminar una ocupacion",
                        HttpMethod.DELETE,
                        "/api/v1/condominios/%s/unidades/%s/ocupantes/" + ocupanteParaMudar,
                        null),
                new Endpoint(
                        "terminar una responsabilidad de pago",
                        HttpMethod.DELETE,
                        "/api/v1/condominios/%s/unidades/%s/responsables-de-pago/" + responsableParaCerrar,
                        null));
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
                    int status = resultado.getResponse().getStatus();
                    if (status == 404) {
                        throw new AssertionError(
                                "El endpoint '%s' dio 404 en el condominio propio: la ruta esta mal escrita o le falta algo al montaje, y entonces su mitad de fuga no prueba nada."
                                        .formatted(endpoint.name()));
                    }
                });
    }

    @Test
    @DisplayName("una cuenta sin ninguna membresia tampoco ve el condominio")
    void sinNingunaMembresiaNoVeNada() throws Exception {
        // Es la otra mitad del criterio del issue #13: con cuenta en COPROP pero sin membresia en
        // ningun condominio, se autentica y no ve nada.
        AccountView account = cuentaNueva();
        String token = TestTokenProvider.valido(TestTokenProvider.sujetoNuevo(), account.email());

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
