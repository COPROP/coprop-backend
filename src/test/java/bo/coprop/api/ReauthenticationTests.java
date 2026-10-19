package bo.coprop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.condominium.CondominiumType;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.InvitationDoubles;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.MembershipView;
import bo.coprop.identity.Memberships;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.identity.PersonView;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * El segundo criterio del issue <strong>#22</strong>, sobre HTTP: una operacion sensible sin
 * reautenticacion reciente se rechaza con un codigo distinguible.
 *
 * <p><strong>El criterio nombra la devolucion de saldo y aqui se prueba sobre el cambio de
 * roles.</strong> No es una rebaja: Seguridad 7.3 enumera cinco operaciones sensibles --credenciales
 * bancarias, anular obligaciones, devolver saldo, asignar roles administrativos y abrir acceso de
 * soporte-- y hoy existe una sola, la que trajo el #20. Las otras cuatro son de M2 y M3. Lo que se
 * comprueba es el <em>mecanismo</em>, que es el mismo para las cinco: cuando llegue
 * {@code POST .../devoluciones} le bastara con añadir {@code @reautenticacion.reciente} a su
 * expresion.
 *
 * <p><strong>Responde 401 y no el 403 que el ticket escribio.</strong> Lo decide Seguridad 7.3, que
 * pide el desafio del RFC 9470, y la razon es que un 403 le diria al cliente "no puedes" cuando la
 * verdad es "puedes, pero demuestralo otra vez". El criterio pedia un codigo distinguible y lo hay:
 * {@code REAUTENTICACION_REQUERIDA}.
 */
@Import({TestcontainersConfiguration.class, TestTokenProvider.class, InvitationDoubles.class})
@SpringBootTest
@AutoConfigureMockMvc
class ReauthenticationTests {

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

    /**
     * El mismo reloj que usa la comprobacion de la ventana.
     *
     * <p>Es el doble, parado en una fecha fija. El {@code auth_time} de un token "viejo" tiene que
     * salir de aqui y no de la hora real: contra un reloj parado en el pasado, la hora real es el
     * futuro y la resta sale negativa.
     */
    @Autowired
    private java.time.Clock reloj;

    /** Una cuenta y el unico token con el que entra, igual que en los tests del #20. */
    private record Quien(AccountView cuenta, String token) {

        UUID id() {
            return cuenta.id();
        }
    }

    // --- El criterio: sin segundo factor reciente, la operacion sensible no pasa -------------

    @Test
    @DisplayName("cambiar roles sin segundo factor: 401 con REAUTENTICACION_REQUERIDA y el desafio del RFC 9470")
    void sinSegundoFactorNoSeCambianRoles() throws Exception {
        UUID condominio = altaDeCondominio();
        Quien administrador = conRolEn(condominio, MembershipRole.CONDO_ADMIN, Autenticacion.SOLO_CONTRASENA);
        MembershipView deUnPropietario = membresiaDeUnPropietario(condominio);

        mockMvc.perform(peticion(
                        HttpMethod.PUT,
                        laRutaDeRoles(condominio, deUnPropietario.id()),
                        administrador.token(),
                        "{\"roles\":[\"TENANT\"],\"motivo\":\"ahora alquila su departamento\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("REAUTENTICACION_REQUERIDA"))
                // La cabecera es lo que convierte el 401 en una instruccion: el cliente sabe que
                // tiene que volver al proveedor con max_age=0 y repetir la peticion.
                .andExpect(header().string(
                                HttpHeaders.WWW_AUTHENTICATE,
                                org.hamcrest.Matchers.allOf(
                                        org.hamcrest.Matchers.containsString("insufficient_user_authentication"),
                                        org.hamcrest.Matchers.containsString("max_age=300"))));
    }

    @Test
    @DisplayName("un segundo factor de hace una hora tampoco vale: la ventana es de cinco minutos")
    void elSegundoFactorCaduca() throws Exception {
        UUID condominio = altaDeCondominio();
        Quien administrador = conRolEn(condominio, MembershipRole.CONDO_ADMIN, Autenticacion.FACTOR_VIEJO);
        MembershipView deUnPropietario = membresiaDeUnPropietario(condominio);

        // El token esta perfectamente vigente --expira en cinco minutos-- y aun asi no pasa. Es la
        // diferencia entre cuando se emitio el token y cuando la persona se autentico de verdad:
        // lo que caduca aqui es lo segundo, que es lo que auth_time dice.
        mockMvc.perform(peticion(
                        HttpMethod.PUT,
                        laRutaDeRoles(condominio, deUnPropietario.id()),
                        administrador.token(),
                        "{\"roles\":[\"TENANT\"],\"motivo\":\"ahora alquila su departamento\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("REAUTENTICACION_REQUERIDA"));
    }

    @Test
    @DisplayName("un proveedor que no manda amr ni auth_time: se exige, porque no se sabe")
    void sinAfirmacionesSeExige() throws Exception {
        UUID condominio = altaDeCondominio();
        Quien administrador = conRolEn(condominio, MembershipRole.CONDO_ADMIN, Autenticacion.SIN_AFIRMACIONES);
        MembershipView deUnPropietario = membresiaDeUnPropietario(condominio);

        // La respuesta segura ante un token que no dice nada es exigir, no dar por bueno. Si algun
        // dia el realm deja de mandar esas afirmaciones, las operaciones sensibles se cierran en
        // vez de abrirse, que es el lado por el que conviene equivocarse.
        mockMvc.perform(peticion(
                        HttpMethod.PUT,
                        laRutaDeRoles(condominio, deUnPropietario.id()),
                        administrador.token(),
                        "{\"roles\":[\"TENANT\"],\"motivo\":\"ahora alquila su departamento\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("REAUTENTICACION_REQUERIDA"));
    }

    @Test
    @DisplayName("con segundo factor reciente, la misma peticion pasa")
    void conSegundoFactorPasa() throws Exception {
        UUID condominio = altaDeCondominio();
        Quien administrador = conRolEn(condominio, MembershipRole.CONDO_ADMIN, Autenticacion.CON_SEGUNDO_FACTOR);
        MembershipView deUnPropietario = membresiaDeUnPropietario(condominio);

        // Es el control del caso anterior y hace falta: sin el, los tres de arriba pasarian igual
        // con la ruta mal escrita o con el endpoint roto.
        mockMvc.perform(peticion(
                        HttpMethod.PUT,
                        laRutaDeRoles(condominio, deUnPropietario.id()),
                        administrador.token(),
                        "{\"roles\":[\"TENANT\"],\"motivo\":\"ahora alquila su departamento\"}"))
                .andExpect(status().isOk());
    }

    // --- El orden: primero el permiso, despues el factor -------------------------------------

    @Test
    @DisplayName("a quien no puede se le responde 403, no se le pide un segundo factor que no le serviria")
    void elPermisoVaPrimero() throws Exception {
        UUID condominio = altaDeCondominio();
        // Sin permiso para repartir roles Y sin segundo factor: las dos cosas fallan a la vez, y la
        // respuesta tiene que ser la del permiso. Pedirle el factor seria mandarle a buscar su
        // telefono para recibir un 403 igual, y de paso confirmarle que la operacion existe.
        Quien financiero = conRolEn(condominio, MembershipRole.FINANCE_ADMIN, Autenticacion.SOLO_CONTRASENA);
        MembershipView deUnPropietario = membresiaDeUnPropietario(condominio);

        mockMvc.perform(peticion(
                        HttpMethod.PUT,
                        laRutaDeRoles(condominio, deUnPropietario.id()),
                        financiero.token(),
                        "{\"roles\":[\"TENANT\"],\"motivo\":\"ahora alquila su departamento\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
    }

    @Test
    @DisplayName("y el 404 del condominio ajeno sigue ganandole al segundo factor")
    void elCondominioAjenoVaAntes() throws Exception {
        UUID propio = altaDeCondominio();
        UUID ajeno = altaDeCondominio();
        Quien administrador = conRolEn(propio, MembershipRole.CONDO_ADMIN, Autenticacion.SOLO_CONTRASENA);
        MembershipView enElAjeno = membresiaDeUnPropietario(ajeno);

        // Si el factor se comprobara primero, un 401 le diria a cualquiera que esa membresia de
        // otro edificio existe. El orden de la expresion lo impide.
        mockMvc.perform(peticion(
                        HttpMethod.PUT,
                        laRutaDeRoles(propio, enElAjeno.id()),
                        administrador.token(),
                        "{\"roles\":[\"TENANT\"],\"motivo\":\"ahora alquila su departamento\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    // --- El rol de plataforma, que es el mayor de los administrativos ------------------------

    @Test
    @DisplayName("conceder y revocar el rol de plataforma piden segundo factor")
    void elRolDePlataformaPideSegundoFactor() throws Exception {
        Quien sinFactor = cuentaDeSuperadministrador(Autenticacion.SOLO_CONTRASENA);
        Quien conFactor = cuentaDeSuperadministrador(Autenticacion.CON_SEGUNDO_FACTOR);
        Quien destinatario = cuentaNueva(Autenticacion.CON_SEGUNDO_FACTOR);

        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/superadministradores",
                        sinFactor.token(),
                        "{\"accountId\":\"" + destinatario.id() + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("REAUTENTICACION_REQUERIDA"));

        // Listarlos no es sensible y sigue pasando: lo que pide factor es repartir el rol, no
        // mirar quien lo tiene.
        mockMvc.perform(peticion(HttpMethod.GET, "/api/v1/superadministradores", sinFactor.token(), null))
                .andExpect(status().isOk());

        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/superadministradores",
                        conFactor.token(),
                        "{\"accountId\":\"" + destinatario.id() + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/superadministradores/" + destinatario.id() + "/revocacion",
                        sinFactor.token(),
                        "{\"motivo\":\"se equivocaron de cuenta\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("REAUTENTICACION_REQUERIDA"));

        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/superadministradores/" + destinatario.id() + "/revocacion",
                        conFactor.token(),
                        "{\"motivo\":\"se equivocaron de cuenta\"}"))
                .andExpect(status().isNoContent());
    }

    // --- La condicion: lo sensible es el rol administrativo, no el alta --------------------

    @Test
    @DisplayName("dar de alta a un propietario no pide segundo factor; nombrar a un administrador si")
    void soloLoAdministrativoPideFactor() throws Exception {
        UUID condominio = altaDeCondominio();
        Quien administrador = conRolEn(condominio, MembershipRole.CONDO_ADMIN, Autenticacion.SOLO_CONTRASENA);
        Quien vecino = cuentaNueva(Autenticacion.CON_SEGUNDO_FACTOR);
        Quien futuroAdministrador = cuentaNueva(Autenticacion.CON_SEGUNDO_FACTOR);

        // Sin el "si hay rol administrativo", invitar al vecino del 3B pediria un segundo factor y
        // el control estorbaria donde no protege nada.
        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/condominios/" + condominio + "/membresias",
                        administrador.token(),
                        "{\"accountId\":\"" + vecino.id() + "\",\"roles\":[\"OWNER\"]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/condominios/" + condominio + "/membresias",
                        administrador.token(),
                        "{\"accountId\":\"" + futuroAdministrador.id() + "\",\"roles\":[\"CONDO_ADMIN\"]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("REAUTENTICACION_REQUERIDA"));
    }

    @Test
    @DisplayName("una invitacion es una membresia diferida, y sigue la misma regla")
    void laInvitacionSigueLaMismaRegla() throws Exception {
        UUID condominio = altaDeCondominio();
        Quien administrador = conRolEn(condominio, MembershipRole.CONDO_ADMIN, Autenticacion.SOLO_CONTRASENA);
        Quien vecino = cuentaNueva(Autenticacion.CON_SEGUNDO_FACTOR);
        Quien futuroAdministrador = cuentaNueva(Autenticacion.CON_SEGUNDO_FACTOR);

        // Invitar a alguien como CONDO_ADMIN es asignarle un rol administrativo: la membresia nace
        // al aceptar, pero quien decide los roles es quien emite. Sin esto, la invitacion seria la
        // puerta de atras del control.
        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/condominios/" + condominio + "/invitaciones",
                        administrador.token(),
                        "{\"accountId\":\"" + vecino.id() + "\",\"roles\":[\"OWNER\"]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/condominios/" + condominio + "/invitaciones",
                        administrador.token(),
                        "{\"accountId\":\"" + futuroAdministrador.id() + "\",\"roles\":[\"CONDO_ADMIN\"]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("REAUTENTICACION_REQUERIDA"));
    }

    // --- Armado -----------------------------------------------------------------------------

    /** Con que se autentico la cuenta de cada caso. */
    // --- La condicion: lo sensible es el NIT, no corregir una tilde del nombre ---------------

    @Test
    @DisplayName("corregir el nombre no pide segundo factor; cambiar el NIT si")
    void soloElNitPideFactor() throws Exception {
        Quien superadministrador = cuentaDeSuperadministrador(Autenticacion.SOLO_CONTRASENA);
        UUID condominio = altaDeCondominio();
        String nitActual = condominios.find(condominio).nit();

        // Mandar el NIT que ya tiene no es un cambio. Sin esta condicion, en un PUT donde los tres
        // campos viajan siempre, corregir una tilde del nombre pediria reautenticarse.
        mockMvc.perform(peticion(
                        HttpMethod.PUT,
                        "/api/v1/condominios/" + condominio,
                        superadministrador.token(),
                        edicion("Las Palmas corregido", nitActual)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Las Palmas corregido"))
                .andExpect(jsonPath("$.address").value("Av. Ballivian 123"));

        mockMvc.perform(peticion(
                        HttpMethod.PUT,
                        "/api/v1/condominios/" + condominio,
                        superadministrador.token(),
                        edicion("Las Palmas corregido", "7777777001")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("REAUTENTICACION_REQUERIDA"));

        // Y la negativa no dejo el NIT a medias.
        assertThat(condominios.find(condominio).nit()).isEqualTo(nitActual);
    }

    @Test
    @DisplayName("con segundo factor reciente, el NIT si se cambia")
    void conFactorElNitSeCambia() throws Exception {
        Quien superadministrador = cuentaDeSuperadministrador(Autenticacion.CON_SEGUNDO_FACTOR);
        UUID condominio = altaDeCondominio();

        mockMvc.perform(peticion(
                        HttpMethod.PUT,
                        "/api/v1/condominios/" + condominio,
                        superadministrador.token(),
                        edicion("Las Palmas", "7777777002")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nit").value("7777777002"));
    }

    /** El cuerpo de la edicion, en un bloque de texto para no escapar las comillas. */
    private static String edicion(String nombre, String nit) {
        return """
                {"name": "%s", "nit": "%s", "address": "Av. Ballivian 123"}"""
                .formatted(nombre, nit);
    }

    private enum Autenticacion {
        CON_SEGUNDO_FACTOR,
        SOLO_CONTRASENA,
        FACTOR_VIEJO,
        SIN_AFIRMACIONES
    }

    private static String laRutaDeRoles(UUID condominio, UUID membresia) {
        return "/api/v1/condominios/" + condominio + "/membresias/" + membresia + "/roles";
    }

    private MembershipView membresiaDeUnPropietario(UUID condominio) {
        return membresias.grant(
                condominio,
                cuentaNueva(Autenticacion.CON_SEGUNDO_FACTOR).id(),
                Set.of(MembershipRole.OWNER),
                null,
                "admin");
    }

    private UUID altaDeCondominio() {
        return condominios
                .register(
                        new NewCondominium(
                                "Sessions " + UUID.randomUUID(),
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

    private Quien cuentaNueva(Autenticacion como) {
        PersonView persona = personas.register(new NewPerson("Emma", "Tola", null), "admin");
        AccountView cuenta = personas.openAccount(persona.id(), TestTokenProvider.correoNuevo(), null, "admin");
        String sujeto = TestTokenProvider.sujetoNuevo();
        String token =
                switch (como) {
                    case CON_SEGUNDO_FACTOR -> TestTokenProvider.valido(sujeto, cuenta.email());
                    case SOLO_CONTRASENA -> TestTokenProvider.sinSegundoFactor(sujeto, cuenta.email());
                    case FACTOR_VIEJO ->
                        TestTokenProvider.conSegundoFactorEn(
                                sujeto, cuenta.email(), reloj.instant().minus(1, java.time.temporal.ChronoUnit.HOURS));
                    case SIN_AFIRMACIONES -> TestTokenProvider.sinAfirmacionesDeAutenticacion(sujeto, cuenta.email());
                };
        return new Quien(cuenta, "Bearer " + token);
    }

    private Quien conRolEn(UUID condominio, MembershipRole rol, Autenticacion como) {
        Quien quien = cuentaNueva(como);
        membresias.grant(condominio, quien.id(), Set.of(rol), null, "admin");
        return quien;
    }

    private Quien cuentaDeSuperadministrador(Autenticacion como) {
        Quien quien = cuentaNueva(como);
        new JdbcTemplate(dataSource)
                .update("insert into platform_admin (user_id, granted_at) values (?, now())", quien.id());
        return quien;
    }

    private MockHttpServletRequestBuilder peticion(
            HttpMethod metodo, String ruta, String autorizacion, @Nullable String cuerpo) {
        var constructor = request(metodo, ruta)
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .header(HttpHeaders.AUTHORIZATION, autorizacion);
        if (cuerpo != null) {
            constructor.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        }
        return constructor;
    }
}
