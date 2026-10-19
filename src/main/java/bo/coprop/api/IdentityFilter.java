package bo.coprop.api;

import bo.coprop.identity.AccountView;
import bo.coprop.identity.Identities;
import bo.coprop.identity.MembershipRole;
import bo.coprop.shared.ErrorCode;
import bo.coprop.shared.LogKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Traduce la identidad del proveedor a la identidad de COPROP, una vez por peticion.
 *
 * <p>Es el punto donde "autenticado" deja de significar "tiene un token de Keycloak" y pasa a
 * significar "es alguien en COPROP". Un token perfectamente valido de alguien sin cuenta aqui
 * <strong>no pasa de este filtro</strong>: responde 403 con {@code NO_ACCOUNT}. Es lo que pide
 * Seguridad 10.1, que el acceso lo de la invitacion y no el haberse autenticado, y lo que hace que
 * encender el login con Google no reparta acceso a nadie.
 *
 * <p>Corre <strong>antes de la autorizacion</strong> y no despues, aunque hoy nadie mire los roles:
 * cuando el issue #16 traiga la matriz de permisos, las autoridades tienen que estar puestas en la
 * autenticacion antes de que se decida si la peticion pasa.
 *
 * <p>Deja el identificador de la cuenta en el MDC ({@link LogKeys#ACTOR}), que es la segunda
 * clave que el issue #6 dejo declarada y sin rellenar. A partir de aqui, toda linea de log de la
 * peticion dice quien la hizo.
 *
 * <p>No se registra como bean: Spring Boot registraria cualquier {@code Filter} que sea bean en la
 * cadena del servlet, donde correria antes de que exista autenticacion y no serviria de nada. Lo
 * instala la configuracion de seguridad en las cadenas que lo necesitan.
 */
class IdentityFilter extends OncePerRequestFilter {

    private final Identities identities;
    private final ProblemWriter problemas;

    IdentityFilter(Identities identities, ProblemWriter problemas) {
        this.identities = identities;
        this.problemas = problemas;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain cadena)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        TokenSubject tokenSubject = tokenSubjectOf(authentication);

        // Rutas publicas --el OpenAPI, el arranque del login-- y cualquier cosa que no venga del
        // proveedor de identidad. Aqui no hay nada que resolver.
        if (authentication == null || tokenSubject == null) {
            cadena.doFilter(request, response);
            return;
        }

        // Un token sin "sub" o sin correo no identifica a nadie. No deberia llegar --Keycloak
        // siempre pone el sub, y el correo lo pide el scope email-- pero si llega se responde lo
        // mismo que a quien no tiene cuenta, no un error distinto que invite a probar.
        String subject = tokenSubject.subject();
        String email = tokenSubject.email();
        AccountView account = subject != null && email != null
                ? identities.signIn(subject, email, tokenSubject.emailVerified())
                : null;
        if (account == null) {
            problemas.escribir(
                    request,
                    response,
                    ErrorCode.NO_ACCOUNT,
                    "Tu cuenta todavia no tiene acceso a COPROP. Pide tu invitacion al administrador de tu condominio.");
            return;
        }

        // La autoridad de plataforma se pone aqui y no en el filtro de condominio porque no depende
        // de ninguno: el superadministrador la lleva tambien en las rutas globales, que es donde
        // crea condominios.
        var authorities = identities.esSuperadministrador(account.id())
                ? List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + MembershipRole.PLATFORM_ADMIN.name()))
                : List.<GrantedAuthority>of();
        var identity = new AuthenticatedIdentity(
                new AuthenticatedIdentity.Actor(account.id(), account.personId()),
                tokenSubject.authentication(),
                authorities);
        SecurityContextHolder.getContext().setAuthentication(identity);
        MDC.put(LogKeys.ACTOR, identity.getName());
        try {
            cadena.doFilter(request, response);
        } finally {
            MDC.remove(LogKeys.ACTOR);
        }
    }

    /**
     * Lo que hace falta del token, venga por donde venga.
     *
     * <p>Son las dos formas de entrar de Seguridad 5: la app movil manda un JWT por la cabecera y
     * la web entra por el BFF, donde el token ya se cambio por una sesion y lo que queda es el
     * usuario OIDC. El resto de la aplicacion no distingue una de otra.
     *
     * <p>Desde el issue #22 salen tambien {@code amr} y {@code auth_time}, que es lo unico que
     * puede contestar si una operacion sensible pasa (Seguridad 7.3). <strong>Se leen aqui y en
     * ningun otro sitio</strong>: este filtro sustituye la autenticacion del proveedor por la de
     * COPROP, asi que es el ultimo punto de la peticion donde el token todavia esta a la vista.
     */
    private static @Nullable TokenSubject tokenSubjectOf(@Nullable Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt token) {
            return new TokenSubject(
                    token.getSubject(),
                    token.getClaimAsString("email"),
                    Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified")),
                    new AuthenticatedIdentity.AuthenticationFacts(
                            token.getClaimAsInstant("auth_time"), metodosDe(token.getClaimAsStringList("amr"))));
        }
        if (principal instanceof OidcUser user) {
            return new TokenSubject(
                    user.getSubject(),
                    user.getEmail(),
                    Boolean.TRUE.equals(user.getEmailVerified()),
                    new AuthenticatedIdentity.AuthenticationFacts(
                            user.getAuthenticatedAt(), metodosDe(user.getClaimAsStringList("amr"))));
        }
        return null;
    }

    /**
     * El {@code amr} como conjunto, en minusculas.
     *
     * <p>Es una lista en el token y aqui interesa la pertenencia, no el orden. En minusculas
     * porque el RFC 8176 registra los valores asi y lo que se compara con ellos es literal: un
     * proveedor que mandara {@code OTP} haria fallar el segundo factor en silencio.
     */
    private static Set<String> metodosDe(@Nullable List<String> amr) {
        if (amr == null) {
            return Set.of();
        }
        return amr.stream().map(metodo -> metodo.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    private record TokenSubject(
            @Nullable String subject,
            @Nullable String email,
            boolean emailVerified,
            AuthenticatedIdentity.AuthenticationFacts authentication) {}
}
