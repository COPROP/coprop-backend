package bo.coprop.api;

import bo.coprop.shared.CodigoDeError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

/**
 * Cadena minima de seguridad.
 *
 * <p>Alcance deliberadamente corto: existe para que el 401 y el 403 salgan en problem+json como el
 * resto del contrato, y para que el health deje de responder 401. Las cuatro cadenas reales que
 * describe Seguridad 7.1 -- BFF web con cookie, API movil con Bearer, webhooks por firma y
 * actuator en red interna -- llegan con el issue #13, en M1.
 *
 * <p>Sin sesion: la API es sin estado y la identidad vendra en el token.
 */
@Configuration(proxyBeanMethods = false)
class ConfiguracionDeSeguridad {

    @Bean
    SecurityFilterChain cadenaDeApi(HttpSecurity http, ObjectMapper mapeador) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        rutas -> rutas.requestMatchers("/actuator/health", "/actuator/health/**", "/api/openapi.json")
                                .permitAll()
                                .anyRequest()
                                .authenticated())
                .exceptionHandling(errores -> errores.authenticationEntryPoint((peticion, respuesta, fallo) -> escribir(
                                mapeador,
                                respuesta,
                                peticion,
                                CodigoDeError.NO_AUTENTICADO,
                                "Hace falta un token valido para esta operacion."))
                        .accessDeniedHandler((peticion, respuesta, fallo) -> escribir(
                                mapeador,
                                respuesta,
                                peticion,
                                CodigoDeError.SIN_PERMISO,
                                "Tu rol no alcanza para esta operacion.")))
                .build();
    }

    /**
     * Escribe el problem+json a mano. Aqui no hay controlador todavia, asi que el manejador de
     * excepciones no interviene: sin esto, Spring Security responderia un cuerpo vacio y el
     * contrato tendria dos formatos de error segun donde falle.
     */
    private static void escribir(
            ObjectMapper mapeador,
            HttpServletResponse respuesta,
            HttpServletRequest peticion,
            CodigoDeError codigo,
            String detalle)
            throws IOException {
        ProblemDetail problema = CatalogoDeProblemas.problema(codigo, detalle, peticion.getRequestURI());
        respuesta.setStatus(CatalogoDeProblemas.estadoDe(codigo).value());
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapeador.writeValue(respuesta.getOutputStream(), problema);
    }
}
