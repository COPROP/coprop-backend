package bo.coprop.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.DoblesDeAcceso;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.identity.PersonView;
import bo.coprop.identity.ProveedorDeIdentidad.SesionDelProveedor;
import bo.coprop.identity.Sesiones.SesionDelBff;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.assertj.core.api.Assertions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code GET /api/v1/me/sesiones} y su cierre: la lista de sesiones activas que pide Seguridad 8 y
 * el "cerrar sesion en todos los dispositivos" de 5.3. Issue <strong>#22</strong>.
 *
 * <p>Lo que se prueba aqui es la <strong>composicion de las dos mitades</strong>, que es donde esta
 * la decision: una lista que mirara solo la sesion del BFF diria que no hay nadie dentro mientras la
 * app movil sigue renovando su token. Por eso los dos dobles vienen sembrados y los dos tienen que
 * aparecer en la respuesta.
 *
 * <p>Las sesiones se siembran en los dobles y no en Spring Session porque lo que decide esta clase
 * no es como se guardan --eso lo prueba {@code SesionesDelBffTests} contra la tabla de verdad-- sino
 * que las dos salgan juntas, ordenadas y por la ruta correcta.
 */
@Import({TestcontainersConfiguration.class, ProveedorDeTokensDePrueba.class, DoblesDeAcceso.class})
@SpringBootTest
@AutoConfigureMockMvc
class MisSesionesTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private People personas;

    @Autowired
    private DoblesDeAcceso.SesionesDePrueba sesionesDelBff;

    @Autowired
    private DoblesDeAcceso.ProveedorDeIdentidadDePrueba proveedor;

    @BeforeEach
    void reiniciarLosDobles() {
        // Los dobles son beans del contexto y el contexto se comparte: sin esto, las sesiones de un
        // caso aparecen en la lista del siguiente y el que simula un proveedor caido los hunde.
        sesionesDelBff.reiniciar();
        proveedor.reiniciar();
    }

    @Test
    @DisplayName("la lista junta la sesion del navegador y la del proveedor")
    void laListaJuntaLasDosMitades() throws Exception {
        Quien quien = cuentaQueYaEntro();
        Instant ahora = Instant.now();

        sesionesDelBff.conSesionAbierta(
                quien.sujeto(),
                new SesionDelBff(
                        "del-navegador",
                        ahora.minus(2, ChronoUnit.HOURS),
                        ahora.minus(30, ChronoUnit.MINUTES),
                        Duration.ofHours(1)));
        proveedor.conSesionAbierta(
                quien.cuenta().email(),
                new SesionDelProveedor(
                        "del-telefono",
                        "190.129.1.1",
                        ahora.minus(3, ChronoUnit.DAYS),
                        ahora.minus(1, ChronoUnit.MINUTES)));

        mockMvc.perform(peticion(HttpMethod.GET, "/api/v1/me/sesiones", quien.token(), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // De la mas usada a la menos: la del telefono se uso hace un minuto.
                .andExpect(jsonPath("$[0].id").value("del-telefono"))
                .andExpect(jsonPath("$[0].origen").value("PROVIDER"))
                .andExpect(jsonPath("$[0].ip").value("190.129.1.1"))
                // El proveedor gobierna sus propios tiempos, asi que de esa no se sabe cuando vence.
                .andExpect(jsonPath("$[0].expira").doesNotExist())
                .andExpect(jsonPath("$[1].id").value("del-navegador"))
                .andExpect(jsonPath("$[1].origen").value("BROWSER"))
                // La del navegador no trae IP: la tabla de la V6 no guarda de donde vino.
                .andExpect(jsonPath("$[1].ip").doesNotExist())
                .andExpect(jsonPath("$[1].expira").exists());
    }

    @Test
    @DisplayName("ninguna es la actual cuando se pregunta desde la app movil")
    void desdeLaAppMovilNingunaEsLaActual() throws Exception {
        Quien quien = cuentaQueYaEntro();
        Instant ahora = Instant.now();
        sesionesDelBff.conSesionAbierta(
                quien.sujeto(), new SesionDelBff("del-navegador", ahora, ahora, Duration.ofHours(24)));

        // La peticion llega con Bearer, asi que no hay sesion de servidor con la que compararse.
        // Marcarla como actual seria mentir: quien pregunta desde el telefono no esta en esa.
        mockMvc.perform(peticion(HttpMethod.GET, "/api/v1/me/sesiones", quien.token(), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actual").value(false));
    }

    @Test
    @DisplayName("sin ninguna sesion abierta responde 200 con lista vacia, no 404")
    void sinSesionesLaListaEstaVacia() throws Exception {
        Quien quien = cuentaQueYaEntro();

        mockMvc.perform(peticion(HttpMethod.GET, "/api/v1/me/sesiones", quien.token(), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("un proveedor caido devuelve media lista, no un error")
    void elProveedorCaidoDevuelveMediaLista() throws Exception {
        Quien quien = cuentaQueYaEntro();
        Instant ahora = Instant.now();
        sesionesDelBff.conSesionAbierta(
                quien.sujeto(), new SesionDelBff("del-navegador", ahora, ahora, Duration.ofHours(24)));
        proveedor.queFalle();

        // Quien mira esta pantalla esta intentando averiguar si le robaron la cuenta. Dejarle sin
        // respuesta porque Keycloak no contesta no le ayuda a decidir nada; media lista si.
        mockMvc.perform(peticion(HttpMethod.GET, "/api/v1/me/sesiones", quien.token(), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].origen").value("BROWSER"));
    }

    @Test
    @DisplayName("el cierre pide las dos puertas con los dos nombres de la cuenta")
    void elCierrePideLasDosPuertas() throws Exception {
        Quien quien = cuentaQueYaEntro();
        Instant ahora = Instant.now();
        sesionesDelBff.conSesionAbierta(
                quien.sujeto(), new SesionDelBff("del-navegador", ahora, ahora, Duration.ofHours(24)));

        mockMvc.perform(peticion(HttpMethod.POST, "/api/v1/me/sesiones/cierre", quien.token(), null))
                .andExpect(status().isNoContent());

        // Los dos nombres, por lo mismo que en la baja del #19: hoy el indice es el sujeto del
        // proveedor, y pasar tambien el de la cuenta deja el cierre a salvo del dia que cambie.
        Assertions.assertThat(sesionesDelBff.cerradas())
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.collection(String.class))
                .containsExactlyInAnyOrder(quien.sujeto(), quien.cuenta().id().toString());

        // Y la del proveedor tambien, que es la que sostiene el refresh token de la app movil.
        Assertions.assertThat(proveedor.aQuienSeLeCerraronSesiones())
                .contains(quien.cuenta().email());

        // Despues no queda ninguna.
        mockMvc.perform(peticion(HttpMethod.GET, "/api/v1/me/sesiones", quien.token(), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // --- Armado -----------------------------------------------------------------------------

    /** Una cuenta, su sujeto en el proveedor y el token con el que entra. */
    private record Quien(AccountView cuenta, String sujeto, String token) {}

    /**
     * Una cuenta que ya hizo una peticion, asi que su sujeto esta vinculado.
     *
     * <p>Hace falta la primera peticion: el vinculo lo escribe el filtro de identidad en el primer
     * login, y sin el la cuenta no tiene {@code idpSubject}, que es uno de los dos nombres con los
     * que se buscan sus sesiones.
     */
    private Quien cuentaQueYaEntro() throws Exception {
        PersonView persona = personas.register(new NewPerson("Emma", "Tola", null), "admin");
        AccountView cuenta = personas.openAccount(persona.id(), ProveedorDeTokensDePrueba.correoNuevo(), null, "admin");
        String sujeto = ProveedorDeTokensDePrueba.sujetoNuevo();
        String token = "Bearer " + ProveedorDeTokensDePrueba.valido(sujeto, cuenta.email());

        mockMvc.perform(peticion(HttpMethod.GET, "/api/v1/me", token, null)).andExpect(status().isOk());
        return new Quien(cuenta, sujeto, token);
    }

    private MockHttpServletRequestBuilder peticion(
            HttpMethod metodo, String ruta, String autorizacion, @Nullable String cuerpo) {
        var constructor = request(metodo, ruta)
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .header(HttpHeaders.AUTHORIZATION, autorizacion);
        if (cuerpo != null) {
            constructor
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(cuerpo);
        }
        return constructor;
    }
}
