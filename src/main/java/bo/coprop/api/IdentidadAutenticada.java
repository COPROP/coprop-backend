package bo.coprop.api;

import java.io.Serializable;
import java.util.Collection;
import java.util.UUID;
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
 * <p>Hoy no lleva autoridades. Los roles por condominio no estan en el token a proposito (Seguridad
 * 4.2): se resuelven por peticion contra la base, y de eso se encargan el filtro de condominio del
 * issue #17 y la matriz del #16, que son quienes las pondran aqui.
 *
 * <p>Todo lo que guarda es serializable y pequeño porque en el flujo web esta autenticacion puede
 * acabar en la sesion del servidor, que viaja a la base de datos.
 */
final class IdentidadAutenticada extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final Actor actor;

    IdentidadAutenticada(Actor actor, Collection<? extends GrantedAuthority> autoridades) {
        super(autoridades);
        this.actor = actor;
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

    /**
     * El actor de una autenticacion que ya paso por el filtro de identidad.
     *
     * <p>Existe para que un controlador no tenga que castear {@code getPrincipal()}, que declara
     * {@code Object} y que NullAway da por anulable con razon: {@link org.springframework.security.core.Authentication}
     * es un contrato de Spring y no promete nada sobre lo que hay dentro.
     *
     * <p>Que no sea una {@code IdentidadAutenticada} <strong>no puede pasar</strong> en un
     * controlador: las tres cadenas exigen autenticacion en todo lo que no es publico, y el filtro
     * de identidad sustituye la autenticacion antes de la autorizacion. Si pasara, seria un cambio
     * en la cadena de seguridad y no una peticion rara, asi que revienta con lo que hace falta para
     * encontrarlo en vez de responder un 403 que mandaria a mirar los permisos.
     */
    static Actor actorDe(Authentication quien) {
        if (quien instanceof IdentidadAutenticada identidad) {
            return identidad.actor;
        }
        throw new IllegalStateException(
                "La peticion llego a un controlador sin identidad de COPROP resuelta; la autenticacion es %s. Revisar el orden del FiltroDeIdentidad en la cadena de seguridad."
                        .formatted(quien.getClass().getName()));
    }

    /**
     * La cuenta y la persona detras de la peticion.
     *
     * @param accountId la cuenta, que es lo que autentica
     * @param personId la persona de esa cuenta, que es lo que el resto del dominio referencia
     */
    record Actor(UUID accountId, UUID personId) implements Serializable {}
}
