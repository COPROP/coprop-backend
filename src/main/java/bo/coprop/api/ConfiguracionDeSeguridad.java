package bo.coprop.api;

import bo.coprop.identity.Identities;
import bo.coprop.shared.CodigoDeError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.ObjectMapper;

/**
 * Las cadenas de seguridad de Seguridad 7.1, una por forma de llegar a la aplicacion.
 *
 * <p>Son tres y no una porque autenticar no se hace igual en cada una, y mezclarlas obligaria a
 * decidir dentro de cada regla. En orden:
 *
 * <ol>
 *   <li><strong>Operacion</strong> ({@code /actuator/**}): el health es publico y lo demas no se
 *       sirve a nadie. No es una cadena "pendiente": es que el actuator no se protege con usuarios,
 *       se protege no exponiendolo. La credencial tecnica que menciona 7.1 entra cuando exista el
 *       worker de la seccion 9.
 *   <li><strong>API movil</strong> ({@code /api/**} con {@code Authorization: Bearer}): resource
 *       server con JWT, sin estado y sin CSRF, porque no hay credencial ambiente que robar.
 *   <li><strong>BFF web</strong> (el resto): {@code oauth2Login} con sesion en el servidor y CSRF.
 *       El navegador nunca ve un token.
 * </ol>
 *
 * <p>La cadena movil se distingue por la <strong>cabecera</strong>, no por la ruta: las dos sirven
 * {@code /api/**}, que es el mismo contrato visto desde dos clientes. Si se distinguieran por ruta
 * habria dos arboles de URLs que mantener en paralelo.
 *
 * <p>Lo que falta y tiene dueño: la cadena de webhooks por firma llega con los pagos
 * (issue <strong>#41</strong>), que es cuando existe {@code /webhooks/**} y hay una firma que
 * comprobar; crear la ruta antes seria abrir un agujero sin nada detras.
 */
@Configuration(proxyBeanMethods = false)
class ConfiguracionDeSeguridad {

    /**
     * El registro del cliente de la web. Tiene que llamarse igual que en el realm: Spring arma la
     * URL de vuelta como {@code /login/oauth2/code/{registrationId}} y el realm solo admite esa.
     */
    static final String REGISTRO_WEB = "coprop-web";

    /** Lo unico de la API que se sirve sin identidad: el contrato, que no tiene datos de nadie. */
    private static final String[] PUBLICAS = {"/api/openapi.json"};

    /**
     * Lo que necesita la consola navegable de springdoc, que solo existe en desarrollo.
     *
     * <p>{@code swagger-config} es la llamada que la consola hace antes de pintar nada, y cuelga de
     * la ruta de la especificacion, que aqui no es la de por defecto.
     */
    private static final String[] DE_LA_CONSOLA = {
        "/swagger-ui.html", "/swagger-ui/**", "/api/openapi.json/swagger-config"
    };

    private final String urlDeLaWeb;
    private final boolean consolaEncendida;

    ConfiguracionDeSeguridad(
            @Value("${coprop.web.url}") String urlDeLaWeb,
            @Value("${springdoc.swagger-ui.enabled:false}") boolean consolaEncendida) {
        this.urlDeLaWeb = urlDeLaWeb;
        this.consolaEncendida = consolaEncendida;
    }

    /**
     * Las rutas que se sirven sin identidad.
     *
     * <p>Las de la consola entran <strong>atadas a la misma propiedad que la enciende</strong>. Con
     * dos interruptores separados --uno para la consola y otro para abrirle las rutas-- lo que pasa
     * tarde o temprano es que quedan en desacuerdo, y el que sobra abre rutas que ya no sirven a
     * nada. En produccion el webjar ni siquiera esta en el artefacto.
     */
    private String[] sinIdentidad() {
        if (!consolaEncendida) {
            return PUBLICAS;
        }
        return Stream.concat(Arrays.stream(PUBLICAS), Arrays.stream(DE_LA_CONSOLA))
                .toArray(String[]::new);
    }

    @Bean
    EscritorDeProblemas escritorDeProblemas(ObjectMapper mapeador) {
        return new EscritorDeProblemas(mapeador);
    }

    @Bean
    @Order(1)
    SecurityFilterChain cadenaDeOperacion(HttpSecurity http, EscritorDeProblemas problemas) throws Exception {
        return http.securityMatcher("/actuator/**")
                .authorizeHttpRequests(rutas -> rutas.requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        // Metricas y modulith no se sirven por HTTP a nadie, ni siquiera a un
                        // usuario autenticado: un propietario con token valido no tiene por que
                        // poder leer las metricas de la plataforma. Quien las necesite las lee por
                        // la red interna, que es lo que dice 7.1.
                        .anyRequest()
                        .denyAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errores -> errores.authenticationEntryPoint((peticion, respuesta, fallo) ->
                                problemas.escribir(peticion, respuesta, CodigoDeError.NO_AUTENTICADO, SIN_TOKEN))
                        .accessDeniedHandler((peticion, respuesta, fallo) ->
                                problemas.escribir(peticion, respuesta, CodigoDeError.SIN_PERMISO, SIN_ROL)))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain cadenaDeApiMovil(HttpSecurity http, EscritorDeProblemas problemas, Identities identidades)
            throws Exception {
        return http.securityMatcher(nuevaPeticionConBearer())
                .authorizeHttpRequests(rutas -> rutas.requestMatchers(sinIdentidad())
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                // La firma, el emisor, la audiencia y la expiracion los comprueba Spring con lo
                // configurado en spring.security.oauth2.resourceserver. La rotacion de claves sale
                // gratis: el decodificador relee el JWKS cuando aparece un "kid" que no conoce.
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((peticion, respuesta, fallo) ->
                                problemas.escribir(peticion, respuesta, CodigoDeError.NO_AUTENTICADO, SIN_TOKEN))
                        .accessDeniedHandler((peticion, respuesta, fallo) ->
                                problemas.escribir(peticion, respuesta, CodigoDeError.SIN_PERMISO, SIN_ROL)))
                .addFilterBefore(new FiltroDeIdentidad(identidades, problemas), AuthorizationFilter.class)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain cadenaDelBffWeb(
            HttpSecurity http,
            EscritorDeProblemas problemas,
            Identities identidades,
            ClientRegistrationRepository registros)
            throws Exception {
        return http.authorizeHttpRequests(rutas -> rutas.requestMatchers(sinIdentidad())
                        .permitAll()
                        .requestMatchers(BffController.LOGIN)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2Login(login ->
                        login.loginPage("/oauth2/authorization/" + REGISTRO_WEB).defaultSuccessUrl(urlDeLaWeb, true))
                .logout(salida -> salida.logoutSuccessHandler(cierreEnKeycloak(registros)))
                .csrf(csrf -> csrf.csrfTokenRepository(repositorioDeCsrf())
                        // Con el manejador por defecto, el token solo se materializa cuando algo lo
                        // pide, asi que la cookie no se escribe y la SPA nunca tiene que mandar. Con
                        // el nombre de atributo en null se resuelve siempre y la cookie sale desde
                        // la primera respuesta.
                        .csrfTokenRequestHandler(manejadorDeCsrf()))
                .addFilterBefore(new FiltroDeIdentidad(identidades, problemas), AuthorizationFilter.class)
                .exceptionHandling(errores -> errores
                        // Una llamada de la SPA que perdio la sesion tiene que recibir un 401 que
                        // pueda leer, no un 302 hacia Keycloak: la redireccion la haria el XHR y el
                        // navegador acabaria pintando el login dentro de una respuesta JSON.
                        .defaultAuthenticationEntryPointFor(
                                (peticion, respuesta, fallo) -> problemas.escribir(
                                        peticion, respuesta, CodigoDeError.NO_AUTENTICADO, SIN_SESION),
                                esDeLaApi())
                        .accessDeniedHandler((peticion, respuesta, fallo) ->
                                problemas.escribir(peticion, respuesta, CodigoDeError.SIN_PERMISO, SIN_ROL)))
                .build();
    }

    /**
     * Cerrar sesion aqui tambien la cierra en Keycloak.
     *
     * <p>Si solo se borrara la sesion local, el siguiente login entraria solo --Keycloak todavia
     * tiene la suya-- y el usuario que pulso "salir" en una maquina compartida seguiria dentro.
     */
    private OidcClientInitiatedLogoutSuccessHandler cierreEnKeycloak(ClientRegistrationRepository registros) {
        var manejador = new OidcClientInitiatedLogoutSuccessHandler(registros);
        manejador.setPostLogoutRedirectUri(urlDeLaWeb);
        return manejador;
    }

    /** Nombres de cookie y cabecera: los que Angular usa de serie, para no configurar el cliente. */
    private static CookieCsrfTokenRepository repositorioDeCsrf() {
        var repositorio = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repositorio.setHeaderName("X-XSRF-TOKEN");
        return repositorio;
    }

    private static CsrfTokenRequestAttributeHandler manejadorDeCsrf() {
        var manejador = new CsrfTokenRequestAttributeHandler();
        manejador.setCsrfRequestAttributeName(null);
        return manejador;
    }

    /**
     * Una peticion con {@code Authorization: Bearer}, venga a donde venga.
     *
     * <p>Se mira la cabecera y no la ruta porque la app movil y la web piden las mismas rutas. Sin
     * cabecera, la peticion cae en la cadena del BFF y se resuelve con la cookie.
     */
    private static RequestMatcher nuevaPeticionConBearer() {
        return (HttpServletRequest peticion) -> {
            String cabecera = peticion.getHeader(HttpHeaders.AUTHORIZATION);
            return cabecera != null && cabecera.toLowerCase(Locale.ROOT).startsWith("bearer ");
        };
    }

    private static RequestMatcher esDeLaApi() {
        return (HttpServletRequest peticion) ->
                peticion.getRequestURI().startsWith(ConfiguracionDeRutas.PREFIJO_DE_VERSION);
    }

    private static final String SIN_TOKEN = "Hace falta un token valido para esta operacion.";
    private static final String SIN_SESION = "Tu sesion no esta iniciada o ya vencio.";
    private static final String SIN_ROL = "Tu rol no alcanza para esta operacion.";
}
