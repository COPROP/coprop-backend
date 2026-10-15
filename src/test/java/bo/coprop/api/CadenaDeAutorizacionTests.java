package bo.coprop.api;

import static bo.coprop.api.CadenaDeAutorizacionTests.Resultado.NO_ENCONTRADO;
import static bo.coprop.api.CadenaDeAutorizacionTests.Resultado.PERMITIDO;
import static bo.coprop.api.CadenaDeAutorizacionTests.Resultado.PROHIBIDO;
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
import bo.coprop.identity.DoblesDeInvitacion;
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
 * endpoints"-- y recorre las dos dimensiones de verdad: los treinta y tres endpoints de la API por los
 * siete roles de Seguridad 3, con el resultado esperado declarado celda a celda. Los cinco de
 * invitaciones entraron con el issue #18, y asi entra cada endpoint nuevo.
 *
 * <p><strong>Cada endpoint declara los siete roles, no solo los que pueden.</strong> Es la leccion
 * que dejo el issue #16, donde el test que declaraba las columnas completas encontro que a
 * {@code CONDO_ADMIN} le faltaba un permiso: la mitad del valor de una matriz esta en los "--", y
 * una tabla que solo lista los "Sí" pasa igual el dia que alguien abra una celda de mas.
 *
 * <p>Lo que prueban los otros dos son cosas distintas y se rompen por separado:
 * {@code MatrizDePermisosTests} prueba la decision sin levantar nada, y {@code PermisosDeApiTests}
 * que cada endpoint pregunte por el permiso que le toca, caso a caso y con su explicacion. Esto
 * prueba que no falte ninguna celda.
 *
 * <p>La otra mitad del issue --el primer criterio, que el propietario que pide lo de otro reciba
 * 404-- son los casos del final, porque no caben en la rejilla: hacen falta dos unidades y dos
 * personas, y lo que se comprueba es que la respuesta cambie con el recurso y no con el rol.
 */
@Import({TestcontainersConfiguration.class, ProveedorDeTokensDePrueba.class, DoblesDeInvitacion.class})
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CadenaDeAutorizacionTests {

    /** Lo que la cadena tiene que contestar, que son tres cosas y no dos. */
    enum Resultado {

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
        NO_ENCONTRADO
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Condominiums condominios;

    @Autowired
    private Units unidades;

    @Autowired
    private UnitRelations relaciones;

    @Autowired
    private People personas;

    @Autowired
    private Memberships membresias;

    @Autowired
    private Invitations invitaciones;

    @Autowired
    private DataSource dataSource;

    private UUID condominio;
    private UUID bloque;

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

    private record Quien(UUID cuenta, UUID persona, String autorizacion) {}

    @BeforeAll
    void prepararElEdificioYUnaCuentaPorRol() {
        condominio = altaDeCondominio();
        bloque = unidades.addBlock(condominio, "Bloque A", "admin").id();
        unidadPropia = altaDeUnidad("CAD-PROPIA");
        unidadAjena = altaDeUnidad("CAD-AJENA");

        for (MembershipRole rol : MembershipRole.values()) {
            porRol.put(rol, conElRol(rol));
        }

        // El propietario y el inquilino de la rejilla tienen relacion vigente con la unidad propia:
        // sin eso, las celdas "Propias" darian 404 y la rejilla no distinguiria un PROPIO
        // resuelto de un NINGUNO. Son dos de las tres relaciones; la tercera tiene su propio caso.
        relaciones.grantOwnership(condominio, unidadPropia, persona(OWNER), new BigDecimal("50.000000"), "admin");
        relaciones.grantOccupancy(condominio, unidadPropia, persona(TENANT), OccupancyRole.INQUILINO, "admin");

        // La membresia que se pide por identificador en la rejilla, de alguien que no es ninguno de
        // los siete: pedir la propia es otra pregunta y la contesta GET /me.
        membresiaDelCondominio = membresias
                .grant(condominio, cuentaNueva().id(), Set.of(OWNER), null, "admin")
                .id();

        PersonView suelta = personas.register(new NewPerson("Ana", "Perez", null), "admin");
        personaSuelta = suelta.id();
        cuentaSuelta = personas.openAccount(suelta.id(), ProveedorDeTokensDePrueba.correoNuevo(), null, "admin")
                .id();

        // Una persona sin cuenta, para que el alta de cuenta de la rejilla no choque con la regla de
        // una cuenta por persona antes de llegar a la autorizacion.
        personaSinCuenta = personas.register(new NewPerson("Luis", "Mamani", null), "admin")
                .id();

        // Una invitacion abierta para reenviar y revocar, y una cuenta libre para emitir. Son dos
        // cuentas distintas a proposito: con una sola, la emision chocaria con la invitacion que ya
        // tiene abierta y la celda daria 422 por el estado del dato en vez de por la autorizacion.
        cuentaParaInvitar = cuentaNueva().id();
        invitacionAbierta = invitaciones
                .issue(
                        condominio,
                        cuentaNueva().id(),
                        Set.of(OWNER),
                        quien(CONDO_ADMIN).cuenta().toString())
                .id();

        // Las tres del issue #19, una por operacion.
        membresiaParaDarDeBaja = membresias
                .grant(condominio, cuentaNueva().id(), Set.of(OWNER), null, "admin")
                .id();
        membresiaParaSuspender = membresias
                .grant(condominio, cuentaNueva().id(), Set.of(OWNER), null, "admin")
                .id();
        membresiaParaReactivar = membresias
                .grant(condominio, cuentaNueva().id(), Set.of(OWNER), null, "admin")
                .id();
        membresias.suspend(condominio, membresiaParaReactivar, "para que la rejilla la reactive", "admin");

        // Y las relaciones de la unidad ajena que los cierres del #19 van a cerrar.
        vendedorDeLaAjena = personas.register(new NewPerson("Vende", "Ajena", null), "admin")
                .id();
        compradorDeLaAjena = personas.register(new NewPerson("Compra", "Ajena", null), "admin")
                .id();
        ocupanteDeLaAjena = personas.register(new NewPerson("Ocupa", "Ajena", null), "admin")
                .id();
        responsableDeLaAjena =
                personas.register(new NewPerson("Paga", "Ajena", null), "admin").id();
        relaciones.grantOwnership(condominio, unidadAjena, vendedorDeLaAjena, new BigDecimal("100.000000"), "admin");
        relaciones.grantOccupancy(condominio, unidadAjena, ocupanteDeLaAjena, OccupancyRole.INQUILINO, "admin");
        relaciones.assignBilling(condominio, unidadAjena, responsableDeLaAjena, null, "admin");
    }

    private UUID altaDeCondominio() {
        return condominios
                .register(
                        new NewCondominium(
                                "Cadena " + UUID.randomUUID(),
                                nitNuevo(),
                                CondominiumType.EDIFICIO,
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

    private UUID altaDeUnidad(String codigo) {
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
     * Una cuenta que entra con ese rol.
     *
     * <p>{@code PLATFORM_ADMIN} es el caso aparte: su alcance es la plataforma, vive en su propia
     * tabla y <strong>concederlo como membresia esta prohibido</strong> --tiene su test en
     * {@code PermisosDeApiTests}--, asi que aqui se siembra por SQL. Entra sin membresia en este
     * condominio, que es justo lo que el filtro del #17 le permite.
     */
    private Quien conElRol(MembershipRole rol) {
        PersonView persona = personas.register(new NewPerson("Rol", rol.name(), null), "admin");
        AccountView cuenta = personas.openAccount(persona.id(), ProveedorDeTokensDePrueba.correoNuevo(), null, "admin");
        if (rol == PLATFORM_ADMIN) {
            new JdbcTemplate(dataSource)
                    .update("insert into platform_admin (user_id, granted_at) values (?, now())", cuenta.id());
        } else {
            membresias.grant(condominio, cuenta.id(), Set.of(rol), null, "admin");
        }
        String token = ProveedorDeTokensDePrueba.valido(ProveedorDeTokensDePrueba.sujetoNuevo(), cuenta.email());
        return new Quien(cuenta.id(), persona.id(), "Bearer " + token);
    }

    private UUID persona(MembershipRole rol) {
        return quien(rol).persona();
    }

    private Quien quien(MembershipRole rol) {
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
            String nombre,
            HttpMethod metodo,
            String ruta,
            @Nullable String cuerpo,
            Map<MembershipRole, Resultado> esperado) {}

    /**
     * La tabla, una fila por endpoint.
     *
     * <p>Lo que no se declara es {@link Resultado#PROHIBIDO}: es el "--" de la matriz, y escribirlo
     * siete veces por fila taparia lo que cada endpoint si permite. Lo que no se puede omitir son
     * los {@link Resultado#NO_ENCONTRADO}, porque son los que distinguen un permiso de alcance
     * propio de uno que no existe.
     */
    private List<Endpoint> endpoints() {
        String enElCondominio = "/api/v1/condominios/" + condominio;
        String laUnidad = enElCondominio + "/unidades/" + unidadPropia;
        String laUnidadAjena = enElCondominio + "/unidades/" + unidadAjena;
        return List.of(
                // Verlo solo pide pertenecer, y eso ya lo exigio el filtro del #17. Los siete.
                new Endpoint("consultar el condominio", HttpMethod.GET, enElCondominio, null, todos()),
                new Endpoint("mi perfil", HttpMethod.GET, "/api/v1/me", null, todos()),

                // Administracion de unidades y personas: solo el administrador del condominio.
                new Endpoint(
                        "dar de alta una unidad",
                        HttpMethod.POST,
                        enElCondominio + "/unidades",
                        unidadConCodigo(),
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "alta masiva de unidades",
                        HttpMethod.POST,
                        enElCondominio + "/unidades/lote",
                        "{\"unidades\":[" + unidadConCodigo() + "]}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "dar de alta un bloque",
                        HttpMethod.POST,
                        enElCondominio + "/bloques",
                        "{\"name\":\"Bloque " + UUID.randomUUID() + "\"}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "dar de alta un piso",
                        HttpMethod.POST,
                        enElCondominio + "/bloques/" + bloque + "/pisos",
                        "{\"number\":3}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "registrar un propietario",
                        HttpMethod.POST,
                        laUnidad + "/propietarios",
                        "{\"personId\":\"" + personaSuelta + "\",\"share\":10.0}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "registrar un ocupante",
                        HttpMethod.POST,
                        laUnidad + "/ocupantes",
                        "{\"personId\":\"" + personaSuelta + "\",\"role\":\"INQUILINO\"}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "registrar un responsable de pago",
                        HttpMethod.POST,
                        laUnidad + "/responsables-de-pago",
                        "{\"personId\":\"" + personaSuelta + "\"}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "dar de alta una persona",
                        HttpMethod.POST,
                        "/api/v1/personas",
                        "{\"firstNames\":\"Nueva\",\"lastNames\":\"Persona\"}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "abrir una cuenta",
                        HttpMethod.POST,
                        "/api/v1/personas/" + personaSinCuenta + "/cuenta",
                        "{\"email\":\"" + ProveedorDeTokensDePrueba.correoNuevo() + "\"}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "consultar una persona ajena",
                        HttpMethod.GET,
                        "/api/v1/personas/" + personaSuelta,
                        null,
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "consultar una cuenta ajena",
                        HttpMethod.GET,
                        "/api/v1/cuentas/" + cuentaSuelta,
                        null,
                        soloPara(CONDO_ADMIN)),

                // El padron del edificio: quien reparte los roles. La propia membresia la da /me.
                new Endpoint(
                        "listar las membresias",
                        HttpMethod.GET,
                        enElCondominio + "/membresias",
                        null,
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "consultar una membresia",
                        HttpMethod.GET,
                        enElCondominio + "/membresias/" + membresiaDelCondominio,
                        null,
                        soloPara(CONDO_ADMIN)),

                // Invitaciones (issue #18). Emitir pide poder conceder esos roles, igual que el alta
                // directa de membresia: es lo mismo diferido. El resto son operaciones sobre el
                // padron del edificio, y el superadministrador no lo opera (Seguridad 10.1).
                new Endpoint(
                        "emitir una invitacion",
                        HttpMethod.POST,
                        enElCondominio + "/invitaciones",
                        "{\"accountId\":\"" + cuentaParaInvitar + "\",\"roles\":[\"OWNER\"]}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "listar las invitaciones",
                        HttpMethod.GET,
                        enElCondominio + "/invitaciones",
                        null,
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "consultar una invitacion",
                        HttpMethod.GET,
                        enElCondominio + "/invitaciones/" + invitacionAbierta,
                        null,
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "reenviar una invitacion",
                        HttpMethod.POST,
                        enElCondominio + "/invitaciones/" + invitacionAbierta + "/reenvio",
                        null,
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "revocar una invitacion",
                        HttpMethod.POST,
                        enElCondominio + "/invitaciones/" + invitacionAbierta + "/revocacion",
                        null,
                        soloPara(CONDO_ADMIN)),

                // Conceder: el administrador cualquiera, el superadministrador solo administrativos,
                // y estos roles no lo son. Es la celda "Solo administradores" vista por su limite.
                new Endpoint(
                        "conceder una membresia",
                        HttpMethod.POST,
                        enElCondominio + "/membresias",
                        "{\"accountId\":\"" + cuentaSuelta + "\",\"roles\":[\"OWNER\"]}",
                        soloPara(CONDO_ADMIN)),

                // La bitacora (issue #21). La matriz da CONSULTAR_AUDITORIA a los dos
                // administradores del condominio y al superadministrador --que no opera, pero audita
                // (Seguridad 10.1)--, y a nadie mas: dice que hizo cada vecino.
                new Endpoint(
                        "consultar la auditoria",
                        HttpMethod.GET,
                        enElCondominio + "/auditoria",
                        null,
                        tabla(Map.of(
                                PLATFORM_ADMIN, PERMITIDO,
                                CONDO_ADMIN, PERMITIDO,
                                FINANCE_ADMIN, PERMITIDO))),

                // Lo que se cobra es del que maneja el dinero, y de nadie mas.
                new Endpoint(
                        "cambiar la configuracion",
                        HttpMethod.PUT,
                        enElCondominio + "/configuracion",
                        "{\"issueDay\":5,\"dueDay\":10}",
                        soloPara(FINANCE_ADMIN)),

                // Crear condominios es del superadministrador.
                new Endpoint(
                        "dar de alta un condominio",
                        HttpMethod.POST,
                        "/api/v1/condominios",
                        altaDeCondominioJson(),
                        soloPara(PLATFORM_ADMIN)),

                // La ficha de la unidad: la matriz no tiene fila para esto, asi que la ve quien
                // administra unidades, y cualquier otro solo la suya. Quien no cumple ni una cosa
                // ni la otra recibe 404, no 403: la respuesta depende de que unidad pidio.
                new Endpoint(
                        "consultar la unidad propia",
                        HttpMethod.GET,
                        laUnidad,
                        null,
                        tabla(Map.of(
                                CONDO_ADMIN, PERMITIDO,
                                OWNER, PERMITIDO,
                                TENANT, PERMITIDO,
                                PLATFORM_ADMIN, NO_ENCONTRADO,
                                FINANCE_ADMIN, NO_ENCONTRADO,
                                MAINTENANCE, NO_ENCONTRADO,
                                BOARD, NO_ENCONTRADO))),

                // Los propietarios de una unidad van por VER_ESTADO_DE_CUENTA, que la matriz da
                // entera a los dos administradores y "Propias" al propietario y al inquilino. Aqui
                // se ven las tres salidas de la cadena en una sola fila.
                new Endpoint(
                        "listar los propietarios de la unidad propia",
                        HttpMethod.GET,
                        laUnidad + "/propietarios",
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
                        enElCondominio + "/membresias/" + membresiaParaDarDeBaja + "/baja",
                        motivo(),
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "suspender una membresia",
                        HttpMethod.POST,
                        enElCondominio + "/membresias/" + membresiaParaSuspender + "/suspension",
                        motivo(),
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "reactivar una membresia",
                        HttpMethod.POST,
                        enElCondominio + "/membresias/" + membresiaParaReactivar + "/reactivacion",
                        motivo(),
                        soloPara(CONDO_ADMIN)),

                // Y los cierres de relacion del mismo issue, que son administracion de unidades como
                // sus altas. El historico de propietarios va por ahi y no por VER_ESTADO_DE_CUENTA
                // como el listado de hoy: Seguridad 6.2 deja el comprobante de la epoca del vendedor
                // en manos del administrador.
                new Endpoint(
                        "ver el historico de propietarios",
                        HttpMethod.GET,
                        laUnidad + "/propietarios/historico",
                        null,
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "transferir una unidad",
                        HttpMethod.POST,
                        laUnidadAjena + "/transferencia",
                        "{\"fromPersonId\":\"" + vendedorDeLaAjena + "\",\"toPersonId\":\"" + compradorDeLaAjena
                                + "\"}",
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "terminar una ocupacion",
                        HttpMethod.DELETE,
                        laUnidadAjena + "/ocupantes/" + ocupanteDeLaAjena,
                        null,
                        soloPara(CONDO_ADMIN)),
                new Endpoint(
                        "terminar una responsabilidad de pago",
                        HttpMethod.DELETE,
                        laUnidadAjena + "/responsables-de-pago/" + responsableDeLaAjena,
                        null,
                        soloPara(CONDO_ADMIN)));
    }

    /** El motivo que piden la baja, la suspension y la reactivacion. Con el minimo de caracteres. */
    private static String motivo() {
        return "{\"motivo\":\"lo decide la rejilla de autorizacion\"}";
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
    private static String unidadConCodigo() {
        String sufijo = UUID.randomUUID().toString().substring(0, 8);
        return "{\"code\":\"R-" + sufijo + "\",\"type\":\"DEPARTAMENTO\",\"aliquot\":\"1.000000\"}";
    }

    private static String altaDeCondominioJson() {
        return "{\"name\":\"Torre " + UUID.randomUUID() + "\",\"nit\":\"" + nitNuevo()
                + "\",\"type\":\"EDIFICIO\",\"timeZone\":\"America/La_Paz\",\"issueDay\":5,\"dueDay\":10}";
    }

    private static Map<MembershipRole, Resultado> todos() {
        var completa = new EnumMap<MembershipRole, Resultado>(MembershipRole.class);
        for (MembershipRole rol : MembershipRole.values()) {
            completa.put(rol, PERMITIDO);
        }
        return completa;
    }

    private static Map<MembershipRole, Resultado> soloPara(MembershipRole unico) {
        return tabla(Map.of(unico, PERMITIDO));
    }

    /** Rellena con {@link Resultado#PROHIBIDO} los roles que la fila no nombra. */
    private static Map<MembershipRole, Resultado> tabla(Map<MembershipRole, Resultado> declarado) {
        var completa = new EnumMap<MembershipRole, Resultado>(MembershipRole.class);
        for (MembershipRole rol : MembershipRole.values()) {
            completa.put(rol, declarado.getOrDefault(rol, PROHIBIDO));
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
                        java.util.Objects.requireNonNull(endpoint.esperado().get(rol))));
            }
        }
        return casos;
    }

    private record Celda(Endpoint endpoint, MembershipRole rol, Resultado esperado) {

        @Override
        public String toString() {
            return "%s con %s da %s".formatted(endpoint.nombre(), rol, esperado);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("celdas")
    @DisplayName("la rejilla entera: los treinta y tres endpoints por los siete roles")
    void laRejillaEntera(Celda celda) throws Exception {
        var resultado =
                mockMvc.perform(peticion(celda.endpoint(), quien(celda.rol()).autorizacion()));

        switch (celda.esperado()) {
            case PERMITIDO -> resultado.andExpect(estadoQueNoSea(403, 404, celda));
            case PROHIBIDO ->
                resultado
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
            case NO_ENCONTRADO ->
                resultado
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
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
            int unEstado, int otro, Celda celda) {
        return resultado -> {
            int estado = resultado.getResponse().getStatus();
            if (estado == unEstado || estado == otro) {
                throw new AssertionError("La celda '%s' respondio %d.".formatted(celda, estado));
            }
        };
    }

    private MockHttpServletRequestBuilder peticion(Endpoint endpoint, String autorizacion) {
        var constructor = request(endpoint.metodo(), endpoint.ruta())
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .header(HttpHeaders.AUTHORIZATION, autorizacion);
        String cuerpo = endpoint.cuerpo();
        if (cuerpo != null) {
            constructor.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        }
        return constructor;
    }

    // --- El primer criterio: un recurso que no es tuyo no existe ----------------------------

    @Test
    @DisplayName("el propietario ve su unidad y la del vecino le responde 404")
    void elPropietarioSoloVeSuUnidad() throws Exception {
        // Es el criterio del issue, con el estado de cuenta sustituido por lo que hay en M1: las
        // dos unidades son del MISMO condominio y el propietario tiene membresia activa en el, asi
        // que el 404 no lo pone el filtro del #17 sino la relacion con el recurso. Es la unica
        // forma de probar este eslabon por separado.
        String propietario = quien(OWNER).autorizacion();

        mockMvc.perform(peticion(laUnidad(unidadPropia), propietario)).andExpect(status().isOk());

        mockMvc.perform(peticion(laUnidad(unidadAjena), propietario))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("al administrador del condominio las dos unidades le responden igual")
    void elAdministradorVeLasDos() throws Exception {
        // El control del caso anterior. Sin esto, una unidad mal sembrada daria 404 a todo el mundo
        // y el 404 del propietario no probaria nada.
        String administrador = quien(CONDO_ADMIN).autorizacion();

        mockMvc.perform(peticion(laUnidad(unidadPropia), administrador)).andExpect(status().isOk());
        mockMvc.perform(peticion(laUnidad(unidadAjena), administrador)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("sobre la unidad ajena el alcance propio da 404 y el rol sin permiso da 403")
    void laNegativaDistingueElRolDelRecurso() throws Exception {
        // Las dos negativas sobre el MISMO endpoint y el MISMO recurso, que es donde se ve que no
        // son intercambiables: a quien le falta el permiso se le dice que le falta, y a quien lo
        // tiene sobre lo suyo se le dice que esto no existe. Al reves, el 403 del propietario
        // confirmaria que esa unidad existe y con eso se enumera el edificio.
        String ruta = "/api/v1/condominios/" + condominio + "/unidades/" + unidadAjena + "/propietarios";

        mockMvc.perform(request(HttpMethod.GET, ruta)
                        .header(HttpHeaders.AUTHORIZATION, quien(OWNER).autorizacion()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));

        mockMvc.perform(request(HttpMethod.GET, ruta)
                        .header(HttpHeaders.AUTHORIZATION, quien(MAINTENANCE).autorizacion()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
    }

    @Test
    @DisplayName("responder por los cobros de una unidad tambien es tenerla")
    void elResponsableDePagoTambienLaTiene() throws Exception {
        // La tercera relacion, que es la que se olvida: el propietario que alquilo ya no ocupa, y
        // si solo contara la ocupacion se quedaria sin ver la unidad por la que sigue pagando.
        // Seguridad 7.2 la nombra explicitamente --"inquilino responsable vigente del servicio"--.
        AccountView cuenta = cuentaNueva();
        UUID persona = cuenta.personId();
        membresias.grant(condominio, cuenta.id(), Set.of(TENANT), null, "admin");
        String token =
                "Bearer " + ProveedorDeTokensDePrueba.valido(ProveedorDeTokensDePrueba.sujetoNuevo(), cuenta.email());

        mockMvc.perform(peticion(laUnidad(unidadAjena), token)).andExpect(status().isNotFound());

        relaciones.assignBilling(condominio, unidadAjena, persona, null, "admin");

        mockMvc.perform(peticion(laUnidad(unidadAjena), token)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("cada uno ve su propia persona y su propia cuenta sin administrar nada")
    void loPropioSeVeSinAdministrarNada() throws Exception {
        // Un propietario no administra ningun condominio, asi que por la matriz no le toca ninguna
        // de las dos consultas. Lo propio es la excepcion, y hace falta: sin ella la cabecera
        // Location del alta de su cuenta apuntaria a una ruta que a el le responde 403.
        Quien propietario = quien(OWNER);

        mockMvc.perform(request(HttpMethod.GET, "/api/v1/personas/" + propietario.persona())
                        .header(HttpHeaders.AUTHORIZATION, propietario.autorizacion()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(propietario.persona().toString()));

        mockMvc.perform(request(HttpMethod.GET, "/api/v1/cuentas/" + propietario.cuenta())
                        .header(HttpHeaders.AUTHORIZATION, propietario.autorizacion()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(propietario.cuenta().toString()));
    }

    @Test
    @DisplayName("la relacion con la unidad no se hereda entre condominios")
    void laRelacionNoCruzaCondominios() throws Exception {
        // El propietario de la unidad de un edificio pide, en otro edificio donde tambien es
        // miembro, una unidad con la que no tiene relacion. La consulta de la relacion lleva el
        // condominio, asi que no empareja; si no lo llevara, bastaria ser propietario en cualquier
        // parte para ver cualquier unidad del mismo identificador.
        UUID otro = altaDeCondominio();
        AccountView cuenta = cuentaNueva();
        UUID persona = cuenta.personId();
        membresias.grant(condominio, cuenta.id(), Set.of(OWNER), null, "admin");
        membresias.grant(otro, cuenta.id(), Set.of(OWNER), null, "admin");
        relaciones.grantOwnership(condominio, unidadAjena, persona, new BigDecimal("10.000000"), "admin");

        UUID unidadDelOtro = unidades.register(
                        otro,
                        new NewUnit("OTRO-1", UnitType.DEPARTAMENTO, new BigDecimal("1.000000"), null, null, null),
                        "admin")
                .id();
        String token =
                "Bearer " + ProveedorDeTokensDePrueba.valido(ProveedorDeTokensDePrueba.sujetoNuevo(), cuenta.email());

        mockMvc.perform(request(HttpMethod.GET, "/api/v1/condominios/" + condominio + "/unidades/" + unidadAjena)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());

        mockMvc.perform(request(HttpMethod.GET, "/api/v1/condominios/" + otro + "/unidades/" + unidadDelOtro)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("la rejilla declara los siete roles en cada endpoint")
    void laRejillaNoDejaCeldasSinDeclarar() {
        // El guardian de este test. Una fila que se olvide de un rol lo dejaria sin probar y la
        // rejilla pasaria igual; es el mismo descuido que en el #16 dejo un permiso de menos en
        // CONDO_ADMIN, y lo cazo justo el test que exigia las columnas completas.
        for (Endpoint endpoint : endpoints()) {
            assertThat(endpoint.esperado())
                    .as("el endpoint '%s' no declara los siete roles", endpoint.nombre())
                    .containsOnlyKeys(MembershipRole.values());
        }
    }

    private Endpoint laUnidad(UUID unidad) {
        return new Endpoint(
                "consultar una unidad",
                HttpMethod.GET,
                "/api/v1/condominios/" + condominio + "/unidades/" + unidad,
                null,
                todos());
    }
}
