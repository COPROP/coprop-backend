package bo.coprop.api;

import static bo.coprop.api.AuthorizationChainTests.Result.NOT_FOUND;
import static bo.coprop.api.AuthorizationChainTests.Result.PERMITIDO;
import static bo.coprop.api.AuthorizationChainTests.Result.PROHIBIDO;
import static bo.coprop.identity.MembershipRole.BOARD;
import static bo.coprop.identity.MembershipRole.CONDO_ADMIN;
import static bo.coprop.identity.MembershipRole.FINANCE_ADMIN;
import static bo.coprop.identity.MembershipRole.MAINTENANCE;
import static bo.coprop.identity.MembershipRole.OWNER;
import static bo.coprop.identity.MembershipRole.PLATFORM_ADMIN;
import static bo.coprop.identity.MembershipRole.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * La cadena de autorizacion completa del issue #15: <strong>cada endpoint, contra cada rol</strong>.
 *
 * <p>Es el segundo criterio del issue --"existe un test que recorre la matriz de roles y
 * endpoints"-- y recorre las dos dimensiones de verdad: los cuarenta y cinco endpoints de la API
 * por los siete roles de Seguridad 3, con el resultado esperado declarado celda a celda. Los cinco
 * de invitaciones entraron con el issue #18, los dos del catalogo de parametricas con el #89, la
 * edicion del condominio con el #90, los dos del catalogo de conceptos de cobro con el #100, los
 * dos de los periodos de facturacion con el #101, y asi entra cada endpoint nuevo.
 *
 * <p><strong>Cada endpoint declara los siete roles, no solo los que pueden.</strong> Es la leccion
 * que dejo el issue #16, donde el test que declaraba las columnas completas encontro que a
 * {@code CONDO_ADMIN} le faltaba un permiso: la mitad del valor de una matriz esta en los "--", y
 * una tabla que solo lista los "Sí" pasa igual el dia que alguien abra una celda de mas.
 *
 * <p>Lo que prueban los otros dos son cosas distintas y se rompen por separado:
 * {@code PermissionMatrixTests} prueba la decision sin levantar nada, y {@code ApiPermissionsTests}
 * que cada endpoint pregunte por el permiso que le toca, caso a caso y con su explicacion. Esto
 * prueba que no falte ninguna celda.
 *
 * <p>La otra mitad del issue --el primer criterio, que el propietario que pide lo de otro reciba
 * 404-- son los casos del final, porque no caben en la rejilla: hacen falta dos unidades y dos
 * personas, y lo que se comprueba es que la respuesta cambie con el recurso y no con el rol.
 */
@Import({TestcontainersConfiguration.class, TestTokenProvider.class, InvitationDoubles.class})
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorizationChainTests {

    /** Reparte un año-mes distinto a cada apertura de periodo. El porque esta en periodoNuevo(). */
    private static final AtomicInteger PERIODOS = new AtomicInteger();

    /** Lo que la cadena tiene que contestar, que son tres cosas y no dos. */
    enum Result {

        /**
         * Pasa la autorizacion. No se mira que estado concreto devuelve: que un alta repetida acabe
         * en 422 es asunto de su propio test, y atarlo aqui convertiria esta rejilla en una
         * bateria funcional de ciento cuarenta casos que se rompe cada vez que cambia un cuerpo.
         */
        PERMITIDO,

        /** 403: el rol no hace esta clase de cosas. No depende de que recurso se pidio. */
        PROHIBIDO,

        /**
         * 404: el rol si hace esta clase de cosas, pero este recurso no es suyo. Tiene que ser
         * indistinguible de uno que no existe, porque si no, quien pregunta puede enumerar el
         * edificio probando identificadores.
         */
        NOT_FOUND
    }

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
    private Invitations invitations;

    @Autowired
    private DataSource dataSource;

    private UUID condominium;
    private UUID block;

    /** La unidad con la que el propietario y el inquilino de la rejilla tienen relacion vigente. */
    private UUID unidadPropia;

    /** Otra unidad del MISMO condominio, con la que nadie de la rejilla tiene relacion. */
    private UUID unidadAjena;

    private UUID membresiaDelCondominio;
    private UUID personaSuelta;
    private UUID cuentaSuelta;
    private UUID personaSinCuenta;

    /** Una invitacion abierta, para reenviarla y revocarla desde la rejilla. */
    private UUID invitacionAbierta;

    /** Una cuenta sin invitacion ni membresia, para que la emision de la rejilla pueda emitir. */
    private UUID cuentaParaInvitar;

    /**
     * Las tres membresias que los endpoints del issue #19 van a tocar, una por operacion.
     *
     * <p>Una por operacion y no una compartida porque la rejilla <strong>escribe</strong>: si la
     * baja y la suspension cayeran sobre la misma fila, la segunda recibiria un 409 por el estado
     * que dejo la primera y la celda pasaria sin haber comprobado la autorizacion. La de reactivar
     * se deja suspendida de antemano por lo mismo, para no depender del orden de la lista.
     *
     * <p>Las tres llevan {@code OWNER}: asi la celda de {@code PLATFORM_ADMIN} mide lo que tiene que
     * medir --solo puede tocar roles administrativos-- y sale {@code PROHIBIDO}.
     */
    private UUID membresiaParaDarDeBaja;

    private UUID membresiaParaSuspender;
    private UUID membresiaParaReactivar;

    /**
     * Las tres piezas que el issue #20 necesita, una por operacion y por el mismo motivo que las
     * del #19: la rejilla <strong>escribe</strong>.
     *
     * <p>La membresia lleva {@code OWNER} para que la celda de {@code PLATFORM_ADMIN} mida lo que
     * tiene que medir --solo toca roles administrativos-- y la cuenta a la que se le concede el rol
     * de plataforma no es ninguna de las siete: si lo fuera, la celda siguiente la encontraria con
     * autoridades que no le corresponden.
     */
    private UUID membresiaParaCambiarRoles;

    private UUID cuentaParaLaPlataforma;
    private UUID cuentaConLaPlataformaPuesta;

    /**
     * Las relaciones del issue #19 se cierran sobre la unidad <em>ajena</em>, no la propia.
     *
     * <p>Son escrituras, y la unidad propia es la que sostiene las celdas "Propias" de media
     * rejilla: cerrarle una relacion desde aqui cambiaria el resultado de otra fila segun el orden
     * en que JUnit corriera los casos. En la ajena nadie de los siete tiene relacion, y el permiso
     * que gobierna estos endpoints es de condominio, asi que la celda mide lo mismo.
     */
    private UUID vendedorDeLaAjena;

    private UUID compradorDeLaAjena;
    private UUID ocupanteDeLaAjena;
    private UUID responsableDeLaAjena;

    /** Un token por rol, y la persona y la cuenta detras de el, para preguntar por lo propio. */
    private final Map<MembershipRole, Quien> porRol = new EnumMap<>(MembershipRole.class);

    private record Quien(UUID account, UUID person, String authorizationHeader) {}

    @BeforeAll
    void prepareTheBuildingAndOneAccountPerRole() {
        condominium = altaDeCondominio();
        block = units.addBlock(condominium, "Bloque A", "admin").id();
        unidadPropia = unitRegistration("CAD-PROPIA");
        unidadAjena = unitRegistration("CAD-AJENA");

        for (MembershipRole rol : MembershipRole.values()) {
            porRol.put(rol, conElRol(rol));
        }

        // El propietario y el inquilino de la rejilla tienen relacion vigente con la unidad propia:
        // sin eso, las celdas "Propias" darian 404 y la rejilla no distinguiria un OWN
        // resuelto de un NONE. Son dos de las tres relaciones; la tercera tiene su propio caso.
        relations.grantOwnership(condominium, unidadPropia, person(OWNER), new BigDecimal("50.000000"), "admin");
        relations.grantOccupancy(condominium, unidadPropia, person(TENANT), OccupancyRole.TENANT, "admin");

        // La membresia que se pide por identificador en la rejilla, de alguien que no es ninguno de
        // los siete: pedir la propia es otra pregunta y la contesta GET /me.
        membresiaDelCondominio = memberships
                .grant(condominium, cuentaNueva().id(), Set.of(OWNER), null, "admin")
                .id();

        PersonView suelta = people.register(new NewPerson("Ana", "Perez", null), "admin");
        personaSuelta = suelta.id();
        cuentaSuelta = people.openAccount(suelta.id(), TestTokenProvider.correoNuevo(), null, "admin")
                .id();

        // Una persona sin cuenta, para que el alta de cuenta de la rejilla no choque con la regla de
        // una cuenta por persona antes de llegar a la autorizacion.
        personaSinCuenta =
                people.register(new NewPerson("Luis", "Mamani", null), "admin").id();

        // Una invitacion abierta para reenviar y revocar, y una cuenta libre para emitir. Son dos
        // cuentas distintas a proposito: con una sola, la emision chocaria con la invitacion que ya
        // tiene abierta y la celda daria 422 por el estado del dato en vez de por la autorizacion.
        cuentaParaInvitar = cuentaNueva().id();
        invitacionAbierta = invitations
                .issue(
                        condominium,
                        cuentaNueva().id(),
                        Set.of(OWNER),
                        principal(CONDO_ADMIN).account().toString())
                .id();

        // Las tres del issue #19, una por operacion.
        membresiaParaDarDeBaja = memberships
                .grant(condominium, cuentaNueva().id(), Set.of(OWNER), null, "admin")
                .id();
        membresiaParaSuspender = memberships
                .grant(condominium, cuentaNueva().id(), Set.of(OWNER), null, "admin")
                .id();
        membresiaParaReactivar = memberships
                .grant(condominium, cuentaNueva().id(), Set.of(OWNER), null, "admin")
                .id();
        memberships.suspend(condominium, membresiaParaReactivar, "para que la rejilla la reactive", "admin");

        // Las tres del issue #20.
        membresiaParaCambiarRoles = memberships
                .grant(condominium, cuentaNueva().id(), Set.of(OWNER), null, "admin")
                .id();
        cuentaParaLaPlataforma = cuentaNueva().id();
        cuentaConLaPlataformaPuesta = cuentaNueva().id();
        // Sembrada por SQL, como la del rol de la rejilla: la revocacion necesita a alguien que YA
        // lo tenga, y si no, la celda del superadministrador daria 404 en vez de pasar.
        new JdbcTemplate(dataSource)
                .update(
                        "insert into platform_admin (user_id, granted_at) values (?, now())",
                        cuentaConLaPlataformaPuesta);

        // Y las relaciones de la unidad ajena que los cierres del #19 van a cerrar.
        vendedorDeLaAjena =
                people.register(new NewPerson("Vende", "Ajena", null), "admin").id();
        compradorDeLaAjena =
                people.register(new NewPerson("Compra", "Ajena", null), "admin").id();
        ocupanteDeLaAjena =
                people.register(new NewPerson("Ocupa", "Ajena", null), "admin").id();
        responsableDeLaAjena =
                people.register(new NewPerson("Paga", "Ajena", null), "admin").id();
        relations.grantOwnership(condominium, unidadAjena, vendedorDeLaAjena, new BigDecimal("100.000000"), "admin");
        relations.grantOccupancy(condominium, unidadAjena, ocupanteDeLaAjena, OccupancyRole.TENANT, "admin");
        relations.assignBilling(condominium, unidadAjena, responsableDeLaAjena, null, "admin");
    }

    private UUID altaDeCondominio() {
        return condominiums
                .register(
                        new NewCondominium(
                                "Cadena " + UUID.randomUUID(),
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

    private UUID unitRegistration(String code) {
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
     * Una cuenta que entra con ese rol.
     *
     * <p>{@code PLATFORM_ADMIN} es el caso aparte: su alcance es la plataforma, vive en su propia
     * tabla y <strong>concederlo como membresia esta prohibido</strong> --tiene su test en
     * {@code ApiPermissionsTests}--, asi que aqui se siembra por SQL. Entra sin membresia en este
     * condominio, que es justo lo que el filtro del #17 le permite.
     */
    private Quien conElRol(MembershipRole rol) {
        PersonView person = people.register(new NewPerson("Rol", rol.name(), null), "admin");
        AccountView account = people.openAccount(person.id(), TestTokenProvider.correoNuevo(), null, "admin");
        if (rol == PLATFORM_ADMIN) {
            new JdbcTemplate(dataSource)
                    .update("insert into platform_admin (user_id, granted_at) values (?, now())", account.id());
        } else {
            memberships.grant(condominium, account.id(), Set.of(rol), null, "admin");
        }
        String token = TestTokenProvider.valid(TestTokenProvider.sujetoNuevo(), account.email());
        return new Quien(account.id(), person.id(), "Bearer " + token);
    }

    private UUID person(MembershipRole rol) {
        return principal(rol).person();
    }

    private Quien principal(MembershipRole rol) {
        return java.util.Objects.requireNonNull(porRol.get(rol));
    }

    // --- La rejilla -------------------------------------------------------------------------

    /**
     * Un endpoint y lo que la cadena tiene que contestarle a cada rol.
     *
     * <p>La ruta se escribe ya formateada: la rejilla pregunta siempre por los mismos recursos, al
     * contrario que la suite de fuga, que corre la misma plantilla contra dos condominios.
     */
    private record Endpoint(
            String name,
            HttpMethod metodo,
            String path,
            @Nullable String cuerpo,
            Map<MembershipRole, Result> expected) {}

    /**
     * La tabla, una fila por endpoint.
     *
     * <p>Lo que no se declara es {@link Resultado#PROHIBIDO}: es el "--" de la matriz, y escribirlo
     * siete veces por fila taparia lo que cada endpoint si permite. Lo que no se puede omitir son
     * los {@link Resultado#NOT_FOUND}, porque son los que distinguen un permiso de alcance
     * propio de uno que no existe.
     */
    private List<Endpoint> endpoints() {
        String enElCondominio = "/api/v1/condominiums/" + condominium;
        String theUnit = enElCondominio + "/units/" + unidadPropia;
        String laUnidadAjena = enElCondominio + "/units/" + unidadAjena;
        return List.of(
                // Verlo solo pide pertenecer, y eso ya lo exigio el filtro del #17. Los siete.
                new Endpoint("consultar el condominio", HttpMethod.GET, enElCondominio, null, todos()),
                new Endpoint("mi perfil", HttpMethod.GET, "/api/v1/me", null, todos()),
                // Las propias sesiones y su cierre: lo que devuelven lo decide la autenticacion, no
                // un rol, asi que los siete. Van en la rejilla igual que /me --y no porque haya una
                // celda que discutir-- para que un @PreAuthorize puesto aqui por error salga aqui.
                new Endpoint("mis sesiones", HttpMethod.GET, "/api/v1/me/sessions", null, todos()),
                new Endpoint(
                        "cerrar todas mis sesiones", HttpMethod.POST, "/api/v1/me/sessions/closure", null, todos()),

                // Las parametricas y la matriz expuesta: lo que devuelven es la forma de la API y no
                // datos de nadie, asi que los siete. Entran en la rejilla igual que /me --y no
                // porque haya una celda que discutir-- para que un @PreAuthorize puesto aqui por
                // error salga en esta tabla y no en produccion.
                new Endpoint("el catalogo de parametricas", HttpMethod.GET, "/api/v1/catalogs", null, todos()),
                new Endpoint(
                        "la matriz de permisos expuesta",
                        HttpMethod.GET,
                        "/api/v1/catalogs/permission-matrix",
                        null,
                        todos()),

                // Administracion de unidades y personas: solo el administrador del condominio.
                new Endpoint(
                        "dar de alta una unidad",
                        HttpMethod.POST,
                        enElCondominio + "/units",
                        unidadConCodigo(),
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "alta masiva de unidades",
                        HttpMethod.POST,
                        enElCondominio + "/units/batch",
                        "{\"units\":[" + unidadConCodigo() + "]}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "dar de alta un bloque",
                        HttpMethod.POST,
                        enElCondominio + "/blocks",
                        "{\"name\":\"Bloque " + UUID.randomUUID() + "\"}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "dar de alta un piso",
                        HttpMethod.POST,
                        enElCondominio + "/blocks/" + block + "/floors",
                        "{\"number\":3}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "registrar un propietario",
                        HttpMethod.POST,
                        theUnit + "/owners",
                        "{\"personId\":\"" + personaSuelta + "\",\"share\":10.0}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "registrar un ocupante",
                        HttpMethod.POST,
                        theUnit + "/occupants",
                        "{\"personId\":\"" + personaSuelta + "\",\"role\":\"TENANT\"}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "registrar un responsable de pago",
                        HttpMethod.POST,
                        theUnit + "/billing-responsibles",
                        "{\"personId\":\"" + personaSuelta + "\"}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "dar de alta una persona",
                        HttpMethod.POST,
                        "/api/v1/people",
                        "{\"firstNames\":\"Nueva\",\"lastNames\":\"Persona\"}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "abrir una cuenta",
                        HttpMethod.POST,
                        "/api/v1/people/" + personaSinCuenta + "/account",
                        "{\"email\":\"" + TestTokenProvider.correoNuevo() + "\"}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "consultar una persona ajena",
                        HttpMethod.GET,
                        "/api/v1/people/" + personaSuelta,
                        null,
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "consultar una cuenta ajena",
                        HttpMethod.GET,
                        "/api/v1/accounts/" + cuentaSuelta,
                        null,
                        soloPara(CONDO_ADMIN)),

                // El padron del edificio: quien reparte los roles. La propia membresia la da /me.
                new Endpoint(
                        "listar las membresias",
                        HttpMethod.GET,
                        enElCondominio + "/memberships",
                        null,
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "consultar una membresia",
                        HttpMethod.GET,
                        enElCondominio + "/memberships/" + membresiaDelCondominio,
                        null,
                        soloPara(CONDO_ADMIN)),

                // Invitaciones (issue #18). Emitir pide poder conceder esos roles, igual que el alta
                // directa de membresia: es lo mismo diferido. El resto son operaciones sobre el
                // padron del edificio, y el superadministrador no lo opera (Seguridad 10.1).
                new Endpoint(
                        "emitir una invitacion",
                        HttpMethod.POST,
                        enElCondominio + "/invitations",
                        "{\"accountId\":\"" + cuentaParaInvitar + "\",\"roles\":[\"OWNER\"]}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "listar las invitaciones",
                        HttpMethod.GET,
                        enElCondominio + "/invitations",
                        null,
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "consultar una invitacion",
                        HttpMethod.GET,
                        enElCondominio + "/invitations/" + invitacionAbierta,
                        null,
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "reenviar una invitacion",
                        HttpMethod.POST,
                        enElCondominio + "/invitations/" + invitacionAbierta + "/resend",
                        null,
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "revocar una invitacion",
                        HttpMethod.POST,
                        enElCondominio + "/invitations/" + invitacionAbierta + "/revocation",
                        null,
                        soloPara(CONDO_ADMIN)),

                // Conceder: el administrador cualquiera, el superadministrador solo administrativos,
                // y estos roles no lo son. Es la celda "Solo administradores" vista por su limite.
                new Endpoint(
                        "conceder una membresia",
                        HttpMethod.POST,
                        enElCondominio + "/memberships",
                        "{\"accountId\":\"" + cuentaSuelta + "\",\"roles\":[\"OWNER\"]}",
                        soloPara(CONDO_ADMIN)),

                // La bitacora (issue #21). La matriz da VIEW_AUDIT_LOG a los dos
                // administradores del condominio y al superadministrador --que no opera, pero audita
                // (Seguridad 10.1)--, y a nadie mas: dice que hizo cada vecino.
                new Endpoint(
                        "consultar la auditoria",
                        HttpMethod.GET,
                        enElCondominio + "/audit-log",
                        null,
                        tabla(Map.of(
                                PLATFORM_ADMIN, PERMITIDO,
                                CONDO_ADMIN, PERMITIDO,
                                FINANCE_ADMIN, PERMITIDO))),

                // Corregir la ficha del condominio es del que lo dio de alta, y de nadie mas: el
                // administrador del condominio administra lo que hay dentro, no la ficha.
                new Endpoint(
                        "editar el condominio",
                        HttpMethod.PUT,
                        enElCondominio,
                        edicionDelCondominio(),
                        soloPara(PLATFORM_ADMIN)),

                // Lo que se cobra es del que maneja el dinero, y de nadie mas.
                new Endpoint(
                        "cambiar la configuracion",
                        HttpMethod.PUT,
                        enElCondominio + "/configuration",
                        "{\"issueDay\":5,\"dueDay\":10}",
                        soloPara(FINANCE_ADMIN)),

                // El catalogo de conceptos de cobro (issue #100), las dos operaciones. El listado va
                // tambien por CONFIGURE_CHARGES: el catalogo dice que cobra la administracion, y
                // cuando una pantalla de propietario necesite los nombres sera su respuesta la que
                // los traiga, no este endpoint.
                new Endpoint(
                        "dar de alta un concepto de cobro",
                        HttpMethod.POST,
                        enElCondominio + "/charge-concepts",
                        conceptoConCodigo(),
                        soloPara(FINANCE_ADMIN)),
                new Endpoint(
                        "listar los conceptos de cobro",
                        HttpMethod.GET,
                        enElCondominio + "/charge-concepts",
                        null,
                        soloPara(FINANCE_ADMIN)),

                // Los periodos de facturacion (issue #101), tambien las dos por
                // CONFIGURE_CHARGES: preparar el mes que se va a cobrar es del que maneja el
                // dinero. Emitir y cerrar no estan aqui porque no tienen ruta --viven en el
                // dominio y las llamara el #24--.
                new Endpoint(
                        "abrir un periodo de facturacion",
                        HttpMethod.POST,
                        enElCondominio + "/billing-periods",
                        periodoNuevo(),
                        soloPara(FINANCE_ADMIN)),
                new Endpoint(
                        "listar los periodos de facturacion",
                        HttpMethod.GET,
                        enElCondominio + "/billing-periods",
                        null,
                        soloPara(FINANCE_ADMIN)),

                // Crear condominios es del superadministrador.
                new Endpoint(
                        "dar de alta un condominio",
                        HttpMethod.POST,
                        "/api/v1/condominiums",
                        altaDeCondominioJson(),
                        soloPara(PLATFORM_ADMIN)),

                // La ficha de la unidad: la matriz no tiene fila para esto, asi que la ve quien
                // administra unidades, y cualquier otro solo la suya. Quien no cumple ni una cosa
                // ni la otra recibe 404, no 403: la respuesta depende de que unidad pidio.
                new Endpoint(
                        "consultar la unidad propia",
                        HttpMethod.GET,
                        theUnit,
                        null,
                        tabla(Map.of(
                                CONDO_ADMIN, PERMITIDO,
                                OWNER, PERMITIDO,
                                TENANT, PERMITIDO,
                                PLATFORM_ADMIN, NOT_FOUND,
                                FINANCE_ADMIN, NOT_FOUND,
                                MAINTENANCE, NOT_FOUND,
                                BOARD, NOT_FOUND))),

                // Los propietarios de una unidad van por VIEW_ACCOUNT_STATEMENT, que la matriz da
                // entera a los dos administradores y "Propias" al propietario y al inquilino. Aqui
                // se ven las tres salidas de la cadena en una sola fila.
                new Endpoint(
                        "listar los propietarios de la unidad propia",
                        HttpMethod.GET,
                        theUnit + "/owners",
                        null,
                        tabla(Map.of(
                                CONDO_ADMIN, PERMITIDO,
                                FINANCE_ADMIN, PERMITIDO,
                                OWNER, PERMITIDO,
                                TENANT, PERMITIDO))),

                // Bajas y suspensiones (issue #19). Piden poder conceder los roles que la membresia
                // tiene, que es el alta leida al reves: el administrador del condominio puede con
                // cualquiera y el superadministrador solo con roles administrativos, y estas tres
                // llevan OWNER. De ahi que su celda sea PROHIBIDO y no PERMITIDO.
                new Endpoint(
                        "dar de baja una membresia",
                        HttpMethod.POST,
                        enElCondominio + "/memberships/" + membresiaParaDarDeBaja + "/termination",
                        reason(),
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "suspender una membresia",
                        HttpMethod.POST,
                        enElCondominio + "/memberships/" + membresiaParaSuspender + "/suspension",
                        reason(),
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "reactivar una membresia",
                        HttpMethod.POST,
                        enElCondominio + "/memberships/" + membresiaParaReactivar + "/reactivation",
                        reason(),
                        soloPara(CONDO_ADMIN)),

                // El cambio de roles (issue #20). Pide las DOS mitades: poder quitar los que tiene
                // --OWNER, y de ahi que el superadministrador salga PROHIBIDO-- y poder poner los que
                // llegan. El financiero no reparte roles, que es la otra mitad de la separacion de
                // funciones.
                new Endpoint(
                        "cambiar los roles de una membresia",
                        HttpMethod.PUT,
                        enElCondominio + "/memberships/" + membresiaParaCambiarRoles + "/roles",
                        "{\"roles\":[\"TENANT\"],\"reason\":\"lo decide la rejilla de authorizationHeader\"}",
                        soloPara(CONDO_ADMIN)),

                // El rol de plataforma y la bitacora de plataforma (issue #20). Las tres rutas son
                // GLOBALES, asi que el filtro del #17 no corre y las unicas autoridades de la
                // peticion son las de plataforma: los seis roles de condominio llegan sin ninguna y
                // reciben 403 por la forma de la ruta, no por una regla escrita aparte.
                new Endpoint(
                        "listar los superadministradores",
                        HttpMethod.GET,
                        "/api/v1/platform-admins",
                        null,
                        soloPara(PLATFORM_ADMIN)),
                new Endpoint(
                        "conceder el rol de plataforma",
                        HttpMethod.POST,
                        "/api/v1/platform-admins",
                        "{\"accountId\":\"" + cuentaParaLaPlataforma + "\"}",
                        soloPara(PLATFORM_ADMIN)),
                new Endpoint(
                        "revocar el rol de plataforma",
                        HttpMethod.POST,
                        "/api/v1/platform-admins/" + cuentaConLaPlataformaPuesta + "/revocation",
                        reason(),
                        soloPara(PLATFORM_ADMIN)),
                new Endpoint(
                        "consultar la auditoria de plataforma",
                        HttpMethod.GET,
                        "/api/v1/audit-log",
                        null,
                        soloPara(PLATFORM_ADMIN)),

                // Y los cierres de relacion del mismo issue, que son administracion de unidades como
                // sus altas. El historico de propietarios va por ahi y no por VIEW_ACCOUNT_STATEMENT
                // como el listado de hoy: Seguridad 6.2 deja el comprobante de la epoca del vendedor
                // en manos del administrador.
                new Endpoint(
                        "ver el historico de propietarios",
                        HttpMethod.GET,
                        theUnit + "/owners/history",
                        null,
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "transferir una unidad",
                        HttpMethod.POST,
                        laUnidadAjena + "/transfer",
                        "{\"fromPersonId\":\"" + vendedorDeLaAjena + "\",\"toPersonId\":\"" + compradorDeLaAjena
                                + "\"}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "terminar una ocupacion",
                        HttpMethod.DELETE,
                        laUnidadAjena + "/occupants/" + ocupanteDeLaAjena,
                        null,
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "terminar una responsabilidad de pago",
                        HttpMethod.DELETE,
                        laUnidadAjena + "/billing-responsibles/" + responsableDeLaAjena,
                        null,
                        soloPara(CONDO_ADMIN)));
    }

    /** El motivo que piden la baja, la suspension y la reactivacion. Con el minimo de caracteres. */
    private static String reason() {
        return "{\"reason\":\"lo decide la rejilla de authorizationHeader\"}";
    }

    /**
     * Un codigo distinto por peticion, y <strong>valido</strong>: la rejilla llama al alta siete
     * veces.
     *
     * <p>Que sea valido no es un detalle. <strong>La validacion del cuerpo corre ANTES de la
     * autorizacion</strong>, porque Spring resuelve los argumentos del metodo --y con ellos el
     * {@code @Valid}-- antes de invocarlo, y {@code @PreAuthorize} es un consejo alrededor de esa
     * invocacion. Un codigo de mas de 32 caracteres hacia que los seis roles sin permiso recibieran
     * 422 en vez de 403, y con eso la celda no probaba la autorizacion sino la longitud del codigo.
     * Pasó al escribir esta rejilla.
     */
    /**
     * El cuerpo de la edicion, con el NIT que el condominio YA tiene.
     *
     * <p>Por dos razones. El NIT es unico y estos tests comparten base con los demas, asi que
     * inventarlo podria chocar; y mandando el mismo, la celda no depende de que el token de prueba
     * traiga segundo factor --si algun dia deja de traerlo, esta fila seguira midiendo la
     * autorizacion y no el control de reautenticacion--.
     *
     * <p>Va en un bloque de texto para no escapar las comillas.
     */
    private String edicionDelCondominio() {
        return """
                {"name": "Cadena editada", "nit": "%s", "address": "Av. Ballivian 123"}"""
                .formatted(condominiums.find(condominium).nit());
    }

    private static String unidadConCodigo() {
        String sufijo = UUID.randomUUID().toString().substring(0, 8);
        return "{\"code\":\"R-" + sufijo + "\",\"type\":\"APARTMENT\",\"aliquot\":\"1.000000\"}";
    }

    /**
     * Un concepto de cobro con codigo distinto cada vez.
     *
     * <p>Hace falta por lo mismo que en la unidad: la rejilla da de alta una vez por rol permitido y
     * el codigo es unico en el condominio, asi que un codigo fijo convertiria el segundo alta en un
     * 409 y la celda pareceria una negativa de autorizacion.
     */
    /**
     * Un periodo de facturacion distinto cada vez.
     *
     * <p>Por lo mismo que el concepto: el par año-mes es unico en el condominio, asi que un mes
     * fijo convertiria una segunda apertura en un 409 y la celda pareceria una negativa de
     * autorizacion. Un contador y no un azar porque el año esta acotado entre 2000 y 2100 y un
     * numero al azar puede repetirse; asi no se repite nunca dentro de la misma ejecucion.
     */
    private static String periodoNuevo() {
        int n = PERIODOS.getAndIncrement();
        return "{\"year\":" + (2000 + n / 12) + ",\"month\":" + (1 + n % 12) + "}";
    }

    private static String conceptoConCodigo() {
        String sufijo = UUID.randomUUID().toString().substring(0, 8);
        return "{\"code\":\"C-" + sufijo + "\",\"name\":\"Expensas\",\"type\":\"DUES\",\"paymentGroup\":\"DUES\"}";
    }

    private static String altaDeCondominioJson() {
        return "{\"name\":\"Torre " + UUID.randomUUID() + "\",\"nit\":\"" + nitNuevo()
                + "\",\"type\":\"BUILDING\",\"timeZone\":\"America/La_Paz\",\"issueDay\":5,\"dueDay\":10}";
    }

    private static Map<MembershipRole, Result> todos() {
        var completa = new EnumMap<MembershipRole, Result>(MembershipRole.class);
        for (MembershipRole rol : MembershipRole.values()) {
            completa.put(rol, PERMITIDO);
        }
        return completa;
    }

    private static Map<MembershipRole, Result> soloPara(MembershipRole unico) {
        return tabla(Map.of(unico, PERMITIDO));
    }

    /** Rellena con {@link Resultado#PROHIBIDO} los roles que la fila no nombra. */
    private static Map<MembershipRole, Result> tabla(Map<MembershipRole, Result> declared) {
        var completa = new EnumMap<MembershipRole, Result>(MembershipRole.class);
        for (MembershipRole rol : MembershipRole.values()) {
            completa.put(rol, declared.getOrDefault(rol, PROHIBIDO));
        }
        return completa;
    }

    /** Un caso por celda: endpoint, rol y lo que tiene que contestar. */
    private List<Celda> celdas() {
        var casos = new ArrayList<Celda>();
        for (Endpoint endpoint : endpoints()) {
            for (MembershipRole rol : MembershipRole.values()) {
                casos.add(new Celda(
                        endpoint,
                        rol,
                        java.util.Objects.requireNonNull(endpoint.expected().get(rol))));
            }
        }
        return casos;
    }

    private record Celda(Endpoint endpoint, MembershipRole rol, Result expected) {

        @Override
        public String toString() {
            return "%s con %s da %s".formatted(endpoint.name(), rol, expected);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("celdas")
    @DisplayName("la rejilla entera: los cuarenta y cinco endpoints por los siete roles")
    void theWholeGrid(Celda celda) throws Exception {
        var result = mockMvc.perform(
                authorizedRequest(celda.endpoint(), principal(celda.rol()).authorizationHeader()));

        switch (celda.expected()) {
            case PERMITIDO -> result.andExpect(estadoQueNoSea(403, 404, celda));
            case PROHIBIDO ->
                result.andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("NO_PERMISSION"));
            case NOT_FOUND ->
                result.andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    /**
     * Para los permitidos se comprueba que no sea ninguna de las dos negativas, no un 200 exacto.
     *
     * <p>Un alta repetida acaba en 422 y eso esta bien: lo que esta rejilla decide es si la cadena
     * dejo pasar la peticion. El mensaje dice el caso entero, porque con ciento cuarenta celdas un
     * fallo sin nombre obliga a contar filas.
     */
    private static org.springframework.test.web.servlet.ResultMatcher estadoQueNoSea(
            int aStatus, int otro, Celda celda) {
        return result -> {
            int status = result.getResponse().getStatus();
            if (status == aStatus || status == otro) {
                throw new AssertionError("La celda '%s' respondio %d.".formatted(celda, status));
            }
        };
    }

    private MockHttpServletRequestBuilder authorizedRequest(Endpoint endpoint, String authorizationHeader) {
        var constructor = request(endpoint.metodo(), endpoint.path())
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader);
        String cuerpo = endpoint.cuerpo();
        if (cuerpo != null) {
            constructor.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        }
        return constructor;
    }

    // --- El primer criterio: un recurso que no es tuyo no existe ----------------------------

    @Test
    @DisplayName("el propietario ve su unidad y la del vecino le responde 404")
    void theOwnerOnlySeesTheirUnit() throws Exception {
        // Es el criterio del issue, con el estado de cuenta sustituido por lo que hay en M1: las
        // dos unidades son del MISMO condominio y el propietario tiene membresia activa en el, asi
        // que el 404 no lo pone el filtro del #17 sino la relacion con el recurso. Es la unica
        // forma de probar este eslabon por separado.
        String owner = principal(OWNER).authorizationHeader();

        mockMvc.perform(authorizedRequest(theUnit(unidadPropia), owner)).andExpect(status().isOk());

        mockMvc.perform(authorizedRequest(theUnit(unidadAjena), owner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("al administrador del condominio las dos unidades le responden igual")
    void theAdminSeesBoth() throws Exception {
        // El control del caso anterior. Sin esto, una unidad mal sembrada daria 404 a todo el mundo
        // y el 404 del propietario no probaria nada.
        String administrador = principal(CONDO_ADMIN).authorizationHeader();

        mockMvc.perform(authorizedRequest(theUnit(unidadPropia), administrador)).andExpect(status().isOk());
        mockMvc.perform(authorizedRequest(theUnit(unidadAjena), administrador)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("sobre la unidad ajena el alcance propio da 404 y el rol sin permiso da 403")
    void theRefusalDistinguishesTheRoleFromTheResource() throws Exception {
        // Las dos negativas sobre el MISMO endpoint y el MISMO recurso, que es donde se ve que no
        // son intercambiables: a quien le falta el permiso se le dice que le falta, y a quien lo
        // tiene sobre lo suyo se le dice que esto no existe. Al reves, el 403 del propietario
        // confirmaria que esa unidad existe y con eso se enumera el edificio.
        String path = "/api/v1/condominiums/" + condominium + "/units/" + unidadAjena + "/owners";

        mockMvc.perform(request(HttpMethod.GET, path)
                        .header(HttpHeaders.AUTHORIZATION, principal(OWNER).authorizationHeader()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(request(HttpMethod.GET, path)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                principal(MAINTENANCE).authorizationHeader()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_PERMISSION"));
    }

    @Test
    @DisplayName("responder por los cobros de una unidad tambien es tenerla")
    void theBillingResponsibleAlsoHasIt() throws Exception {
        // La tercera relacion, que es la que se olvida: el propietario que alquilo ya no ocupa, y
        // si solo contara la ocupacion se quedaria sin ver la unidad por la que sigue pagando.
        // Seguridad 7.2 la nombra explicitamente --"inquilino responsable vigente del servicio"--.
        AccountView account = cuentaNueva();
        UUID person = account.personId();
        memberships.grant(condominium, account.id(), Set.of(TENANT), null, "admin");
        String token = "Bearer " + TestTokenProvider.valid(TestTokenProvider.sujetoNuevo(), account.email());

        mockMvc.perform(authorizedRequest(theUnit(unidadAjena), token)).andExpect(status().isNotFound());

        relations.assignBilling(condominium, unidadAjena, person, null, "admin");

        mockMvc.perform(authorizedRequest(theUnit(unidadAjena), token)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("cada uno ve su propia persona y su propia cuenta sin administrar nada")
    void yourOwnIsVisibleWithoutManagingAnything() throws Exception {
        // Un propietario no administra ningun condominio, asi que por la matriz no le toca ninguna
        // de las dos consultas. Lo propio es la excepcion, y hace falta: sin ella la cabecera
        // Location del alta de su cuenta apuntaria a una ruta que a el le responde 403.
        Quien owner = principal(OWNER);

        mockMvc.perform(request(HttpMethod.GET, "/api/v1/people/" + owner.person())
                        .header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(owner.person().toString()));

        mockMvc.perform(request(HttpMethod.GET, "/api/v1/accounts/" + owner.account())
                        .header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(owner.account().toString()));
    }

    @Test
    @DisplayName("la relacion con la unidad no se hereda entre condominios")
    void theRelationDoesNotCrossCondominiums() throws Exception {
        // El propietario de la unidad de un edificio pide, en otro edificio donde tambien es
        // miembro, una unidad con la que no tiene relacion. La consulta de la relacion lleva el
        // condominio, asi que no empareja; si no lo llevara, bastaria ser propietario en cualquier
        // parte para ver cualquier unidad del mismo identificador.
        UUID otro = altaDeCondominio();
        AccountView account = cuentaNueva();
        UUID person = account.personId();
        memberships.grant(condominium, account.id(), Set.of(OWNER), null, "admin");
        memberships.grant(otro, account.id(), Set.of(OWNER), null, "admin");
        relations.grantOwnership(condominium, unidadAjena, person, new BigDecimal("10.000000"), "admin");

        UUID unidadDelOtro = units.register(
                        otro,
                        new NewUnit("OTRO-1", UnitType.APARTMENT, new BigDecimal("1.000000"), null, null, null),
                        "admin")
                .id();
        String token = "Bearer " + TestTokenProvider.valid(TestTokenProvider.sujetoNuevo(), account.email());

        mockMvc.perform(request(HttpMethod.GET, "/api/v1/condominiums/" + condominium + "/units/" + unidadAjena)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());

        mockMvc.perform(request(HttpMethod.GET, "/api/v1/condominiums/" + otro + "/units/" + unidadDelOtro)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("la rejilla declara los siete roles en cada endpoint")
    void theGridLeavesNoCellUndeclared() {
        // El guardian de este test. Una fila que se olvide de un rol lo dejaria sin probar y la
        // rejilla pasaria igual; es el mismo descuido que en el #16 dejo un permiso de menos en
        // CONDO_ADMIN, y lo cazo justo el test que exigia las columnas completas.
        for (Endpoint endpoint : endpoints()) {
            assertThat(endpoint.expected())
                    .as("el endpoint '%s' no declara los siete roles", endpoint.name())
                    .containsOnlyKeys(MembershipRole.values());
        }
    }

    private Endpoint theUnit(UUID unit) {
        return new Endpoint(
                "consultar una unidad",
                HttpMethod.GET,
                "/api/v1/condominiums/" + condominium + "/units/" + unit,
                null,
                todos());
    }
}
