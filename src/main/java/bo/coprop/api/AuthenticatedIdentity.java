package bo.coprop.api;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Quien hace la peticion, ya traducido a la cuenta de COPROP.
 *
 * <p>Sustituye a la autenticacion que deja Spring Security --el JWT o el usuario OIDC-- en cuanto
 * el filtro de identidad resuelve la cuenta local. Asi {@code Principal.getName()} que reciben los
 * controladores es el identificador de la cuenta de COPROP y no el {@code sub} de Keycloak, y el
 * actor que acaba en la bitacora y en los eventos es el mismo que la aplicacion conoce. Los
 * controladores que ya existian no cambian: siguen recibiendo un {@link java.security.Principal}.
 *
 * <p>Lleva las autoridades que dos filtros le ponen: la de plataforma el de identidad --no depende
 * de ningun condominio, asi que vale tambien en las rutas globales-- y las del condominio el filtro
 * del issue #17, leyendo la membresia de la ruta. Ninguna viaja en el token a proposito (Seguridad
 * 4.2): se resuelven por peticion contra la base.
 *
 * <p>Lleva tambien <strong>como y cuando</strong> se autentico esa persona, que es lo que deciden
 * las operaciones sensibles del issue #22. Viaja aqui porque despues de este punto ya no hay un
 * {@code Jwt} ni un {@code OidcUser} del que sacarlo.
 *
 * <p>Todo lo que guarda es serializable y pequeño porque en el flujo web esta autenticacion puede
 * acabar en la sesion del servidor, que viaja a la base de datos.
 */
final class AuthenticatedIdentity extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final Actor actor;
    private final AuthenticationFacts authentication;

    AuthenticatedIdentity(
            Actor actor, AuthenticationFacts authentication, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.actor = actor;
        this.authentication = authentication;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        // No hay credencial que arrastrar: el token ya se valido y no vuelve a usarse.
        return "";
    }

    @Override
    public Object getPrincipal() {
        return actor;
    }

    /** El identificador de la cuenta. Es lo que se guarda como actor en eventos y bitacora. */
    @Override
    public String getName() {
        return actor.accountId().toString();
    }

    /** Como y cuando se autentico, para quien ya tiene la identidad en la mano. */
    AuthenticationFacts authentication() {
        return authentication;
    }

    /**
     * Como y cuando se autentico quien hace la peticion, o nada si esta autenticacion no paso por
     * el filtro de identidad.
     *
     * <p>No revienta como {@link #actorOf}: lo usa la comprobacion de reautenticacion del issue
     * #22, que autoriza, y una autorizacion que no sabe como se autentico quien pregunta tiene que
     * <strong>exigir</strong> el segundo factor, no caerse.
     */
    static @Nullable AuthenticationFacts authenticationFactsOf(Authentication principal) {
        return principal instanceof AuthenticatedIdentity identity ? identity.authentication : null;
    }

    /**
     * El actor de una autenticacion que ya paso por el filtro de identidad.
     *
     * <p>Existe para que un controlador no tenga que castear {@code getPrincipal()}, que declara
     * {@code Object} y que NullAway da por anulable con razon: {@link org.springframework.security.core.Authentication}
     * es un contrato de Spring y no promete nada sobre lo que hay dentro.
     *
     * <p>Que no sea una {@code AuthenticatedIdentity} <strong>no puede pasar</strong> en un
     * controlador: las tres cadenas exigen autenticacion en todo lo que no es publico, y el filtro
     * de identidad sustituye la autenticacion antes de la autorizacion. Si pasara, seria un cambio
     * en la cadena de seguridad y no una peticion rara, asi que revienta con lo que hace falta para
     * encontrarlo en vez de responder un 403 que mandaria a mirar los permisos.
     */
    static Actor actorOf(Authentication principal) {
        if (principal instanceof AuthenticatedIdentity identity) {
            return identity.actor;
        }
        throw new IllegalStateException(
                "La peticion llego a un controlador sin identidad de COPROP resuelta; la autenticacion es %s. Revisar el orden del IdentityFilter en la cadena de seguridad."
                        .formatted(principal.getClass().getName()));
    }

    /**
     * La cuenta y la persona detras de la peticion.
     *
     * @param accountId la cuenta, que es lo que autentica
     * @param personId la persona de esa cuenta, que es lo que el resto del dominio referencia
     */
    record Actor(UUID accountId, UUID personId) implements Serializable {}

    /**
     * Como y cuando demostro su identidad, tal como lo cuenta el proveedor. Issue <strong>#22</strong>.
     *
     * <p>Son las dos afirmaciones que Seguridad 5.3 pide que lleve el token y que 7.3 usa para
     * decidir si una operacion sensible puede pasar. <strong>Se guardan aqui porque este objeto
     * sustituye a la autenticacion del proveedor</strong>: a partir del filtro de identidad ya no
     * hay un {@code Jwt} ni un {@code OidcUser} del que sacarlas, y una operacion sensible que las
     * leyera del contexto encontraria la identidad de COPROP y nada mas.
     *
     * <p>Los dos pueden faltar. Un proveedor no esta obligado a mandar {@code amr} ni
     * {@code auth_time}, y un token sin ellos no puede demostrar un segundo factor reciente: se le
     * exige reautenticarse, que es lo que hay que hacer cuando no se sabe.
     *
     * @param cuando el {@code auth_time}: cuando se autentico de verdad, que no es cuando se emitio
     *     el token. Un token recien renovado con un refresh de hace tres dias trae {@code iat} de
     *     hace un segundo y {@code auth_time} de hace tres dias, y lo que importa es el segundo
     * @param metodos el {@code amr}: con que lo hizo. {@code otp} y {@code hwk} son los dos que
     *     Seguridad 7.3 acepta como segundo factor
     */
    record AuthenticationFacts(@Nullable Instant cuando, Set<String> metodos) implements Serializable {

        /** Una autenticacion de la que no se sabe nada: ni cuando fue ni con que. */
        static final AuthenticationFacts DESCONOCIDA = new AuthenticationFacts(null, Set.of());

        AuthenticationFacts {
            metodos = Set.copyOf(metodos);
        }
    }
}
