package bo.coprop.api;

import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.MembershipStatus;
import bo.coprop.identity.Memberships;
import bo.coprop.identity.PermissionMatrix;
import bo.coprop.shared.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Los tiempos de sesion de Seguridad 5.3, que no se pueden poner en un archivo de configuracion.
 * Issue <strong>#22</strong>.
 *
 * <p>La politica es <strong>una hora de inactividad para los roles administrativos y veinticuatro
 * para el resto, con un tope de veinticuatro en todo caso</strong> (§10.1). Hasta este issue,
 * {@code spring.session.jdbc.timeout} la dejaba en 24 horas para todo el mundo, con una nota que
 * apuntaba aqui.
 *
 * <p><strong>Por que no es configuracion.</strong> Spring Session tiene un unico tiempo de
 * inactividad para la aplicacion entera, y lo que distingue a un administrador de un propietario no
 * esta en la sesion: los roles cuelgan de una membresia por condominio y viven en la base de COPROP
 * (Seguridad 4.2), no en el token ni en la cookie. La misma cuenta puede administrar un edificio y
 * ser propietaria en otro. Asi que la unica forma de aplicar la politica es resolverla
 * <strong>por peticion</strong>, que es lo que hace esto.
 *
 * <p><strong>El tope absoluto no lo da Spring Session</strong>, que solo conoce inactividad: una
 * sesion que se usa cada cincuenta minutos no caduca nunca. El limite de 24 horas se comprueba aqui
 * contra la hora de creacion de la sesion, y cuando se pasa la sesion se invalida en el acto y la
 * peticion se responde 401. La diferencia con la inactividad importa: contra una sesion robada, lo
 * que la cierra es el tope, porque el ladron la mantiene viva usandola.
 *
 * <p>Solo esta en la cadena del BFF. La app movil no tiene sesion de servidor --es
 * {@code STATELESS}-- y su equivalente son la vida del access token y la rotacion del refresh, que
 * gobierna el realm de Keycloak y no esta aplicacion.
 *
 * <p>Cuesta <strong>una consulta por peticion</strong>, la de las membresias de la cuenta, y se
 * paga a proposito. La alternativa --decidirlo al crear la sesion y guardarlo-- dejaria a quien
 * acaba de recibir un rol administrativo con una sesion de 24 horas hasta su siguiente login, que
 * es el error en la direccion que no conviene. Al reves no hace falta: a quien <em>pierde</em> un
 * rol administrativo se le cierran las sesiones en el acto desde el issue #20.
 */
class SessionValidityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionValidityFilter.class);

    /** Inactividad de quien tiene algun rol administrativo, o administra la plataforma. */
    static final Duration ADMINISTRATIVA = Duration.ofHours(1);

    /** Inactividad del resto: propietarios, inquilinos, directiva, conserjeria. */
    static final Duration ORDINARIA = Duration.ofHours(24);

    /** Duracion maxima de una sesion, se use o no se use. */
    static final Duration MAXIMA = Duration.ofHours(24);

    private final Memberships memberships;
    private final ProblemWriter problemas;
    private final Clock clock;

    SessionValidityFilter(Memberships memberships, ProblemWriter problemas, Clock clock) {
        this.memberships = memberships;
        this.problemas = problemas;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain cadena)
            throws ServletException, IOException {
        // Sin sesion no hay nada que gobernar: una ruta publica, o el propio login antes de crearla.
        // Nunca se crea una aqui --getSession(false)--: crear sesiones es cosa de oauth2Login, y
        // hacerlo en un filtro le daria cookie a cualquiera que pase por una ruta publica.
        HttpSession session = request.getSession(false);
        Authentication principal = SecurityContextHolder.getContext().getAuthentication();
        if (session == null || principal == null) {
            cadena.doFilter(request, response);
            return;
        }
        var actor = actorOf(principal);
        if (actor == null) {
            cadena.doFilter(request, response);
            return;
        }

        Instant created = Instant.ofEpochMilli(session.getCreationTime());
        Duration edad = Duration.between(created, clock.instant());
        if (edad.compareTo(MAXIMA) > 0) {
            log.info(
                    "La sesion de la cuenta {} alcanzo la duracion maxima ({} horas) y se cierra; llevaba abierta {} horas (Seguridad 5.3).",
                    actor.accountId(),
                    MAXIMA.toHours(),
                    edad.toHours());
            session.invalidate();
            SecurityContextHolder.clearContext();
            problemas.escribir(
                    request,
                    response,
                    ErrorCode.NOT_AUTHENTICATED,
                    "Tu sesion alcanzo su duracion maxima. Vuelve a iniciar sesion.");
            return;
        }

        Duration inactivity = inactividadDe(principal, actor.accountId());
        if (session.getMaxInactiveInterval() != (int) inactivity.toSeconds()) {
            // Solo cuando cambia. Tocarlo en cada peticion marcaria la sesion como sucia y la
            // escribiria en la base aunque no hubiera cambiado nada.
            session.setMaxInactiveInterval((int) inactivity.toSeconds());
            log.debug(
                    "La sesion de la cuenta {} pasa a {} minutos de inactividad.",
                    actor.accountId(),
                    inactivity.toMinutes());
        }
        cadena.doFilter(request, response);
    }

    /**
     * Una hora si tiene algun rol administrativo en algun sitio, veinticuatro si no.
     *
     * <p><strong>Basta con uno</strong>, en cualquier condominio: la sesion es una sola y vale para
     * todos los edificios, asi que la politica que se le aplica tiene que ser la mas estricta de
     * las que le tocan. Quien administra un edificio y es propietario en otro tiene una sesion
     * administrativa, porque desde ella puede administrar.
     *
     * <p>Las suspendidas no cuentan: no dan acceso a nada, asi que tampoco endurecen nada.
     */
    private Duration inactividadDe(Authentication principal, UUID accountId) {
        String deLaPlataforma = "ROLE_" + MembershipRole.PLATFORM_ADMIN.name();
        boolean managesThePlatform = principal.getAuthorities().stream()
                .anyMatch(authority -> deLaPlataforma.equals(authority.getAuthority()));
        if (managesThePlatform) {
            return ADMINISTRATIVA;
        }
        boolean managesAnyCondominium = memberships.ofAccount(accountId).stream()
                .filter(membership -> membership.status() == MembershipStatus.ACTIVE)
                .flatMap(membership -> membership.roles().stream())
                .anyMatch(PermissionMatrix::esAdministrativo);
        return managesAnyCondominium ? ADMINISTRATIVA : ORDINARIA;
    }

    private static AuthenticatedIdentity.@Nullable Actor actorOf(Authentication principal) {
        return principal.getPrincipal() instanceof AuthenticatedIdentity.Actor actor ? actor : null;
    }
}
