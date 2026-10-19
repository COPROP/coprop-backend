package bo.coprop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.condominium.CondominiumType;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Las invitaciones del issue #18, de punta a punta sobre HTTP.
 *
 * <p>Los dos criterios que se pueden comprobar hoy estan aqui. El primero --"un token usado o
 * vencido no activa nada"-- con sus cuatro motivos, porque los cuatro tienen que responder lo
 * mismo. El segundo, el de vincular la cuenta existente, lo cumple el primer login del issue #13 y
 * tiene su prueba en {@code AutenticacionDeApiTests}; aqui se comprueba la mitad que es de este
 * issue: que aceptar <strong>no crea una segunda cuenta</strong>, solo agrega la membresia.
 *
 * <p>El tercero --que todo quede en auditoria-- no se puede comprobar: los eventos se publican y el
 * modulo {@code audit} es el issue #21. Lo que si se comprueba es que los eventos salgan.
 *
 * <p><strong>El token se lee del correo</strong>, con {@code CorreoDePrueba}, igual que lo leeria la
 * persona invitada. Es el unico sitio donde existe: en la base solo hay un hash, y un test que lo
 * sacara de ahi estaria probando otro sistema.
 */
@Import({TestcontainersConfiguration.class, DoblesDeInvitacion.class})
@SpringBootTest
@AutoConfigureMockMvc
class InvitacionesTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Condominiums condominios;

    @Autowired
    private People personas;

    @Autowired
    private Memberships membresias;

    @Autowired
    private Invitations invitaciones;

    @Autowired
    private DoblesDeInvitacion.CorreoDePrueba buzon;

    @Autowired
    private DoblesDeAcceso.ProveedorDeIdentidadDePrueba proveedor;

    @Autowired
    private DoblesDeInvitacion.RelojDePrueba reloj;

    @Autowired
    private javax.sql.DataSource fuenteDeDatos;

    private UUID condominio;

    /** La cuenta que invita: administradora del condominio. */
    private UUID administradora;

    /** La cuenta invitada, recien abierta y sin haber entrado nunca. */
    private AccountView invitada;

    @BeforeEach
    void prepararElCondominioYLasDosCuentas() {
        buzon.vaciar();
        condominio = condominios
                .register(
                        new NewCondominium(
                                "Invitaciones " + UUID.randomUUID(),
                                nitNuevo(),
                                CondominiumType.BUILDING,
                                "America/La_Paz",
                                5,
                                10),
                        "admin")
                .id();

        AccountView cuentaDeLaAdministradora = cuentaNueva();
        administradora = cuentaDeLaAdministradora.id();
        membresias.grant(condominio, administradora, Set.of(MembershipRole.CONDO_ADMIN), null, "admin");

        invitada = cuentaNueva();
    }

    private static String nitNuevo() {
        return String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_0000L));
    }

    private AccountView cuentaNueva() {
        PersonView persona = personas.register(new NewPerson("Emma", "Tola", null), "admin");
        return personas.openAccount(persona.id(), "emma-" + UUID.randomUUID() + "@coprop.bo", null, "admin");
    }

    // --- Lo que hace la administradora ------------------------------------------------------

    private MockHttpServletRequestBuilder comoAdministradora(MockHttpServletRequestBuilder peticion) {
        return peticion.with(csrf()).with(user(administradora.toString()).roles("CONDO_ADMIN"));
    }

    private String emitir(MembershipRole... roles) throws Exception {
        String cuerpo = "{\"accountId\":\"%s\",\"roles\":[%s]}"
                .formatted(
                        invitada.id(),
                        java.util.Arrays.stream(roles)
                                .map(rol -> "\"" + rol.name() + "\"")
                                .collect(java.util.stream.Collectors.joining(",")));

        mockMvc.perform(comoAdministradora(post("/api/v1/condominios/" + condominio + "/invitaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo)))
                .andExpect(status().isCreated());

        return tokenDelCorreo();
    }

    private String tokenDelCorreo() {
        return buzon.ultimoTokenPara(invitada.email())
                .orElseThrow(() -> new AssertionError("No llego ningun correo con enlace a %s. Enviados: %s"
                        .formatted(invitada.email(), buzon.enviados())));
    }

    private MockHttpServletRequestBuilder apertura(String token) {
        return post("/api/v1/invitaciones/apertura")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\"}".formatted(token));
    }

    private MockHttpServletRequestBuilder aceptacion(String token, UUID cuenta) {
        return post("/api/v1/invitaciones/aceptacion")
                .with(csrf())
                .with(user(cuenta.toString()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\"}".formatted(token));
    }

    // --- El camino que funciona -------------------------------------------------------------

    @Test
    @DisplayName("la invitacion llega por correo, se abre sin autenticar y al aceptarla nace la membresia")
    void elCaminoCompleto() throws Exception {
        String token = emitir(MembershipRole.OWNER, MembershipRole.BOARD);

        // Antes de aceptar no hay membresia: la invitacion no mete a nadie, solo le abre la puerta.
        assertThat(membresias.of(condominio, invitada.id())).isNull();

        // Se abre SIN autenticar: quien acaba de recibir el enlace no tiene con que entrar.
        mockMvc.perform(apertura(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominio").value(condominio.toString()))
                // Esta cuenta nunca entro, asi que no tiene credencial en el proveedor.
                .andExpect(jsonPath("$.credencialEnviada").value(true));

        assertThat(proveedor.preparados()).contains(invitada.email());

        mockMvc.perform(aceptacion(token, invitada.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedAt").isNotEmpty());

        MembershipView nacida = java.util.Objects.requireNonNull(
                membresias.of(condominio, invitada.id()), "Aceptar la invitacion no dejo ninguna membresia.");
        assertThat(nacida.status()).isEqualTo(MembershipStatus.ACTIVE);
        // Los roles son los que llevaba la invitacion, no unos por defecto.
        assertThat(nacida.roles()).containsExactlyInAnyOrder(MembershipRole.OWNER, MembershipRole.BOARD);
        // Y queda registrado quien invito, que es lo que la bitacora del #21 necesita para
        // reconstruir por que esta persona esta dentro.
        assertThat(nacida.accountId()).isEqualTo(invitada.id());
    }

    @Test
    @DisplayName("aceptar no crea una segunda cuenta: la persona sigue siendo la misma")
    void aceptarNoCreaUnaSegundaCuenta() throws Exception {
        // La mitad de este issue del segundo criterio. La otra --que entrar con Google caiga en la
        // misma cuenta-- la hace cumplir el primer login del #13, vinculando por correo verificado.
        String token = emitir(MembershipRole.OWNER);
        UUID personaAntes = invitada.personId();

        mockMvc.perform(aceptacion(token, invitada.id())).andExpect(status().isOk());

        AccountView newValues = personas.findAccount(invitada.id());
        assertThat(newValues.personId()).isEqualTo(personaAntes);
        assertThat(newValues.email()).isEqualTo(invitada.email());
    }

    @Test
    @DisplayName("el correo lleva un enlace a la web, no a la API")
    void elCorreoLlevaUnEnlaceALaWeb() throws Exception {
        emitir(MembershipRole.OWNER);

        List<DoblesDeInvitacion.CorreoDePrueba.Enviado> enviados = buzon.enviados();
        assertThat(enviados).hasSize(1);
        assertThat(enviados.get(0).destinatario()).isEqualTo(invitada.email());
        // La pagina es la que sabe que mostrar en cada caso y la que manda al login; una ruta de la
        // API obligaria al backend a devolver redirecciones y a conocer las rutas de la SPA.
        assertThat(enviados.get(0).cuerpo()).contains("http://localhost:4200/invitaciones/");
        assertThat(enviados.get(0).cuerpo()).doesNotContain("/api/v1");
    }

    // --- El primer criterio: un token que no sirve no activa nada ---------------------------

    @Test
    @DisplayName("un token ya usado no vuelve a servir")
    void unTokenUsadoNoVuelveAServir() throws Exception {
        String token = emitir(MembershipRole.OWNER);
        mockMvc.perform(aceptacion(token, invitada.id())).andExpect(status().isOk());

        mockMvc.perform(aceptacion(token, invitada.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("un token vencido no activa nada")
    void unTokenVencidoNoActivaNada() throws Exception {
        String token = emitir(MembershipRole.OWNER);

        // Siete dias y un minuto. Con un Instant.now() dentro del servicio, este test no existiria.
        reloj.adelantar(Duration.ofDays(7).plusMinutes(1));

        mockMvc.perform(aceptacion(token, invitada.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
        assertThat(membresias.of(condominio, invitada.id())).isNull();
    }

    @Test
    @DisplayName("a los siete dias justos todavia sirve")
    void justoAntesDeVencerTodaviaSirve() throws Exception {
        // El control del test anterior: sin esto, una vigencia de cero dias lo pasaria igual.
        String token = emitir(MembershipRole.OWNER);

        reloj.adelantar(Duration.ofDays(7).minusMinutes(1));

        mockMvc.perform(aceptacion(token, invitada.id())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("un token revocado no activa nada")
    void unTokenRevocadoNoActivaNada() throws Exception {
        String token = emitir(MembershipRole.OWNER);
        UUID invitacion = invitaciones.ofCondominium(condominio).get(0).id();

        mockMvc.perform(comoAdministradora(
                        post("/api/v1/condominios/" + condominio + "/invitaciones/" + invitacion + "/revocacion")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedAt").isNotEmpty());

        mockMvc.perform(aceptacion(token, invitada.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
        assertThat(membresias.of(condominio, invitada.id())).isNull();
    }

    @Test
    @DisplayName("un token inventado responde igual que uno que existio")
    void unTokenInventadoRespondeIgual() throws Exception {
        // Los cuatro motivos responden lo mismo a proposito: distinguirlos le diria a quien prueba
        // tokens cuales existieron.
        mockMvc.perform(apertura("estonoesuntoken"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("el enlace de otro no sirve ni teniendolo en la mano")
    void elEnlaceDeOtroNoSirve() throws Exception {
        String token = emitir(MembershipRole.OWNER);
        AccountView ajena = cuentaNueva();

        // 404 y no 403: un 403 confirmaria que ese enlace es bueno, solo que no para quien lo trae.
        mockMvc.perform(aceptacion(token, ajena.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));

        // Y el enlace sigue sirviendo para su dueña: el intento ajeno no lo consumio.
        mockMvc.perform(aceptacion(token, invitada.id())).andExpect(status().isOk());
    }

    // --- Reenvio y revocacion ---------------------------------------------------------------

    @Test
    @DisplayName("al reenviar, el enlace anterior deja de servir")
    void alReenviarElAnteriorDejaDeServir() throws Exception {
        String viejo = emitir(MembershipRole.OWNER);
        UUID primera = invitaciones.ofCondominium(condominio).get(0).id();

        mockMvc.perform(comoAdministradora(
                        post("/api/v1/condominios/" + condominio + "/invitaciones/" + primera + "/reenvio")))
                .andExpect(status().isCreated());

        String nuevo = tokenDelCorreo();
        assertThat(nuevo).isNotEqualTo(viejo);

        // Dos enlaces vivos a la vez serian dos accesos y solo uno controlado.
        mockMvc.perform(aceptacion(viejo, invitada.id())).andExpect(status().isNotFound());
        mockMvc.perform(aceptacion(nuevo, invitada.id())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("se puede reenviar una invitacion ya vencida")
    void seReenviaUnaVencida() throws Exception {
        // Es el "EXPIRADO --> INVITADO: administrador reenvia" del diagrama de Seguridad 6. Lo que
        // lo hace posible es que el indice parcial considere abierta una fila vencida, asi que el
        // reenvio la revoca antes de emitir la nueva.
        emitir(MembershipRole.OWNER);
        UUID primera = invitaciones.ofCondominium(condominio).get(0).id();
        reloj.adelantar(Duration.ofDays(8));

        mockMvc.perform(comoAdministradora(
                        post("/api/v1/condominios/" + condominio + "/invitaciones/" + primera + "/reenvio")))
                .andExpect(status().isCreated());

        mockMvc.perform(aceptacion(tokenDelCorreo(), invitada.id())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("una invitacion ya aceptada no se revoca ni se reenvia")
    void unaAceptadaNoSeRevocaNiSeReenvia() throws Exception {
        String token = emitir(MembershipRole.OWNER);
        UUID invitacion = invitaciones.ofCondominium(condominio).get(0).id();
        mockMvc.perform(aceptacion(token, invitada.id())).andExpect(status().isOk());

        // La membresia ya existe: lo que hay que hacer es darla de baja, que es el issue #19.
        // Revocar la invitacion no la quitaria y dejaria creer que si.
        mockMvc.perform(comoAdministradora(
                        post("/api/v1/condominios/" + condominio + "/invitaciones/" + invitacion + "/revocacion")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("REGLA_DE_NEGOCIO"));

        mockMvc.perform(comoAdministradora(
                        post("/api/v1/condominios/" + condominio + "/invitaciones/" + invitacion + "/reenvio")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("REGLA_DE_NEGOCIO"));
    }

    // --- Lo que no se puede invitar ---------------------------------------------------------

    @Test
    @DisplayName("no se invita dos veces a la misma cuenta: para eso esta el reenvio")
    void noSeInvitaDosVeces() throws Exception {
        emitir(MembershipRole.OWNER);

        mockMvc.perform(comoAdministradora(post("/api/v1/condominios/" + condominio + "/invitaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"roles\":[\"OWNER\"]}".formatted(invitada.id()))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("REGLA_DE_NEGOCIO"));
    }

    @Test
    @DisplayName("no se invita a quien ya es miembro")
    void noSeInvitaAQuienYaEsMiembro() throws Exception {
        membresias.grant(condominio, invitada.id(), Set.of(MembershipRole.OWNER), null, "admin");

        mockMvc.perform(comoAdministradora(post("/api/v1/condominios/" + condominio + "/invitaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"roles\":[\"OWNER\"]}".formatted(invitada.id()))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("REGLA_DE_NEGOCIO"));
        assertThat(buzon.enviados()).isEmpty();
    }

    @Test
    @DisplayName("PLATFORM_ADMIN no se invita: su alcance es la plataforma")
    void elRolDePlataformaNoSeInvita() throws Exception {
        // Se comprueba al emitir y no solo al conceder la membresia porque entre emitir y aceptar
        // pasan dias: el error tiene que salirle a quien invita, no a quien pulsa el enlace.
        mockMvc.perform(comoAdministradora(post("/api/v1/condominios/" + condominio + "/invitaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"roles\":[\"PLATFORM_ADMIN\"]}".formatted(invitada.id()))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("REGLA_DE_NEGOCIO"));
    }

    @Test
    @DisplayName("una invitacion sin roles no se emite")
    void sinRolesNoSeEmite() throws Exception {
        mockMvc.perform(comoAdministradora(post("/api/v1/condominios/" + condominio + "/invitaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"roles\":[]}".formatted(invitada.id()))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
    }

    // --- La credencial del proveedor de identidad -------------------------------------------

    @Test
    @DisplayName("a quien ya participa en otro condominio no se le manda poner contrasena otra vez")
    void aQuienYaEntroNoSeLePidePonerContrasena() throws Exception {
        // El segundo caso de Seguridad 6.1. Lo que distingue a los dos es tener sujeto del
        // proveedor, que es lo que deja el primer login del #13.
        String token = emitir(MembershipRole.OWNER);
        marcarComoYaVinculada(invitada.id());

        mockMvc.perform(apertura(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credencialEnviada").value(false));

        assertThat(proveedor.preparados()).doesNotContain(invitada.email());
    }

    @Test
    @DisplayName("abrir el enlace dos veces vuelve a mandar el correo y no rompe nada")
    void abrirDosVecesNoRompeNada() throws Exception {
        // La apertura no consume el token: el correo del proveedor se pierde mas facil que el
        // nuestro, y obligar a pedir un reenvio por eso seria castigar al invitado.
        String token = emitir(MembershipRole.OWNER);

        mockMvc.perform(apertura(token)).andExpect(status().isOk());
        mockMvc.perform(apertura(token)).andExpect(status().isOk());

        assertThat(proveedor.vecesQueSePreparo(invitada.email())).isEqualTo(2);
        mockMvc.perform(aceptacion(token, invitada.id())).andExpect(status().isOk());
    }

    /**
     * Deja la cuenta como si ya hubiera entrado alguna vez.
     *
     * <p>Por SQL porque vincular es cosa del filtro de identidad y pasa con un token de verdad, que
     * es lo que prueba {@code AutenticacionDeApiTests}. Aqui lo unico que hace falta es el estado
     * resultante.
     */
    private void marcarComoYaVinculada(UUID cuenta) {
        new org.springframework.jdbc.core.JdbcTemplate(fuenteDeDatos)
                .update(
                        "update user_account set idp_subject = ?, status = ? where id = ?",
                        UUID.randomUUID().toString(),
                        AccountStatus.ACTIVE.name(),
                        cuenta);
    }

    // --- Lo que ve la administradora --------------------------------------------------------

    @Test
    @DisplayName("el listado muestra las vivas y las cerradas, y ninguna lleva el token")
    void elListadoNoLlevaElToken() throws Exception {
        String token = emitir(MembershipRole.OWNER);

        mockMvc.perform(comoAdministradora(get("/api/v1/condominios/" + condominio + "/invitaciones")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].accountId").value(invitada.id().toString()))
                // Un administrador que pudiera leer el token podria entrar como el invitado.
                .andExpect(result -> {
                    String cuerpo = result.getResponse().getContentAsString();
                    if (cuerpo.contains(token)) {
                        throw new AssertionError("El listado de invitaciones devolvio el token en claro.");
                    }
                });
    }
}
