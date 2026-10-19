package bo.coprop.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
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
 * Los dos criterios de aceptacion del issue <strong>#20</strong>, sobre HTTP.
 *
 * <ul>
 *   <li><em>"Un CONDO_ADMIN no puede asignar SUPER_ADMIN"</em>. Son <strong>dos puertas</strong> y
 *       hay que cerrar las dos, porque responden distinto y por motivos distintos: la ruta global de
 *       superadministradores le responde <strong>403</strong> --en una ruta global no tiene ninguna
 *       autoridad, y el permiso es de plataforma-- y el cambio de roles de una membresia le responde
 *       <strong>422</strong>, porque ese rol no cabe en una membresia, no importa quien lo pida.
 *   <li><em>"Un CONDO_ADMIN no puede tocar roles de otro condominio"</em>: <strong>404</strong>, no
 *       403. Un 403 confirmaria que ese condominio y esa membresia existen.
 * </ul>
 *
 * <p>No va en una transaccion: los filtros de la cadena de seguridad escriben por su cuenta y un
 * rollback del test no los alcanza. De ahi que cada caso use su propio condominio y sus propias
 * cuentas.
 *
 * <p><strong>El token se saca una sola vez por cuenta</strong>, y es importante: cada token de
 * prueba lleva un sujeto nuevo, y una cuenta ya vinculada no se revincula a otro sujeto (#13), asi
 * que un segundo token para la misma cuenta recibiria {@code NO_ACCOUNT}. De ahi {@link Quien}.
 */
@Import({TestcontainersConfiguration.class, TestTokenProvider.class, InvitationDoubles.class})
@SpringBootTest
@AutoConfigureMockMvc
class AdminRolesTests {

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

    /** Una cuenta y el unico token con el que entra. */
    private record Quien(AccountView account, String token) {

        UUID id() {
            return account.id();
        }
    }

    // --- Primer criterio: el rol de plataforma no lo reparte el administrador de un edificio ---

    @Test
    @DisplayName("un CONDO_ADMIN no concede el rol de plataforma: la ruta es global y alli no es nadie")
    void theCondoAdminDoesNotGrantThePlatformRole() throws Exception {
        UUID condominium = altaDeCondominio();
        Quien administrador = conRolEn(condominium, MembershipRole.CONDO_ADMIN);
        Quien alguien = cuentaNueva();

        // La ruta no cuelga de ningun condominio, asi que el filtro del #17 no corre y las unicas
        // autoridades de la peticion son las de plataforma, que no tiene. El 403 sale de la forma de
        // la ruta y no de una regla escrita aparte.
        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST,
                        "/api/v1/platform-admins",
                        administrador.token(),
                        "{\"accountId\":\"" + alguien.id() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_PERMISSION"));

        // Y tampoco los lista: quien administra la plataforma no es dato de un edificio.
        mockMvc.perform(authorizedRequest(HttpMethod.GET, "/api/v1/platform-admins", administrador.token(), null))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("un CONDO_ADMIN tampoco lo cuela por el cambio de roles de una membresia")
    void itDoesNotSlipThroughTheMembershipEither() throws Exception {
        UUID condominium = altaDeCondominio();
        Quien administrador = conRolEn(condominium, MembershipRole.CONDO_ADMIN);
        MembershipView ofAnOwner = anOwnersMembership(condominium);

        // 422 y no 403, y la diferencia dice algo: aqui no le falta permiso --un CONDO_ADMIN puede
        // conceder cualquier rol de su condominio-- sino que PLATFORM_ADMIN no es un rol de
        // condominio. Ni el superadministrador puede concederlo por esta puerta.
        mockMvc.perform(authorizedRequest(
                        HttpMethod.PUT,
                        laRutaDeRoles(condominium, ofAnOwner.id()),
                        administrador.token(),
                        "{\"roles\":[\"PLATFORM_ADMIN\"],\"reason\":\"que lo administre todo\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    @DisplayName("el superadministrador si concede el rol de plataforma, y el nuevo crea condominios")
    void thePlatformAdminDoesGrantIt() throws Exception {
        Quien quienConcede = cuentaDeSuperadministrador();
        Quien quienRecibe = cuentaNueva();

        // Antes: crear condominios es del superadministrador, y no lo es.
        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST, "/api/v1/condominiums", quienRecibe.token(), altaDeCondominioJson()))
                .andExpect(status().isForbidden());

        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST,
                        "/api/v1/platform-admins",
                        quienConcede.token(),
                        "{\"accountId\":\"" + quienRecibe.id() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value(quienRecibe.id().toString()))
                .andExpect(jsonPath("$.grantedBy").value(quienConcede.id().toString()));

        // Despues, con el MISMO token: el rol se resuelve en cada peticion y nunca estuvo en el
        // token, asi que no hay que volver a entrar.
        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST, "/api/v1/condominiums", quienRecibe.token(), altaDeCondominioJson()))
                .andExpect(status().isCreated());

        // Y la revocacion lo deshace en la siguiente peticion, por lo mismo.
        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST,
                        "/api/v1/platform-admins/" + quienRecibe.id() + "/revocation",
                        quienConcede.token(),
                        "{\"reason\":\"se equivocaron de account\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST, "/api/v1/condominiums", quienRecibe.token(), altaDeCondominioJson()))
                .andExpect(status().isForbidden());
    }

    // --- Segundo criterio: nunca fuera del propio condominio --------------------------------

    @Test
    @DisplayName("un CONDO_ADMIN no cambia roles de otro condominio: 404 y no 403")
    void neverOutsideYourOwnCondominium() throws Exception {
        UUID propio = altaDeCondominio();
        UUID ajeno = altaDeCondominio();
        Quien administrador = conRolEn(propio, MembershipRole.CONDO_ADMIN);
        MembershipView enElAjeno = anOwnersMembership(ajeno);

        String cuerpo = "{\"roles\":[\"CONDO_ADMIN\"],\"reason\":\"administrador del edificio de al side\"}";

        // Por la ruta del condominio ajeno lo corta el filtro del #17, que ni le deja llegar.
        mockMvc.perform(authorizedRequest(
                        HttpMethod.PUT, laRutaDeRoles(ajeno, enElAjeno.id()), administrador.token(), cuerpo))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // Y por la del propio --con el identificador de una membresia del ajeno, que es el intento
        // de verdad-- lo corta puedeCambiar, que exige que la membresia sea de ESTE condominio y
        // lanza en vez de devolver falso, para que la respuesta sea 404 y no 403.
        mockMvc.perform(authorizedRequest(
                        HttpMethod.PUT, laRutaDeRoles(propio, enElAjeno.id()), administrador.token(), cuerpo))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --- Y el camino bueno ------------------------------------------------------------------

    @Test
    @DisplayName("el administrador nombra a otro administrador, y surte efecto en la siguiente peticion")
    void theAdminAppointsAnother() throws Exception {
        UUID condominium = altaDeCondominio();
        Quien administrador = conRolEn(condominium, MembershipRole.CONDO_ADMIN);
        Quien owner = cuentaNueva();
        MembershipView suya = memberships.grant(condominium, owner.id(), Set.of(MembershipRole.OWNER), null, "admin");

        // Antes: un propietario no da de alta unidades.
        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST,
                        "/api/v1/condominiums/" + condominium + "/units",
                        owner.token(),
                        unidadConCodigo()))
                .andExpect(status().isForbidden());

        mockMvc.perform(authorizedRequest(
                        HttpMethod.PUT,
                        laRutaDeRoles(condominium, suya.id()),
                        administrador.token(),
                        "{\"roles\":[\"OWNER\",\"CONDO_ADMIN\"],\"reason\":\"se hace cargo del edificio\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(suya.id().toString()))
                // La fila es la misma y sigue abierta: un cambio de rol no es una baja.
                .andExpect(jsonPath("$.validTo").doesNotExist())
                .andExpect(jsonPath("$.roles.length()").value(2));

        // Despues, con el MISMO token: los roles se releen en cada peticion.
        mockMvc.perform(authorizedRequest(
                        HttpMethod.POST,
                        "/api/v1/condominiums/" + condominium + "/units",
                        owner.token(),
                        unidadConCodigo()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("el financiero no reparte roles, ni para quitarlos")
    void theFinanceAdminHandsOutNoRoles() throws Exception {
        UUID condominium = altaDeCondominio();
        Quien financeAdmin = conRolEn(condominium, MembershipRole.FINANCE_ADMIN);
        MembershipView deOtro = anOwnersMembership(condominium);

        // Es la otra mitad de la separacion de funciones de Seguridad 3: si pudiera repartir roles se
        // daria CONDO_ADMIN y la separacion no existiria. Vale igual para quitarlos.
        mockMvc.perform(authorizedRequest(
                        HttpMethod.PUT,
                        laRutaDeRoles(condominium, deOtro.id()),
                        financeAdmin.token(),
                        "{\"roles\":[\"TENANT\"],\"reason\":\"ahora alquila su departamento\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_PERMISSION"));
    }

    @Test
    @DisplayName("el superadministrador no toca los roles de un propietario, solo los administrativos")
    void thePlatformAdminTouchesOnlyAdminMatters() throws Exception {
        UUID condominium = altaDeCondominio();
        Quien superadministrador = cuentaDeSuperadministrador();
        MembershipView ofAnOwner = anOwnersMembership(condominium);
        MembershipView deUnAdministrador =
                memberships.grant(condominium, cuentaNueva().id(), Set.of(MembershipRole.CONDO_ADMIN), null, "admin");

        // La celda de la matriz dice "Solo administradores", y el cambio pide las DOS mitades: poder
        // quitar los que tiene y poder poner los que llegan. Sobre un propietario falla la primera.
        mockMvc.perform(authorizedRequest(
                        HttpMethod.PUT,
                        laRutaDeRoles(condominium, ofAnOwner.id()),
                        superadministrador.token(),
                        "{\"roles\":[\"CONDO_ADMIN\"],\"reason\":\"lo asciendo yo\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_PERMISSION"));

        // Sobre un administrador si, que es como pone al primero de un condominio recien creado...
        mockMvc.perform(authorizedRequest(
                        HttpMethod.PUT,
                        laRutaDeRoles(condominium, deUnAdministrador.id()),
                        superadministrador.token(),
                        "{\"roles\":[\"CONDO_ADMIN\",\"FINANCE_ADMIN\"],\"reason\":\"tambien lleva el dinero\"}"))
                .andExpect(status().isOk());

        // ...pero no para dejarle un rol que no es administrativo: eso es operar el condominio, y
        // Seguridad 10.1 dice que no opera. Aqui falla la segunda mitad.
        mockMvc.perform(authorizedRequest(
                        HttpMethod.PUT,
                        laRutaDeRoles(condominium, deUnAdministrador.id()),
                        superadministrador.token(),
                        "{\"roles\":[\"OWNER\"],\"reason\":\"que solo sea vecino\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_PERMISSION"));
    }

    private static String laRutaDeRoles(UUID condominium, UUID membership) {
        return "/api/v1/condominiums/" + condominium + "/memberships/" + membership + "/roles";
    }

    /** Una membresia de propietario de alguien que no entra en ningun caso: solo se le cambia. */
    private MembershipView anOwnersMembership(UUID condominium) {
        return memberships.grant(condominium, cuentaNueva().id(), Set.of(MembershipRole.OWNER), null, "admin");
    }

    private UUID altaDeCondominio() {
        return condominiums
                .register(
                        new NewCondominium(
                                "Roles " + UUID.randomUUID(),
                                nitNuevo(),
                                CondominiumType.BUILDING,
                                "America/La_Paz",
                                5,
                                10),
                        "admin")
                .id();
    }

    private static String altaDeCondominioJson() {
        return "{\"name\":\"Torre " + UUID.randomUUID() + "\",\"nit\":\"" + nitNuevo()
                + "\",\"type\":\"BUILDING\",\"timeZone\":\"America/La_Paz\",\"issueDay\":5,\"dueDay\":10}";
    }

    /** Un codigo distinto y valido por peticion: la validacion del cuerpo corre antes del permiso. */
    private static String unidadConCodigo() {
        String sufijo = UUID.randomUUID().toString().substring(0, 8);
        return "{\"code\":\"R-" + sufijo + "\",\"type\":\"APARTMENT\",\"aliquot\":\"1.000000\"}";
    }

    /** El NIT es unico por condominio, y estos tests comparten base con los demas. */
    private static String nitNuevo() {
        return String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_0000L));
    }

    private Quien cuentaNueva() {
        PersonView person = people.register(new NewPerson("Emma", "Tola", null), "admin");
        AccountView account = people.openAccount(person.id(), TestTokenProvider.correoNuevo(), null, "admin");
        String token = "Bearer " + TestTokenProvider.valid(TestTokenProvider.sujetoNuevo(), account.email());
        return new Quien(account, token);
    }

    private Quien conRolEn(UUID condominium, MembershipRole rol) {
        Quien principal = cuentaNueva();
        memberships.grant(condominium, principal.id(), Set.of(rol), null, "admin");
        return principal;
    }

    /**
     * Una cuenta con alcance de plataforma, sembrada por SQL.
     *
     * <p>Por SQL y no con la operacion nueva a proposito: para usarla haria falta ya tener un
     * superadministrador con el que autenticarse, y el primero de todos no lo concede nadie.
     */
    private Quien cuentaDeSuperadministrador() {
        Quien principal = cuentaNueva();
        new JdbcTemplate(dataSource)
                .update("insert into platform_admin (user_id, granted_at) values (?, now())", principal.id());
        return principal;
    }

    private MockHttpServletRequestBuilder authorizedRequest(
            HttpMethod metodo, String path, String authorizationHeader, @Nullable String cuerpo) {
        var constructor = request(metodo, path)
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader);
        if (cuerpo != null) {
            constructor.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        }
        return constructor;
    }
}
