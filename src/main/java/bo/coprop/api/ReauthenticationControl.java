package bo.coprop.api;

import bo.coprop.condominium.Condominiums;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.PermissionMatrix;
import bo.coprop.shared.ReauthenticationRequired;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * El cuarto control de Seguridad 7.2: <strong>segundo factor reciente</strong>. Issue
 * <strong>#22</strong>.
 *
 * <p>Los tres primeros ya estaban --identidad, membresia y rol, relacion con el recurso-- y los
 * pasa toda peticion. Este lo añaden solo las operaciones que Seguridad 7.3 llama sensibles, y
 * pregunta algo que ninguno de los otros pregunta: no quien eres ni que puedes, sino
 * <strong>cuando y como lo demostraste</strong>. Una sesion robada pasa los tres primeros sin
 * despeinarse; este no, porque el ladron no tiene el segundo factor.
 *
 * <p>Vive aqui por lo mismo que {@link AccessControl}: lo que se comprueba son las afirmaciones
 * del token, que es cosa de la capa web, y los controladores lo invocan por nombre de bean
 * ({@code @reautenticacion}) desde {@code @PreAuthorize}.
 *
 * <h2>Se escribe despues de la autorizacion, nunca antes</h2>
 *
 * En la expresion va en segundo lugar --{@code @authorization.canChange(...) and
 * @reauthentication.recent(authentication)}-- y el orden importa: SpEL corta en el primer
 * {@code false}, asi que a quien no tiene permiso se le responde <strong>403</strong> sin pedirle
 * nada. Al reves, le pediriamos el segundo factor a alguien que, despues de ponerlo, recibiria un
 * 403 igual; y de paso le confirmariamos que la operacion existe.
 *
 * <h2>Por que 401 y no 403</h2>
 *
 * Nunca devuelve {@code false}: o pasa o levanta {@link ReauthenticationRequired}, que sale como
 * <strong>401</strong> con la cabecera de desafio del RFC 9470. Es el mismo recurso que el 404 del
 * #15 --una excepcion lanzada dentro de la expresion, que SpEL deja pasar sin envolver-- y por el
 * mismo motivo: devolver {@code false} siempre acaba en 403, y un 403 le dice al cliente "no
 * puedes", que es falso. Si puede; le falta demostrarlo.
 *
 * <h2>Lo que hoy no puede comprobar</h2>
 *
 * Que el segundo factor sea <em>de esta operacion</em> y no de hace cuatro minutos y medio en otra.
 * RFC 9470 resuelve eso con {@code acr_values} y un nivel por operacion; aqui alcanza con la
 * ventana, que es lo que Seguridad 7.3 pide. La diferencia importa el dia que dos operaciones
 * sensibles pidan niveles distintos.
 */
@Component("reauthentication")
class ReauthenticationControl {

    private static final Logger log = LoggerFactory.getLogger(ReauthenticationControl.class);

    /**
     * La ventana de Seguridad 7.3, que es tambien el {@code max_age} del desafio.
     *
     * <p>Cinco minutos: lo bastante para encadenar varias operaciones sensibles sin volver a pedir
     * el factor, y lo bastante poco para que una pantalla desatendida no sirva para usarlo.
     */
    private static final Duration VIGENCIA = Duration.ofMinutes(5);

    /**
     * Los metodos que cuentan como segundo factor, del registro del RFC 8176.
     *
     * <p>{@code otp} es el codigo de la aplicacion de autenticacion y {@code hwk} la llave fisica.
     * <strong>{@code pwd} no esta y no puede estar</strong>: volver a escribir la contraseña no es
     * un segundo factor, es el primero otra vez, y contra un ladron de sesion que la sabe no sirve
     * de nada.
     */
    private static final Set<String> SEGUNDO_FACTOR = Set.of("otp", "hwk");

    private final Clock clock;

    /**
     * El puerto del condominio, para saber si el NIT que llega en el cuerpo es un cambio de verdad.
     *
     * <p>Es la direccion que permite la regla 6: {@code api} declara {@code condominium}, y quien
     * sabe de HTTP pregunta a quien sabe de condominios.
     */
    private final Condominiums condominiums;

    ReauthenticationControl(Clock clock, Condominiums condominiums) {
        this.clock = clock;
        this.condominiums = condominiums;
    }

    /**
     * Exige que esta peticion venga con segundo factor de hace menos de cinco minutos.
     *
     * @return siempre {@code true}; la negativa viaja como excepcion
     * @throws ReauthenticationRequired si no hay segundo factor, o es de hace demasiado, o no se
     *     sabe
     */
    public boolean recent(Authentication principal) {
        var autenticacion = AuthenticatedIdentity.autenticacionDe(principal);
        if (autenticacion == null) {
            // Sin identidad resuelta no hay nada que leer. Se exige, que es la respuesta segura:
            // dar por bueno lo que no se sabe es justamente lo que este control existe para evitar.
            throw require(principal, "esta peticion no trae identidad de COPROP resuelta");
        }
        if (autenticacion.metodos().stream().noneMatch(SEGUNDO_FACTOR::contains)) {
            throw require(principal, "se autentico con " + autenticacion.metodos() + " y ahi no hay segundo factor");
        }
        Instant cuando = autenticacion.cuando();
        if (cuando == null) {
            throw require(principal, "su autenticacion no dice cuando fue");
        }
        Duration hace = Duration.between(cuando, clock.instant());
        if (hace.compareTo(VIGENCIA) > 0) {
            throw require(principal, "su segundo factor es de hace " + hace.toMinutes() + " minutos");
        }
        return true;
    }

    /**
     * Lo mismo, pero solo si entre esos roles hay alguno administrativo.
     *
     * <p>Es la forma que usan el alta de una membresia y la emision de una invitacion, donde los
     * roles llegan en el cuerpo: lo que Seguridad 7.3 llama sensible es <strong>asignar roles
     * administrativos</strong>, no dar de alta a un propietario. Sin esta distincion, invitar al
     * vecino del 3B pediria un segundo factor y el control acabaria estorbando donde no protege
     * nada.
     *
     * <p>Que la condicion mire el cuerpo de la peticion no la hace evitable: quien quiera colar un
     * {@code CONDO_ADMIN} sin reautenticarse tiene que mandarlo en el cuerpo, y entonces la
     * condicion se cumple.
     *
     * <p><strong>{@code PLATFORM_ADMIN} no cuenta aqui</strong>, aunque sea el mayor de los roles
     * administrativos. Ese rol no cabe en una membresia ni en una invitacion y estas dos rutas lo
     * rechazan con un 422 venga de quien venga, asi que un cuerpo que lo lleve no concede nada:
     * pedir el segundo factor solo retrasaria la misma negativa, y de paso cambiaria la respuesta
     * de un caso que ya tenia la suya desde el #18. Donde ese rol si se reparte --la ruta de
     * superadministradores-- el factor se pide siempre, sin condicion.
     *
     * @param roles los roles que la operacion concede
     */
    public boolean ifAdminRole(Authentication principal, Collection<MembershipRole> roles) {
        boolean concedeAlguno = roles.stream()
                .filter(rol -> rol != MembershipRole.PLATFORM_ADMIN)
                .anyMatch(PermissionMatrix::esAdministrativo);
        if (!concedeAlguno) {
            return true;
        }
        return recent(principal);
    }

    /**
     * Lo mismo, pero solo si ese NIT es distinto del que el condominio tiene hoy.
     *
     * <p>El NIT identifica legalmente al condominio: dos con el mismo serian el mismo, y a partir de
     * ahi los pagos de uno podrian aplicarse al otro. Cambiarlo es de las cosas que Seguridad 7.3
     * llama sensibles. Corregir una tilde del nombre o escribir la direccion no lo es, y pedir el
     * factor para eso dejaria el control estorbando donde no protege nada --el mismo razonamiento
     * que {@link #ifAdminRole}--.
     *
     * <p><strong>La condicion compara contra la base, no contra el cuerpo solo.</strong> Mandar el
     * NIT que el condominio ya tiene no es un cambio, y exigir el factor por el mero hecho de que el
     * campo venga en el cuerpo haria imposible corregir el nombre sin reautenticarse: en un
     * {@code PUT} los tres campos viajan siempre.
     *
     * <p>Que la comprobacion lea el condominio tiene un efecto buscado: <strong>el 404 de un
     * condominio que no existe sale de aqui</strong>, de la excepcion lanzada dentro de la
     * expresion, igual que en el #15 y el #19. Y como esta anotacion va despues de la autorizacion,
     * quien no puede editar recibe su 403 antes de que se mire la base.
     *
     * @param nit el NIT que trae el cuerpo
     */
    public boolean ifNitChanges(Authentication principal, UUID condominiumId, String nit) {
        if (nit.equals(condominiums.find(condominiumId).nit())) {
            return true;
        }
        return recent(principal);
    }

    /**
     * La negativa, con su linea de log.
     *
     * <p>Se registra en WARN y no en INFO: una operacion sensible rechazada por falta de segundo
     * factor es casi siempre el flujo normal --la primera vez que se pide en una sesion-- pero es
     * tambien exactamente lo que se veria si alguien estuviera probando con una sesion robada, y
     * eso tiene que quedar a la vista de quien mira los logs.
     */
    private ReauthenticationRequired require(Authentication principal, String porque) {
        log.warn(
                "La cuenta {} pidio una operacion sensible sin segundo factor reciente: {}. Se responde 401 con el desafio del RFC 9470 (Seguridad 7.3).",
                principal.getName(),
                porque);
        return new ReauthenticationRequired(
                "Esta operacion pide confirmar tu identidad con tu segundo factor. Vuelve a autenticarte y repitela.",
                VIGENCIA);
    }
}
