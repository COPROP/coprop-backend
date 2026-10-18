package bo.coprop.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.condominium.CondominiumType;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.DoblesDeInvitacion;
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
 * que un segundo token para la misma cuenta recibiria {@code SIN_CUENTA}. De ahi {@link Quien}.
 */
@Import({TestcontainersConfiguration.class, ProveedorDeTokensDePrueba.class, DoblesDeInvitacion.class})
@SpringBootTest
@AutoConfigureMockMvc
class RolesAdministrativosTests {

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

    /** Una cuenta y el unico token con el que entra. */
    private record Quien(AccountView cuenta, String token) {

        UUID id() {
            return cuenta.id();
        }
    }

    // --- Primer criterio: el rol de plataforma no lo reparte el administrador de un edificio ---

    @Test
    @DisplayName("un CONDO_ADMIN no concede el rol de plataforma: la ruta es global y alli no es nadie")
    void elAdministradorDelCondominioNoConcedeElRolDePlataforma() throws Exception {
        UUID condominio = altaDeCondominio();
        Quien administrador = conRolEn(condominio, MembershipRole.CONDO_ADMIN);
        Quien alguien = cuentaNueva();

        // La ruta no cuelga de ningun condominio, asi que el filtro del #17 no corre y las unicas
        // autoridades de la peticion son las de plataforma, que no tiene. El 403 sale de la forma de
        // la ruta y no de una regla escrita aparte.
        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/superadministradores",
                        administrador.token(),
                        "{\"accountId\":\"" + alguien.id() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));

        // Y tampoco los lista: quien administra la plataforma no es dato de un edificio.
        mockMvc.perform(peticion(HttpMethod.GET, "/api/v1/superadministradores", administrador.token(), null))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("un CONDO_ADMIN tampoco lo cuela por el cambio de roles de una membresia")
    void tampocoLoCuelaPorLaMembresia() throws Exception {
        UUID condominio = altaDeCondominio();
        Quien administrador = conRolEn(condominio, MembershipRole.CONDO_ADMIN);
        MembershipView deUnPropietario = membresiaDeUnPropietario(condominio);

        // 422 y no 403, y la diferencia dice algo: aqui no le falta permiso --un CONDO_ADMIN puede
        // conceder cualquier rol de su condominio-- sino que PLATFORM_ADMIN no es un rol de
        // condominio. Ni el superadministrador puede concederlo por esta puerta.
        mockMvc.perform(peticion(
                        HttpMethod.PUT,
                        laRutaDeRoles(condominio, deUnPropietario.id()),
                        administrador.token(),
                        "{\"roles\":[\"PLATFORM_ADMIN\"],\"motivo\":\"que lo administre todo\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("REGLA_DE_NEGOCIO"));
    }

    @Test
    @DisplayName("el superadministrador si concede el rol de plataforma, y el nuevo crea condominios")
    void elSuperadministradorSiLoConcede() throws Exception {
        Quien quienConcede = cuentaDeSuperadministrador();
        Quien quienRecibe = cuentaNueva();

        // Antes: crear condominios es del superadministrador, y no lo es.
        mockMvc.perform(peticion(HttpMethod.POST, "/api/v1/condominios", quienRecibe.token(), altaDeCondominioJson()))
                .andExpect(status().isForbidden());

        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/superadministradores",
                        quienConcede.token(),
                        "{\"accountId\":\"" + quienRecibe.id() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value(quienRecibe.id().toString()))
                .andExpect(jsonPath("$.grantedBy").value(quienConcede.id().toString()));

        // Despues, con el MISMO token: el rol se resuelve en cada peticion y nunca estuvo en el
        // token, asi que no hay que volver a entrar.
        mockMvc.perform(peticion(HttpMethod.POST, "/api/v1/condominios", quienRecibe.token(), altaDeCondominioJson()))
                .andExpect(status().isCreated());

        // Y la revocacion lo deshace en la siguiente peticion, por lo mismo.
        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/superadministradores/" + quienRecibe.id() + "/revocacion",
                        quienConcede.token(),
                        "{\"motivo\":\"se equivocaron de cuenta\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(peticion(HttpMethod.POST, "/api/v1/condominios", quienRecibe.token(), altaDeCondominioJson()))
                .andExpect(status().isForbidden());
    }

    // --- Segundo criterio: nunca fuera del propio condominio --------------------------------

    @Test
    @DisplayName("un CONDO_ADMIN no cambia roles de otro condominio: 404 y no 403")
    void nuncaFueraDelPropioCondominio() throws Exception {
        UUID propio = altaDeCondominio();
        UUID ajeno = altaDeCondominio();
        Quien administrador = conRolEn(propio, MembershipRole.CONDO_ADMIN);
        MembershipView enElAjeno = membresiaDeUnPropietario(ajeno);

        String cuerpo = "{\"roles\":[\"CONDO_ADMIN\"],\"motivo\":\"administrador del edificio de al lado\"}";

        // Por la ruta del condominio ajeno lo corta el filtro del #17, que ni le deja llegar.
        mockMvc.perform(peticion(HttpMethod.PUT, laRutaDeRoles(ajeno, enElAjeno.id()), administrador.token(), cuerpo))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));

        // Y por la del propio --con el identificador de una membresia del ajeno, que es el intento
        // de verdad-- lo corta puedeCambiar, que exige que la membresia sea de ESTE condominio y
        // lanza en vez de devolver falso, para que la respuesta sea 404 y no 403.
        mockMvc.perform(peticion(HttpMethod.PUT, laRutaDeRoles(propio, enElAjeno.id()), administrador.token(), cuerpo))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    // --- Y el camino bueno ------------------------------------------------------------------

    @Test
    @DisplayName("el administrador nombra a otro administrador, y surte efecto en la siguiente peticion")
    void elAdministradorNombraAOtro() throws Exception {
        UUID condominio = altaDeCondominio();
        Quien administrador = conRolEn(condominio, MembershipRole.CONDO_ADMIN);
        Quien propietario = cuentaNueva();
        MembershipView suya =
                membresias.grant(condominio, propietario.id(), Set.of(MembershipRole.OWNER), null, "admin");

        // Antes: un propietario no da de alta unidades.
        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/condominios/" + condominio + "/unidades",
                        propietario.token(),
                        unidadConCodigo()))
                .andExpect(status().isForbidden());

        mockMvc.perform(peticion(
                        HttpMethod.PUT,
                        laRutaDeRoles(condominio, suya.id()),
                        administrador.token(),
                        "{\"roles\":[\"OWNER\",\"CONDO_ADMIN\"],\"motivo\":\"se hace cargo del edificio\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(suya.id().toString()))
                // La fila es la misma y sigue abierta: un cambio de rol no es una baja.
                .andExpect(jsonPath("$.validTo").doesNotExist())
                .andExpect(jsonPath("$.roles.length()").value(2));

        // Despues, con el MISMO token: los roles se releen en cada peticion.
        mockMvc.perform(peticion(
                        HttpMethod.POST,
                        "/api/v1/condominios/" + condominio + "/unidades",
                        propietario.token(),
                        unidadConCodigo()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("el financiero no reparte roles, ni para quitarlos")
    void elFinancieroNoRepartoRoles() throws Exception {
        UUID condominio = altaDeCondominio();
        Quien financiero = conRolEn(condominio, MembershipRole.FINANCE_ADMIN);
        MembershipView deOtro = membresiaDeUnPropietario(condominio);

        // Es la otra mitad de la separacion de funciones de Seguridad 3: si pudiera repartir roles se
        // daria CONDO_ADMIN y la separacion no existiria. Vale igual para quitarlos.
        mockMvc.perform(peticion(
                        HttpMethod.PUT,
                        laRutaDeRoles(condominio, deOtro.id()),
                        financiero.token(),
                        "{\"roles\":[\"TENANT\"],\"motivo\":\"ahora alquila su departamento\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
    }

    @Test
    @DisplayName("el superadministrador no toca los roles de un propietario, solo los administrativos")
    void elSuperadministradorSoloTocaLoAdministrativo() throws Exception {
        UUID condominio = altaDeCondominio();
        Quien superadministrador = cuentaDeSuperadministrador();
        MembershipView deUnPropietario = membresiaDeUnPropietario(condominio);
        MembershipView deUnAdministrador =
                membresias.grant(condominio, cuentaNueva().id(), Set.of(MembershipRole.CONDO_ADMIN), null, "admin");

        // La celda de la matriz dice "Solo administradores", y el cambio pide las DOS mitades: poder
        // quitar los que tiene y poder poner los que llegan. Sobre un propietario falla la primera.
        mockMvc.perform(peticion(
                        HttpMethod.PUT,
                        laRutaDeRoles(condominio, deUnPropietario.id()),
                        superadministrador.token(),
                        "{\"roles\":[\"CONDO_ADMIN\"],\"motivo\":\"lo asciendo yo\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));

        // Sobre un administrador si, que es como pone al primero de un condominio recien creado...
        mockMvc.perform(peticion(
                        HttpMethod.PUT,
                        laRutaDeRoles(condominio, deUnAdministrador.id()),
                        superadministrador.token(),
                        "{\"roles\":[\"CONDO_ADMIN\",\"FINANCE_ADMIN\"],\"motivo\":\"tambien lleva el dinero\"}"))
                .andExpect(status().isOk());

        // ...pero no para dejarle un rol que no es administrativo: eso es operar el condominio, y
        // Seguridad 10.1 dice que no opera. Aqui falla la segunda mitad.
        mockMvc.perform(peticion(
                        HttpMethod.PUT,
                        laRutaDeRoles(condominio, deUnAdministrador.id()),
                        superadministrador.token(),
                        "{\"roles\":[\"OWNER\"],\"motivo\":\"que solo sea vecino\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
    }

    private static String laRutaDeRoles(UUID condominio, UUID membresia) {
        return "/api/v1/condominios/" + condominio + "/membresias/" + membresia + "/roles";
    }

    /** Una membresia de propietario de alguien que no entra en ningun caso: solo se le cambia. */
    private MembershipView membresiaDeUnPropietario(UUID condominio) {
        return membresias.grant(condominio, cuentaNueva().id(), Set.of(MembershipRole.OWNER), null, "admin");
    }

    private UUID altaDeCondominio() {
        return condominios
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
        PersonView persona = personas.register(new NewPerson("Emma", "Tola", null), "admin");
        AccountView cuenta = personas.openAccount(persona.id(), ProveedorDeTokensDePrueba.correoNuevo(), null, "admin");
        String token =
                "Bearer " + ProveedorDeTokensDePrueba.valido(ProveedorDeTokensDePrueba.sujetoNuevo(), cuenta.email());
        return new Quien(cuenta, token);
    }

    private Quien conRolEn(UUID condominio, MembershipRole rol) {
        Quien quien = cuentaNueva();
        membresias.grant(condominio, quien.id(), Set.of(rol), null, "admin");
        return quien;
    }

    /**
     * Una cuenta con alcance de plataforma, sembrada por SQL.
     *
     * <p>Por SQL y no con la operacion nueva a proposito: para usarla haria falta ya tener un
     * superadministrador con el que autenticarse, y el primero de todos no lo concede nadie.
     */
    private Quien cuentaDeSuperadministrador() {
        Quien quien = cuentaNueva();
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
