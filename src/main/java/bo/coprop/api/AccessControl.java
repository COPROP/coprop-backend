package bo.coprop.api;

import bo.coprop.condominium.UnitRelations;
import bo.coprop.identity.Authorization;
import bo.coprop.identity.Scope;
import bo.coprop.shared.ResourceNotFound;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * El cuarto eslabon de la cadena de Seguridad 7.2: <strong>la relacion con el recurso</strong>.
 * Issue #15.
 *
 * <p>Los tres anteriores ya estan: la identidad la resuelve {@link IdentityFilter} (#13), la
 * membresia y los roles del condominio los resuelve {@link CondominiumFilter} (#17), y si el rol
 * hace esa clase de cosas lo dice la matriz (#16). Falta la pregunta que ninguno de ellos puede
 * contestar: <em>este</em> recurso, el de la ruta, es suyo. Es el {@code canPayFor} del ejemplo de
 * Seguridad 7.2.
 *
 * <p><strong>Vive en {@code api} porque es el unico modulo que ve las dos mitades.</strong> La
 * matriz es de {@code identity} y las relaciones de la unidad son de {@code condominium}, y las
 * reglas 2 y 6 prohiben que esos dos se miren entre si. Es el mismo motivo por el que
 * {@link MeController} esta aqui, y no relaja ningun limite: {@code api} compone lo que cada
 * dominio ya publica.
 *
 * <p>Los controladores lo invocan por <strong>nombre de bean</strong> ({@code @acceso}) desde
 * {@code @PreAuthorize}, igual que {@code @autorizacion}. La anotacion es una cadena, asi que un
 * controlador de {@code condominium} lo usa sin importar nada de este paquete --y el compilador no
 * ve ese uso, de modo que lo sostienen los tests de cada endpoint--.
 *
 * <h2>Por que 403 y 404 no son intercambiables</h2>
 *
 * Las dos negativas de esta clase responden distinto, y la diferencia es el criterio del issue:
 *
 * <ul>
 *   <li><strong>El rol no hace esto</strong> ({@link Scope#NONE}): se devuelve {@code false} y
 *       la cadena responde <strong>403</strong>. No filtra nada, porque la respuesta depende solo
 *       de los roles de quien pregunta y no de que recurso pidio.
 *   <li><strong>El recurso no es suyo</strong> ({@link Scope#OWN} sin relacion vigente): se
 *       lanza {@link ResourceNotFound} y responde <strong>404</strong>. Aqui la respuesta si
 *       depende del recurso, asi que un 403 confirmaria que esa unidad existe y con eso un vecino
 *       puede enumerar el edificio probando identificadores. Es el mismo criterio con el que el
 *       filtro del #17 responde 404 a un condominio ajeno.
 * </ul>
 *
 * <p><strong>El 404 sale de una excepcion lanzada dentro de la expresion de {@code @PreAuthorize}</strong>,
 * no de un {@code false}. SpEL deja pasar sin envolver las excepciones no comprobadas del metodo
 * que invoca, asi que {@link ErrorHandler} la ve y la traduce. Es la unica forma de que la
 * autorizacion decida el estado: devolver {@code false} siempre acaba en 403. Esto lo prueba un
 * test, porque es un detalle del que depende el criterio entero y no esta a la vista en el codigo.
 */
@Component("access")
class AccessControl {

    private static final Logger log = LoggerFactory.getLogger(AccessControl.class);

    /**
     * El permiso con el que la matriz nombra la administracion de unidades y personas: la fila
     * "Gestionar bloques, unidades y personas" de Seguridad 3.
     */
    private static final String MANAGE_UNITS = "MANAGE_UNITS";

    private final Authorization authorizationCheck;
    private final UnitRelations relations;

    AccessControl(Authorization authorizationCheck, UnitRelations relations) {
        this.authorizationCheck = authorizationCheck;
        this.relations = relations;
    }

    /**
     * Si puede hacer esa operacion <strong>sobre esa unidad</strong>, resolviendo el "Propias" de la
     * matriz.
     *
     * <p>Es la forma general, la que vale para toda fila que la matriz marque con "Propias": el
     * estado de cuenta, los saldos a favor, el QR, el comprobante y la reserva. De esas cinco, hoy
     * no hay endpoint para ninguna --son de M2 y M3-- salvo lo que cuelga de la unidad, asi que lo
     * que la usa es la consulta de propietarios. No es adelantarse: la decision de cada celda ya
     * esta tomada y esto es lo que la hace valer.
     *
     * @param permiso el nombre de un {@code Permission}; mal escrito, revienta en el primer test que
     *     toque el endpoint, que es mejor que denegar en silencio
     * @throws ResourceNotFound si el permiso le alcanza solo para lo propio y esta unidad no lo
     *     es
     */
    public boolean canOnUnit(Authentication principal, String permission, UUID condominiumId, UUID unitId) {
        return switch (authorizationCheck.scopeOf(principal, permission)) {
            case ALL -> true;
            case OWN -> requireItIsTheirs(principal, condominiumId, unitId, permission);
            case NONE -> false;
        };
    }

    /**
     * Si puede ver esa unidad: quien administra unidades las ve todas, y quien no, solo las suyas.
     *
     * <p><strong>Esta decision se toma aqui y no en la matriz, porque la matriz no la toma.</strong>
     * Sus veintiuna filas son acciones sobre dinero y administracion; ninguna dice "ver una
     * unidad". Lo que gobierna leer la ficha de una unidad es el cruce de los dos eslabones
     * anteriores: pertenecer al condominio --que ya exigio el filtro del #17-- y, dentro de el,
     * administrarlas o tener relacion con esa. Es el mismo recurso que ya se usa en
     * {@code CondominiumController} cuando una fila no existe con ese nombre: se resuelve del lado
     * de la separacion de funciones y se deja escrito.
     *
     * <p>Lo que decide el alcance del issue --"un propietario solo ve su unidad"-- es justo esto, y
     * de ahi el 404 para la del vecino.
     *
     * @throws ResourceNotFound si no administra unidades y esta no es suya
     */
    public boolean canViewUnit(Authentication principal, UUID condominiumId, UUID unitId) {
        if (authorizationCheck.scopeOf(principal, MANAGE_UNITS) == Scope.ALL) {
            return true;
        }
        return requireItIsTheirs(principal, condominiumId, unitId, "ver la unidad");
    }

    /**
     * Si puede ver los datos de esa persona: la suya, o la de cualquiera si administra algun
     * condominio.
     *
     * <p>La persona es global (Seguridad 2), asi que esta ruta no cuelga de un condominio y no hay
     * autoridades de condominio que leer; la pregunta es la misma que ya decide el alta,
     * {@code MANAGE_UNITS} en alguno. Lo que se añade es lo propio: sin eso, quien no
     * administra nada no podria ver ni sus propios datos.
     *
     * <p><strong>Niega con 403 y no con 404</strong>, al contrario que la unidad. Aqui la respuesta
     * no depende de que identificador llego: a quien no administra ninguno se le dice lo mismo para
     * cualquier persona que no sea el, asi que no hay existencia que filtrar. El 404 es para cuando
     * la respuesta cambia con el recurso.
     */
    public boolean canViewPerson(Authentication principal, UUID personId) {
        return isTheirPerson(principal, personId) || authorizationCheck.canInAnyCondominium(principal, MANAGE_UNITS);
    }

    /**
     * Si puede ver esa cuenta de acceso: la suya, o la de cualquiera si administra algun condominio.
     *
     * <p>Misma regla que {@link #canViewPerson}, y hace falta por separado porque la cuenta
     * lleva el correo, el telefono y si tiene segundo factor. Quien la abre --el administrador que
     * invita-- la necesita; el resto, solo la suya. El perfil propio completo esta en
     * {@code GET /api/v1/me} y no pide ningun identificador.
     */
    public boolean canViewAccount(Authentication principal, UUID accountId) {
        return isTheirAccount(principal, accountId) || authorizationCheck.canInAnyCondominium(principal, MANAGE_UNITS);
    }

    /**
     * La relacion, o 404.
     *
     * <p>Se registra en el log porque es una negativa que el cliente recibe como "no existe": sin
     * esta linea, un propietario que dice que no ve su unidad obliga a reproducirlo a ciegas.
     */
    private boolean requireItIsTheirs(Authentication principal, UUID condominiumId, UUID unitId, String operation) {
        UUID person = personOf(principal);
        if (person != null && relations.isRelatedTo(condominiumId, unitId, person)) {
            return true;
        }
        log.info(
                "La cuenta {} pidio {} sobre la unidad {} del condominio {} sin relacion vigente con ella; se responde 404.",
                principal.getName(),
                operation,
                unitId,
                condominiumId);
        throw ResourceNotFound.de("la unidad", unitId);
    }

    private static boolean isTheirPerson(Authentication principal, UUID personId) {
        return personId.equals(personOf(principal));
    }

    private static boolean isTheirAccount(Authentication principal, UUID accountId) {
        var actor = actorOf(principal);
        return actor != null && accountId.equals(actor.accountId());
    }

    private static @Nullable UUID personOf(Authentication principal) {
        var actor = actorOf(principal);
        return actor == null ? null : actor.personId();
    }

    /**
     * El actor, o nada si esta autenticacion no paso por el filtro de identidad.
     *
     * <p>No revienta como {@link AuthenticatedIdentity#actorDe}, y la diferencia es deliberada: alli
     * se llama desde un controlador, donde no tener identidad resuelta solo puede ser un error de
     * montaje de la cadena. Aqui se llama para autorizar, y una autorizacion que no sabe quien
     * pregunta tiene que <strong>negar</strong>, no caerse: sin identidad nada es suyo, que es la
     * respuesta segura.
     */
    private static AuthenticatedIdentity.@Nullable Actor actorOf(Authentication principal) {
        return principal.getPrincipal() instanceof AuthenticatedIdentity.Actor actor ? actor : null;
    }
}
