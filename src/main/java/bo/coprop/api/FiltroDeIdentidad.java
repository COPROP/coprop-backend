package bo.coprop.api;

import bo.coprop.identity.AccountView;
import bo.coprop.identity.Identities;
import bo.coprop.shared.ClavesDeLog;
import bo.coprop.shared.CodigoDeError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Traduce la identidad del proveedor a la identidad de COPROP, una vez por peticion.
 *
 * <p>Es el punto donde "autenticado" deja de significar "tiene un token de Keycloak" y pasa a
 * significar "es alguien en COPROP". Un token perfectamente valido de alguien sin cuenta aqui
 * <strong>no pasa de este filtro</strong>: responde 403 con {@code SIN_CUENTA}. Es lo que pide
 * Seguridad 10.1, que el acceso lo de la invitacion y no el haberse autenticado, y lo que hace que
 * encender el login con Google no reparta acceso a nadie.
 *
 * <p>Corre <strong>antes de la autorizacion</strong> y no despues, aunque hoy nadie mire los roles:
 * cuando el issue #16 traiga la matriz de permisos, las autoridades tienen que estar puestas en la
 * autenticacion antes de que se decida si la peticion pasa.
 *
 * <p>Deja el identificador de la cuenta en el MDC ({@link ClavesDeLog#ACTOR}), que es la segunda
 * clave que el issue #6 dejo declarada y sin rellenar. A partir de aqui, toda linea de log de la
 * peticion dice quien la hizo.
 *
 * <p>No se registra como bean: Spring Boot registraria cualquier {@code Filter} que sea bean en la
 * cadena del servlet, donde correria antes de que exista autenticacion y no serviria de nada. Lo
 * instala la configuracion de seguridad en las cadenas que lo necesitan.
 */
class FiltroDeIdentidad extends OncePerRequestFilter {

    private final Identities identidades;
    private final EscritorDeProblemas problemas;

    FiltroDeIdentidad(Identities identidades, EscritorDeProblemas problemas) {
        this.identidades = identidades;
        this.problemas = problemas;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        SujetoDelToken sujeto = sujetoDe(autenticacion);

        // Rutas publicas --el OpenAPI, el arranque del login-- y cualquier cosa que no venga del
        // proveedor de identidad. Aqui no hay nada que resolver.
        if (autenticacion == null || sujeto == null) {
            cadena.doFilter(peticion, respuesta);
            return;
        }

        // Un token sin "sub" o sin correo no identifica a nadie. No deberia llegar --Keycloak
        // siempre pone el sub, y el correo lo pide el scope email-- pero si llega se responde lo
        // mismo que a quien no tiene cuenta, no un error distinto que invite a probar.
        String subject = sujeto.subject();
        String email = sujeto.email();
        AccountView cuenta =
                subject != null && email != null ? identidades.signIn(subject, email, sujeto.emailVerificado()) : null;
        if (cuenta == null) {
            problemas.escribir(
                    peticion,
                    respuesta,
                    CodigoDeError.SIN_CUENTA,
                    "Tu cuenta todavia no tiene acceso a COPROP. Pide tu invitacion al administrador de tu condominio.");
            return;
        }

        var identidad =
                new IdentidadAutenticada(new IdentidadAutenticada.Actor(cuenta.id(), cuenta.personId()), autenticacion);
        SecurityContextHolder.getContext().setAuthentication(identidad);
        MDC.put(ClavesDeLog.ACTOR, identidad.getName());
        try {
            cadena.doFilter(peticion, respuesta);
        } finally {
            MDC.remove(ClavesDeLog.ACTOR);
        }
    }

    /**
     * Los tres datos que hacen falta del token, vengan por donde vengan.
     *
     * <p>Son las dos formas de entrar de Seguridad 5: la app movil manda un JWT por la cabecera y
     * la web entra por el BFF, donde el token ya se cambio por una sesion y lo que queda es el
     * usuario OIDC. El resto de la aplicacion no distingue una de otra.
     */
    private static @Nullable SujetoDelToken sujetoDe(@Nullable Authentication autenticacion) {
        if (autenticacion == null || !autenticacion.isAuthenticated()) {
            return null;
        }
        Object quien = autenticacion.getPrincipal();
        if (quien instanceof Jwt token) {
            return new SujetoDelToken(
                    token.getSubject(),
                    token.getClaimAsString("email"),
                    Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified")));
        }
        if (quien instanceof OidcUser usuario) {
            return new SujetoDelToken(
                    usuario.getSubject(), usuario.getEmail(), Boolean.TRUE.equals(usuario.getEmailVerified()));
        }
        return null;
    }

    private record SujetoDelToken(@Nullable String subject, @Nullable String email, boolean emailVerificado) {}
}
