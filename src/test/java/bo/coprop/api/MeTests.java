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
@Import({TestcontainersConfiguration.class, ProveedorDeTokensDePrueba.class})
@SpringBootTest
@AutoConfigureMockMvc
class MeTests {

    private static final String ME = "/api/v1/me";

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
    private DataSource dataSource;

    @Test
    @DisplayName("Con dos membresias, la respuesta lista las dos con su rol")
    void conDosMembresiasListaLasDosConSuRol() throws Exception {
        UUID aurora = condominioLlamado("Aurora");
        UUID bolivar = condominioLlamado("Bolivar");
        Quien emma = cuentaNueva();
        membresias.grant(aurora, emma.cuenta(), Set.of(MembershipRole.CONDO_ADMIN), null, "admin");
        membresias.grant(bolivar, emma.cuenta(), Set.of(MembershipRole.OWNER, MembershipRole.BOARD), null, "admin");

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, emma.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominios.length()").value(2))
                // Ordenados por nombre, no por el orden en que se concedieron las membresias.
                .andExpect(jsonPath("$.condominios[0].condominio.name").value("Aurora"))
                .andExpect(jsonPath("$.condominios[0].roles").value("CONDO_ADMIN"))
                .andExpect(jsonPath("$.condominios[1].condominio.name").value("Bolivar"))
                .andExpect(jsonPath("$.condominios[1].roles.length()").value(2))
                .andExpect(jsonPath("$.persona.firstNames").value("Emma"))
                .andExpect(jsonPath("$.cuenta.email").value(emma.correo()))
                .andExpect(jsonPath("$.administraLaPlataforma").value(false));
    }

    @Test
    @DisplayName("Un request a un condominio sin membresia devuelve 404")
    void unRequestAUnCondominioSinMembresiaDevuelve404() throws Exception {
        UUID propio = condominioLlamado("Propio");
        UUID ajeno = condominioLlamado("Ajeno");
        Quien emma = cuentaNueva();
        membresias.grant(propio, emma.cuenta(), Set.of(MembershipRole.CONDO_ADMIN), null, "admin");

        mockMvc.perform(get("/api/v1/condominios/" + ajeno).header(HttpHeaders.AUTHORIZATION, emma.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));

        // La otra mitad del criterio: que el 404 no venga de una ruta mal escrita.
        mockMvc.perform(get("/api/v1/condominios/" + propio).header(HttpHeaders.AUTHORIZATION, emma.token()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("El perfil no nombra un condominio en el que no hay membresia")
    void elPerfilNoNombraUnCondominioEnElQueNoHayMembresia() throws Exception {
        UUID propio = condominioLlamado("Propio");
        condominioLlamado("Del vecino");
        Quien emma = cuentaNueva();
        membresias.grant(propio, emma.cuenta(), Set.of(MembershipRole.OWNER), null, "admin");

        // El 404 del criterio tapa el condominio cuando se pide por su ruta. Aqui no hay ruta que
        // tapar: si este endpoint devolviera de mas, enumeraria la plataforma entera de una vez.
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, emma.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominios.length()").value(1))
                .andExpect(jsonPath("$.condominios[0].condominio.id").value(propio.toString()));
    }

    @Test
    @DisplayName("Una membresia suspendida no se lista")
    void unaMembresiaSuspendidaNoSeLista() throws Exception {
        UUID condominio = condominioLlamado("Suspendido");
        Quien emma = cuentaNueva();
        UUID membresia = membresias
                .grant(condominio, emma.cuenta(), Set.of(MembershipRole.OWNER), null, "admin")
                .id();

        // Por SQL porque suspender es una operacion del issue #19 y todavia no existe. Lo que se
        // prueba no es como se suspende, sino que una suspendida no se ofrezca en el selector: el
        // filtro de aislamiento le responderia 404 a todo lo de ese condominio.
        new JdbcTemplate(dataSource)
                .update("update membership set status = ? where id = ?", MembershipStatus.SUSPENDIDA.name(), membresia);

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, emma.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominios").isEmpty());
    }

    @Test
    @DisplayName("Quien no tiene ninguna membresia recibe 200 con la lista vacia")
    void quienNoTieneNingunaMembresiaRecibe200ConLaListaVacia() throws Exception {
        condominioLlamado("Ninguno mio");
        Quien recienLlegada = cuentaNueva();

        // Ni 404 ni 403: la pregunta es sobre uno mismo y la respuesta correcta es "existes y no
        // estas en ningun condominio". Es el caso del superadministrador que abre el arranque, que
        // es justo quien tiene que poder crear el primero.
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, recienLlegada.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominios").isEmpty())
                .andExpect(
                        jsonPath("$.persona.id").value(recienLlegada.persona().toString()));
    }

    @Test
    @DisplayName("Cada unidad aparece una vez con todos sus papeles")
    void cadaUnidadApareceUnaVezConTodosSusPapeles() throws Exception {
        UUID condominio = condominioLlamado("Con unidades");
        Quien emma = cuentaNueva();
        membresias.grant(condominio, emma.cuenta(), Set.of(MembershipRole.OWNER), null, "admin");
        UUID suya = unidad(condominio, "A-1");
        UUID queOcupa = unidad(condominio, "B-2");

        // El reparto normal de un alquiler: de la suya es propietaria y responde por los cobros; la
        // otra solo la ocupa.
        relaciones.grantOwnership(condominio, suya, emma.persona(), new BigDecimal("100.00"), "admin");
        relaciones.assignBilling(condominio, suya, emma.persona(), null, "admin");
        relaciones.grantOccupancy(condominio, queOcupa, emma.persona(), OccupancyRole.INQUILINO, "admin");

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, emma.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominios[0].unidades.length()").value(2))
                .andExpect(jsonPath("$.condominios[0].unidades[0].code").value("A-1"))
                .andExpect(jsonPath("$.condominios[0].unidades[0].relations.length()")
                        .value(2))
                .andExpect(jsonPath("$.condominios[0].unidades[1].code").value("B-2"))
                .andExpect(jsonPath("$.condominios[0].unidades[1].relations").value("OCUPANTE"));
    }

    @Test
    @DisplayName("Las unidades de un condominio no se cuelgan de otro")
    void lasUnidadesDeUnCondominioNoSeCuelganDeOtro() throws Exception {
        UUID uno = condominioLlamado("Uno");
        UUID otro = condominioLlamado("Otro");
        Quien emma = cuentaNueva();
        membresias.grant(uno, emma.cuenta(), Set.of(MembershipRole.OWNER), null, "admin");
        membresias.grant(otro, emma.cuenta(), Set.of(MembershipRole.OWNER), null, "admin");
        relaciones.grantOwnership(uno, unidad(uno, "U-1"), emma.persona(), new BigDecimal("100.00"), "admin");

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, emma.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominios[0].condominio.name").value("Otro"))
                .andExpect(jsonPath("$.condominios[0].unidades").isEmpty())
                .andExpect(jsonPath("$.condominios[1].condominio.name").value("Uno"))
                .andExpect(jsonPath("$.condominios[1].unidades[0].code").value("U-1"));
    }

    @Test
    @DisplayName("El superadministrador se anuncia aparte y no como rol de un condominio")
    void elSuperadministradorSeAnunciaAparteYNoComoRolDeUnCondominio() throws Exception {
        UUID condominio = condominioLlamado("De la plataforma");
        Quien quien = cuentaNueva();
        new JdbcTemplate(dataSource)
                .update("insert into platform_admin (user_id, granted_at) values (?, now())", quien.cuenta());
        membresias.grant(condominio, quien.cuenta(), Set.of(MembershipRole.CONDO_ADMIN), null, "admin");

        // Aparte y no dentro de los roles del condominio: su alcance es la plataforma (Seguridad 3),
        // y meterlo ahi le haria creer al cliente que ese condominio se lo concedio.
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, quien.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.administraLaPlataforma").value(true))
                .andExpect(jsonPath("$.condominios[0].roles").value("CONDO_ADMIN"));
    }

    @Test
    @DisplayName("Sin un token valido no hay perfil")
    void sinUnTokenValidoNoHayPerfil() throws Exception {
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer no-es-un-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }

    @Test
    @DisplayName("Un token valido de quien no tiene cuenta en COPROP no da perfil")
    void unTokenValidoDeQuienNoTieneCuentaEnCopropNoDaPerfil() throws Exception {
        String token = "Bearer "
                + ProveedorDeTokensDePrueba.valido(
                        ProveedorDeTokensDePrueba.sujetoNuevo(), ProveedorDeTokensDePrueba.correoNuevo());

        // Lo rechaza el filtro de identidad, no este endpoint: autenticarse no reparte acceso
        // (Seguridad 10.1). Esta aqui porque es el primer sitio al que llega un cliente recien
        // logueado, y es donde se notaria si ese filtro dejara de correr.
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_CUENTA"));
    }

    /** Una cuenta recien abierta, con su persona y el token con el que entra. */
    private record Quien(UUID persona, UUID cuenta, String correo, String token) {}

    private Quien cuentaNueva() {
        PersonView persona = personas.register(new NewPerson("Emma", "Tola", null), "admin");
        AccountView cuenta = personas.openAccount(persona.id(), ProveedorDeTokensDePrueba.correoNuevo(), null, "admin");
        String token =
                "Bearer " + ProveedorDeTokensDePrueba.valido(ProveedorDeTokensDePrueba.sujetoNuevo(), cuenta.email());
        return new Quien(persona.id(), cuenta.id(), cuenta.email(), token);
    }

    private UUID condominioLlamado(String nombre) {
        return condominios
                .register(
                        new NewCondominium(nombre, nitNuevo(), CondominiumType.EDIFICIO, "America/La_Paz", 5, 10),
                        "admin")
                .id();
    }

    private UUID unidad(UUID condominio, String codigo) {
        return unidades.register(
                        condominio,
                        new NewUnit(codigo, UnitType.DEPARTAMENTO, new BigDecimal("1.000000"), null, null, null),
                        "admin")
                .id();
    }

    private static String nitNuevo() {
        return String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_0000L));
    }
}
