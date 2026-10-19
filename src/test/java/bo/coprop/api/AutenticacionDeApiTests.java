package bo.coprop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.identity.PersonView;
import bo.coprop.shared.ClavesDeLog;
import bo.coprop.shared.ControladorDeApi;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los dos criterios de aceptacion del issue #13, vistos desde HTTP.
 *
 * <p>Los tokens se firman de verdad con una clave generada en el test, en vez de usar el
 * {@code jwt()} de spring-security-test. Eso importa: {@code jwt()} salta el decodificador, asi que
 * con el no se podria comprobar lo unico que este test tiene que comprobar --que un token expirado,
 * mal firmado o con otra audiencia se rechaza--. Lo unico fingido es de donde sale la clave
 * publica; los validadores son los mismos que corren en produccion.
 *
 * <p>Cada caso usa <strong>su propio sujeto y su propio correo</strong>. No es mania: estos tests
 * no van en una transaccion --el filtro de identidad escribe por su cuenta y un rollback del test
 * no lo alcanzaria-- asi que comparten base. Reutilizar el sujeto hizo que un caso entrara con el
 * vinculo que habia dejado otro, y el fallo salio como un 404 en vez de un 403, que no se parece
 * en nada a la causa.
 */
@Import({
    TestcontainersConfiguration.class,
    ProveedorDeTokensDePrueba.class,
    AutenticacionDeApiTests.ControladorQueRegistra.class
})
@SpringBootTest
@AutoConfigureMockMvc
class AutenticacionDeApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private People personas;

    private AccountView abrirCuenta(String correo) {
        PersonView persona = personas.register(new NewPerson("Emma", "Tola", null), "admin");
        return personas.openAccount(persona.id(), correo, null, "admin");
    }

    @Test
    @DisplayName("un token expirado devuelve 401 en problem+json")
    void unTokenExpiradoDevuelve401() throws Exception {
        String expirado = ProveedorDeTokensDePrueba.token(
                ProveedorDeTokensDePrueba.sujetoNuevo(),
                ProveedorDeTokensDePrueba.correoNuevo(),
                true,
                Instant.now().minus(1, ChronoUnit.MINUTES),
                ProveedorDeTokensDePrueba.AUDIENCIA,
                ProveedorDeTokensDePrueba.CLAVE);

        mockMvc.perform(get("/api/v1/cuentas/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expirado))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }

    @Test
    @DisplayName("un token firmado con otra clave devuelve 401")
    void unTokenConFirmaInvalidaDevuelve401() throws Exception {
        // Mismo contenido, otra firma: es el token que trae quien se lo fabrico el solo.
        RSAKey otraClave = new RSAKeyGenerator(2048).keyID("intrusa").generate();
        String falsificado = ProveedorDeTokensDePrueba.token(
                ProveedorDeTokensDePrueba.sujetoNuevo(),
                ProveedorDeTokensDePrueba.correoNuevo(),
                true,
                Instant.now().plus(5, ChronoUnit.MINUTES),
                ProveedorDeTokensDePrueba.AUDIENCIA,
                otraClave);

        mockMvc.perform(get("/api/v1/cuentas/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + falsificado))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }

    @Test
    @DisplayName("un token para otra audiencia no sirve contra esta API")
    void unTokenDeOtraAudienciaDevuelve401() throws Exception {
        // El mismo realm emite tokens para la consola de cuenta. Sin validar la audiencia, uno de
        // esos abriria la API entera.
        String ajeno = ProveedorDeTokensDePrueba.token(
                ProveedorDeTokensDePrueba.sujetoNuevo(),
                ProveedorDeTokensDePrueba.correoNuevo(),
                true,
                Instant.now().plus(5, ChronoUnit.MINUTES),
                "account-console",
                ProveedorDeTokensDePrueba.CLAVE);

        mockMvc.perform(get("/api/v1/cuentas/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ajeno))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("sin token no se entra, y la respuesta es la del contrato")
    void sinTokenNoSeEntra() throws Exception {
        mockMvc.perform(get("/api/v1/cuentas/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }

    @Test
    @DisplayName("un token valido de quien no tiene cuenta autentica, pero no ve nada")
    void unTokenSinCuentaNoVeNada() throws Exception {
        // El criterio del issue: autenticarse no es tener acceso. Es exactamente lo que pasara
        // cuando se encienda el login con Google y entre alguien a quien nadie invito.
        mockMvc.perform(get("/api/v1/cuentas/" + UUID.randomUUID())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer "
                                        + ProveedorDeTokensDePrueba.valido(
                                                ProveedorDeTokensDePrueba.sujetoNuevo(),
                                                ProveedorDeTokensDePrueba.correoNuevo())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.codigo").value("SIN_CUENTA"));
    }

    @Test
    @DisplayName("un token con el correo sin verificar tampoco entra")
    void unTokenConCorreoSinVerificarNoEntra() throws Exception {
        String correo = ProveedorDeTokensDePrueba.correoNuevo();
        abrirCuenta(correo);

        String sinVerificar = ProveedorDeTokensDePrueba.token(
                ProveedorDeTokensDePrueba.sujetoNuevo(),
                correo,
                false,
                Instant.now().plus(5, ChronoUnit.MINUTES),
                ProveedorDeTokensDePrueba.AUDIENCIA,
                ProveedorDeTokensDePrueba.CLAVE);

        mockMvc.perform(get("/api/v1/cuentas/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sinVerificar))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_CUENTA"));
    }

    @Test
    @DisplayName("un token valido de quien si tiene cuenta entra y la deja activa")
    void unTokenConCuentaEntra() throws Exception {
        String correo = ProveedorDeTokensDePrueba.correoNuevo();
        AccountView cuenta = abrirCuenta(correo);

        mockMvc.perform(get("/api/v1/cuentas/" + cuenta.id())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer "
                                        + ProveedorDeTokensDePrueba.valido(
                                                ProveedorDeTokensDePrueba.sujetoNuevo(), correo)))
                .andExpect(status().isOk())
                // Entro invitada y el primer login la deja activa, sin que nadie mas intervenga.
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.email").value(correo));
    }

    @Test
    @DisplayName("un segundo sujeto con el mismo correo no se queda con la cuenta")
    void unSegundoSujetoNoSeQuedaConLaCuenta() throws Exception {
        String correo = ProveedorDeTokensDePrueba.correoNuevo();
        AccountView cuenta = abrirCuenta(correo);

        // El primero entra y la cuenta queda vinculada a su sujeto.
        mockMvc.perform(get("/api/v1/cuentas/" + cuenta.id())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer "
                                        + ProveedorDeTokensDePrueba.valido(
                                                ProveedorDeTokensDePrueba.sujetoNuevo(), correo)))
                .andExpect(status().isOk());

        // El segundo llega con otro sujeto y el mismo correo. El realm no deberia permitir dos
        // identidades con el mismo correo; si alguna vez pasara, la cuenta no cambia de dueño.
        mockMvc.perform(get("/api/v1/cuentas/" + cuenta.id())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer "
                                        + ProveedorDeTokensDePrueba.valido(
                                                ProveedorDeTokensDePrueba.sujetoNuevo(), correo)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_CUENTA"));
    }

    @Test
    @DisplayName("toda linea de log de la peticion dice quien la hizo")
    void elActorQuedaEnElLog() throws Exception {
        String correo = ProveedorDeTokensDePrueba.correoNuevo();
        AccountView cuenta = abrirCuenta(correo);

        var capturados = new ListAppender<ILoggingEvent>();
        var registro = (Logger) LoggerFactory.getLogger(ControladorQueRegistra.Endpoints.class);
        capturados.start();
        registro.addAppender(capturados);
        registro.setLevel(Level.INFO);
        try {
            mockMvc.perform(get("/api/v1/identidad/una-linea")
                            .header(
                                    HttpHeaders.AUTHORIZATION,
                                    "Bearer "
                                            + ProveedorDeTokensDePrueba.valido(
                                                    ProveedorDeTokensDePrueba.sujetoNuevo(), correo)))
                    .andExpect(status().isOk());

            // La clave es la que el issue #6 dejo declarada y sin rellenar. Lo que se guarda es la
            // cuenta, que es lo que autentica, y es el mismo valor que acaba como actor en los
            // eventos del dominio.
            assertThat(capturados.list).singleElement().satisfies(evento -> assertThat(evento.getMDCPropertyMap())
                    .containsEntry(ClavesDeLog.ACTOR, cuenta.id().toString())
                    .containsKey(ClavesDeLog.TRAZA));
        } finally {
            registro.detachAppender(capturados);
            capturados.stop();
        }
    }

    @Test
    @DisplayName("el contrato de la API se sirve sin identidad")
    void elContratoEsPublico() throws Exception {
        mockMvc.perform(get("/api/openapi.json")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("el health es publico y el resto del actuator no se sirve a nadie")
    void elActuatorNoSeSirve() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

        // Ni siquiera con un token valido: las metricas de la plataforma no son de un propietario.
        // Sale 401 y no 403 porque esa cadena no tiene con que autenticar a nadie --no es resource
        // server-- asi que todo lo que deniega cae en el punto de entrada, no en el de permisos.
        mockMvc.perform(get("/actuator/metrics")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer "
                                        + ProveedorDeTokensDePrueba.valido(
                                                ProveedorDeTokensDePrueba.sujetoNuevo(),
                                                ProveedorDeTokensDePrueba.correoNuevo())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }

    /** Controlador de mentira que escribe una linea durante la peticion, para mirarle el MDC. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ControladorQueRegistra {

        @ControladorDeApi
        @RestController
        @RequestMapping("/identidad")
        static class Endpoints {

            private static final org.slf4j.Logger log = LoggerFactory.getLogger(Endpoints.class);

            @GetMapping("/una-linea")
            void unaLinea() {
                log.info("algo que hace el dominio");
            }
        }
    }
}
