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
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
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
    AutenticacionDeApiTests.ProveedorDeTokensDePrueba.class,
    AutenticacionDeApiTests.ControladorQueRegistra.class
})
@SpringBootTest
@AutoConfigureMockMvc
class AutenticacionDeApiTests {

    private static final String EMISOR = "http://localhost:8081/realms/coprop";
    private static final String AUDIENCIA = "coprop-api";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private People personas;

    private static String sujetoNuevo() {
        return UUID.randomUUID().toString();
    }

    private static String correoNuevo() {
        return "emma-" + UUID.randomUUID() + "@coprop.bo";
    }

    private AccountView abrirCuenta(String correo) {
        PersonView persona = personas.register(new NewPerson("Emma", "Tola", null), "admin");
        return personas.openAccount(persona.id(), correo, null, "admin");
    }

    private static String token(
            String sujeto, String correo, boolean correoVerificado, Instant expira, String audiencia, RSAKey clave)
            throws Exception {
        var afirmaciones = new JWTClaimsSet.Builder()
                .subject(sujeto)
                .issuer(EMISOR)
                .audience(audiencia)
                .issueTime(Date.from(expira.minus(5, ChronoUnit.MINUTES)))
                .expirationTime(Date.from(expira))
                .claim("email", correo)
                .claim("email_verified", correoVerificado)
                .build();
        var firmado = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(clave.getKeyID())
                        .build(),
                afirmaciones);
        firmado.sign(new RSASSASigner(clave.toPrivateKey()));
        return firmado.serialize();
    }

    private static String tokenValido(String sujeto, String correo) throws Exception {
        return token(
                sujeto,
                correo,
                true,
                Instant.now().plus(5, ChronoUnit.MINUTES),
                AUDIENCIA,
                ProveedorDeTokensDePrueba.CLAVE);
    }

    @Test
    @DisplayName("un token expirado devuelve 401 en problem+json")
    void unTokenExpiradoDevuelve401() throws Exception {
        String expirado = token(
                sujetoNuevo(),
                correoNuevo(),
                true,
                Instant.now().minus(1, ChronoUnit.MINUTES),
                AUDIENCIA,
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
        String falsificado = token(
                sujetoNuevo(), correoNuevo(), true, Instant.now().plus(5, ChronoUnit.MINUTES), AUDIENCIA, otraClave);

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
        String ajeno = token(
                sujetoNuevo(),
                correoNuevo(),
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
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValido(sujetoNuevo(), correoNuevo())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.codigo").value("SIN_CUENTA"));
    }

    @Test
    @DisplayName("un token con el correo sin verificar tampoco entra")
    void unTokenConCorreoSinVerificarNoEntra() throws Exception {
        String correo = correoNuevo();
        abrirCuenta(correo);

        String sinVerificar = token(
                sujetoNuevo(),
                correo,
                false,
                Instant.now().plus(5, ChronoUnit.MINUTES),
                AUDIENCIA,
                ProveedorDeTokensDePrueba.CLAVE);

        mockMvc.perform(get("/api/v1/cuentas/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sinVerificar))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_CUENTA"));
    }

    @Test
    @DisplayName("un token valido de quien si tiene cuenta entra y la deja activa")
    void unTokenConCuentaEntra() throws Exception {
        String correo = correoNuevo();
        AccountView cuenta = abrirCuenta(correo);

        mockMvc.perform(get("/api/v1/cuentas/" + cuenta.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValido(sujetoNuevo(), correo)))
                .andExpect(status().isOk())
                // Entro invitada y el primer login la deja activa, sin que nadie mas intervenga.
                .andExpect(jsonPath("$.status").value("ACTIVO"))
                .andExpect(jsonPath("$.email").value(correo));
    }

    @Test
    @DisplayName("un segundo sujeto con el mismo correo no se queda con la cuenta")
    void unSegundoSujetoNoSeQuedaConLaCuenta() throws Exception {
        String correo = correoNuevo();
        AccountView cuenta = abrirCuenta(correo);

        // El primero entra y la cuenta queda vinculada a su sujeto.
        mockMvc.perform(get("/api/v1/cuentas/" + cuenta.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValido(sujetoNuevo(), correo)))
                .andExpect(status().isOk());

        // El segundo llega con otro sujeto y el mismo correo. El realm no deberia permitir dos
        // identidades con el mismo correo; si alguna vez pasara, la cuenta no cambia de dueño.
        mockMvc.perform(get("/api/v1/cuentas/" + cuenta.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValido(sujetoNuevo(), correo)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_CUENTA"));
    }

    @Test
    @DisplayName("toda linea de log de la peticion dice quien la hizo")
    void elActorQuedaEnElLog() throws Exception {
        String correo = correoNuevo();
        AccountView cuenta = abrirCuenta(correo);

        var capturados = new ListAppender<ILoggingEvent>();
        var registro = (Logger) LoggerFactory.getLogger(ControladorQueRegistra.Endpoints.class);
        capturados.start();
        registro.addAppender(capturados);
        registro.setLevel(Level.INFO);
        try {
            mockMvc.perform(get("/api/v1/identidad/una-linea")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValido(sujetoNuevo(), correo)))
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
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValido(sujetoNuevo(), correoNuevo())))
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

    /**
     * La clave con la que se firman los tokens del test, y el decodificador que la conoce.
     *
     * <p>Sustituye al decodificador de produccion, que descargaria el JWKS de Keycloak. Los
     * validadores son los mismos: emisor, expiracion y audiencia.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProveedorDeTokensDePrueba {

        static final RSAKey CLAVE = generar();

        private static RSAKey generar() {
            try {
                return new RSAKeyGenerator(2048).keyID("prueba").generate();
            } catch (Exception fallo) {
                throw new IllegalStateException("No se pudo generar la clave de prueba", fallo);
            }
        }

        @Bean
        @Primary
        JwtDecoder decodificadorDePrueba() throws Exception {
            var decodificador =
                    NimbusJwtDecoder.withPublicKey(CLAVE.toRSAPublicKey()).build();
            decodificador.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(EMISOR),
                    new JwtClaimValidator<List<String>>(
                            JwtClaimNames.AUD, audiencias -> audiencias != null && audiencias.contains(AUDIENCIA))));
            return decodificador;
        }
    }
}
