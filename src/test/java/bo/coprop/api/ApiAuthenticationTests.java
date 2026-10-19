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
import bo.coprop.shared.ApiController;
import bo.coprop.shared.LogKeys;
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
@Import({TestcontainersConfiguration.class, TestTokenProvider.class, ApiAuthenticationTests.ControladorQueRegistra.class
})
@SpringBootTest
@AutoConfigureMockMvc
class ApiAuthenticationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private People people;

    private AccountView openAccount(String email) {
        PersonView person = people.register(new NewPerson("Emma", "Tola", null), "admin");
        return people.openAccount(person.id(), email, null, "admin");
    }

    @Test
    @DisplayName("un token expirado devuelve 401 en problem+json")
    void anExpiredTokenReturns401() throws Exception {
        String expired = TestTokenProvider.token(
                TestTokenProvider.sujetoNuevo(),
                TestTokenProvider.correoNuevo(),
                true,
                Instant.now().minus(1, ChronoUnit.MINUTES),
                TestTokenProvider.AUDIENCIA,
                TestTokenProvider.CLAVE);

        mockMvc.perform(get("/api/v1/accounts/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("NOT_AUTHENTICATED"));
    }

    @Test
    @DisplayName("un token firmado con otra clave devuelve 401")
    void aTokenWithAnInvalidSignatureReturns401() throws Exception {
        // Mismo contenido, otra firma: es el token que trae quien se lo fabrico el solo.
        RSAKey otraClave = new RSAKeyGenerator(2048).keyID("intrusa").generate();
        String forged = TestTokenProvider.token(
                TestTokenProvider.sujetoNuevo(),
                TestTokenProvider.correoNuevo(),
                true,
                Instant.now().plus(5, ChronoUnit.MINUTES),
                TestTokenProvider.AUDIENCIA,
                otraClave);

        mockMvc.perform(get("/api/v1/accounts/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NOT_AUTHENTICATED"));
    }

    @Test
    @DisplayName("un token para otra audiencia no sirve contra esta API")
    void aTokenFromAnotherAudienceReturns401() throws Exception {
        // El mismo realm emite tokens para la consola de cuenta. Sin validar la audiencia, uno de
        // esos abriria la API entera.
        String ajeno = TestTokenProvider.token(
                TestTokenProvider.sujetoNuevo(),
                TestTokenProvider.correoNuevo(),
                true,
                Instant.now().plus(5, ChronoUnit.MINUTES),
                "account-console",
                TestTokenProvider.CLAVE);

        mockMvc.perform(get("/api/v1/accounts/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ajeno))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("sin token no se entra, y la respuesta es la del contrato")
    void withoutATokenThereIsNoEntry() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NOT_AUTHENTICATED"));
    }

    @Test
    @DisplayName("un token valido de quien no tiene cuenta autentica, pero no ve nada")
    void aTokenWithoutAnAccountSeesNothing() throws Exception {
        // El criterio del issue: autenticarse no es tener acceso. Es exactamente lo que pasara
        // cuando se encienda el login con Google y entre alguien a quien nadie invito.
        mockMvc.perform(get("/api/v1/accounts/" + UUID.randomUUID())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer "
                                        + TestTokenProvider.valid(
                                                TestTokenProvider.sujetoNuevo(), TestTokenProvider.correoNuevo())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("NO_ACCOUNT"));
    }

    @Test
    @DisplayName("un token con el correo sin verificar tampoco entra")
    void aTokenWithAnUnverifiedEmailDoesNotGetIn() throws Exception {
        String email = TestTokenProvider.correoNuevo();
        openAccount(email);

        String sinVerificar = TestTokenProvider.token(
                TestTokenProvider.sujetoNuevo(),
                email,
                false,
                Instant.now().plus(5, ChronoUnit.MINUTES),
                TestTokenProvider.AUDIENCIA,
                TestTokenProvider.CLAVE);

        mockMvc.perform(get("/api/v1/accounts/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sinVerificar))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_ACCOUNT"));
    }

    @Test
    @DisplayName("un token valido de quien si tiene cuenta entra y la deja activa")
    void aTokenWithAnAccountGetsIn() throws Exception {
        String email = TestTokenProvider.correoNuevo();
        AccountView account = openAccount(email);

        mockMvc.perform(get("/api/v1/accounts/" + account.id())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + TestTokenProvider.valid(TestTokenProvider.sujetoNuevo(), email)))
                .andExpect(status().isOk())
                // Entro invitada y el primer login la deja activa, sin que nadie mas intervenga.
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    @DisplayName("un segundo sujeto con el mismo correo no se queda con la cuenta")
    void aSecondSubjectDoesNotTakeOverTheAccount() throws Exception {
        String email = TestTokenProvider.correoNuevo();
        AccountView account = openAccount(email);

        // El primero entra y la cuenta queda vinculada a su sujeto.
        mockMvc.perform(get("/api/v1/accounts/" + account.id())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + TestTokenProvider.valid(TestTokenProvider.sujetoNuevo(), email)))
                .andExpect(status().isOk());

        // El segundo llega con otro sujeto y el mismo correo. El realm no deberia permitir dos
        // identidades con el mismo correo; si alguna vez pasara, la cuenta no cambia de dueño.
        mockMvc.perform(get("/api/v1/accounts/" + account.id())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + TestTokenProvider.valid(TestTokenProvider.sujetoNuevo(), email)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_ACCOUNT"));
    }

    @Test
    @DisplayName("toda linea de log de la peticion dice quien la hizo")
    void theActorIsRecordedInTheLog() throws Exception {
        String email = TestTokenProvider.correoNuevo();
        AccountView account = openAccount(email);

        var captured = new ListAppender<ILoggingEvent>();
        var registro = (Logger) LoggerFactory.getLogger(ControladorQueRegistra.Endpoints.class);
        captured.start();
        registro.addAppender(captured);
        registro.setLevel(Level.INFO);
        try {
            mockMvc.perform(get("/api/v1/identidad/una-linea")
                            .header(
                                    HttpHeaders.AUTHORIZATION,
                                    "Bearer " + TestTokenProvider.valid(TestTokenProvider.sujetoNuevo(), email)))
                    .andExpect(status().isOk());

            // La clave es la que el issue #6 dejo declarada y sin rellenar. Lo que se guarda es la
            // cuenta, que es lo que autentica, y es el mismo valor que acaba como actor en los
            // eventos del dominio.
            assertThat(captured.list).singleElement().satisfies(evento -> assertThat(evento.getMDCPropertyMap())
                    .containsEntry(LogKeys.ACTOR, account.id().toString())
                    .containsKey(LogKeys.TRAZA));
        } finally {
            registro.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    @DisplayName("el contrato de la API se sirve sin identidad")
    void theContractIsPublic() throws Exception {
        mockMvc.perform(get("/api/openapi.json")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("el health es publico y el resto del actuator no se sirve a nadie")
    void theActuatorIsNotServed() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

        // Ni siquiera con un token valido: las metricas de la plataforma no son de un propietario.
        // Sale 401 y no 403 porque esa cadena no tiene con que autenticar a nadie --no es resource
        // server-- asi que todo lo que deniega cae en el punto de entrada, no en el de permisos.
        mockMvc.perform(get("/actuator/metrics")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer "
                                        + TestTokenProvider.valid(
                                                TestTokenProvider.sujetoNuevo(), TestTokenProvider.correoNuevo())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NOT_AUTHENTICATED"));
    }

    /** Controlador de mentira que escribe una linea durante la peticion, para mirarle el MDC. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ControladorQueRegistra {

        @ApiController
        @RestController
        @RequestMapping("/identidad")
        static class Endpoints {

            private static final org.slf4j.Logger log = LoggerFactory.getLogger(Endpoints.class);

            @GetMapping("/una-linea")
            void oneLine() {
                log.info("algo que hace el dominio");
            }
        }
    }
}
