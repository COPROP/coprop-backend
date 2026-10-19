package bo.coprop.identity.internal;

import bo.coprop.identity.MembershipGranted;
import bo.coprop.identity.MembershipReinstated;
import bo.coprop.identity.MembershipRevoked;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.MembershipRolesChanged;
import bo.coprop.identity.MembershipStatus;
import bo.coprop.identity.MembershipSuspended;
import bo.coprop.identity.MembershipView;
import bo.coprop.identity.Memberships;
import bo.coprop.shared.BusinessRuleViolated;
import bo.coprop.shared.ResourceNotFound;
import bo.coprop.shared.StateConflict;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MembershipsService implements Memberships {

    private static final Logger log = LoggerFactory.getLogger(MembershipsService.class);

    private final MembershipRepository memberships;
    private final UserAccountRepository accounts;
    private final AccessRevocation cierre;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    MembershipsService(
            MembershipRepository memberships,
            UserAccountRepository accounts,
            AccessRevocation cierre,
            ApplicationEventPublisher eventos,
            Clock clock) {
        this.memberships = memberships;
        this.accounts = accounts;
        this.cierre = cierre;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Override
    @Transactional
    public MembershipView grant(
            UUID condominiumId, UUID accountId, Set<MembershipRole> roles, @Nullable UUID invitedBy, String actor) {
        if (!accounts.existsById(accountId)) {
            throw ResourceNotFound.de("la cuenta", accountId);
        }
        // Al menos un rol, y ninguno de plataforma. Las dos reglas y sus motivos estan en
        // exigirRolesDeMembresia, compartido con el cambio de roles del #20.
        requireMembershipRoles(roles);
        // El segundo criterio de aceptacion del issue #11. Tambien lo impone un indice parcial;
        // comprobarlo aqui permite explicar que pasa.
        if (memberships.existsByCondominiumIdAndAccountIdAndValidToIsNull(condominiumId, accountId)) {
            throw new BusinessRuleViolated(
                    "La cuenta %s ya tiene membresia vigente en el condominio %s.".formatted(accountId, condominiumId));
        }
        if (invitedBy != null && invitedBy.equals(accountId)) {
            throw new BusinessRuleViolated("Nadie se invita a si mismo.");
        }

        Instant ahora = clock.instant();
        Membership membership = memberships.save(new Membership(condominiumId, accountId, roles, invitedBy, ahora));
        avisarSiConcentraFunciones(membership.getRoles(), condominiumId, accountId, actor);

        eventos.publishEvent(new MembershipGranted(
                condominiumId, membership.getId(), accountId, membership.getRoles(), actor, ahora));

        return view(membership);
    }

    @Override
    @Transactional
    public MembershipView revoke(UUID condominiumId, UUID membershipId, String reason, String actor) {
        Membership membership = currentForChange(condominiumId, membershipId, actor);
        requireNotTheLastAdmin(membership, "dar de baja");

        Instant ahora = clock.instant();
        membership.close(ahora);
        boolean closedSessions = cierre.ifLeftWithoutCondominiums(membership.getAccountId());

        eventos.publishEvent(new MembershipRevoked(
                condominiumId,
                membership.getId(),
                membership.getAccountId(),
                membership.getRoles(),
                reason,
                closedSessions,
                actor,
                ahora));

        return view(membership);
    }

    @Override
    @Transactional
    public MembershipView suspend(UUID condominiumId, UUID membershipId, String reason, String actor) {
        Membership membership = currentForChange(condominiumId, membershipId, actor);
        if (membership.getStatus() == MembershipStatus.SUSPENDED) {
            throw new StateConflict("La membresia %s ya esta suspendida.".formatted(membershipId));
        }
        requireNotTheLastAdmin(membership, "suspender");

        Instant ahora = clock.instant();
        membership.suspend();
        boolean closedSessions = cierre.ifLeftWithoutCondominiums(membership.getAccountId());

        eventos.publishEvent(new MembershipSuspended(
                condominiumId, membership.getId(), membership.getAccountId(), reason, closedSessions, actor, ahora));

        return view(membership);
    }

    /**
     * La reactivacion es la unica de las tres que se puede hacer sobre si mismo.
     *
     * <p>No pasa por {@link #vigenteParaCambiar} por eso: la regla de "nadie se da de baja a si
     * mismo" existe para que un administrador no se deje fuera, y devolverse el acceso no deja a
     * nadie fuera de nada. En la practica no se puede llegar aqui estando suspendido --el filtro de
     * condominio responde 404 antes-- asi que la restriccion solo estorbaria.
     */
    @Override
    @Transactional
    public MembershipView reinstate(UUID condominiumId, UUID membershipId, String reason, String actor) {
        Membership membership = ofThisCondominium(condominiumId, membershipId);
        requireCurrent(membership, "reactivar");
        if (membership.getStatus() == MembershipStatus.ACTIVE) {
            throw new StateConflict("La membresia %s no esta suspendida.".formatted(membershipId));
        }

        Instant ahora = clock.instant();
        membership.reinstate();

        eventos.publishEvent(new MembershipReinstated(
                condominiumId, membership.getId(), membership.getAccountId(), reason, actor, ahora));

        return view(membership);
    }

    /**
     * El cambio de roles del issue #20.
     *
     * <p>Las comprobaciones van en este orden y cada una descarta una respuesta distinta: primero
     * que la membresia sea de este condominio y este abierta y no sea la de quien pide --las tres
     * de {@link #vigenteParaCambiar}, iguales que en la baja--, despues que los roles que llegan
     * sean roles de membresia, y al final que el condominio no se quede sin administrador.
     */
    @Override
    @Transactional
    public MembershipView changeRoles(
            UUID condominiumId, UUID membershipId, Set<MembershipRole> roles, String reason, String actor) {
        Membership membership = currentForChange(condominiumId, membershipId, actor);
        requireMembershipRoles(roles);

        Set<MembershipRole> oldValues = membership.getRoles();
        if (oldValues.equals(roles)) {
            // Un PUT es idempotente, asi que repetir los roles que ya hay no es un error. Pero
            // tampoco es un cambio: publicar el evento dejaria en la bitacora una fila que dice que
            // alguien cambio algo cuando no cambio nada, y la bitacora registra cambios, no
            // peticiones.
            return view(membership);
        }

        // Si deja de tener CONDO_ADMIN, la misma regla que la baja: el edificio no se queda sin
        // nadie que pueda invitar ni repartir roles. Con el guarda, el metodo compartido hace lo
        // que toca --sale por donde entra si la membresia no lo tenia--.
        if (!roles.contains(MembershipRole.CONDO_ADMIN)) {
            requireNotTheLastAdmin(membership, "quitarle CONDO_ADMIN a");
        }

        Instant ahora = clock.instant();
        membership.changeRoles(roles);

        Set<MembershipRole> retirados = EnumSet.noneOf(MembershipRole.class);
        retirados.addAll(oldValues);
        retirados.removeAll(roles);
        boolean closedSessions = cierre.siPierdeAlgunRolAdministrativo(membership.getAccountId(), retirados);
        boolean concentraFunciones =
                avisarSiConcentraFunciones(membership.getRoles(), condominiumId, membership.getAccountId(), actor);

        eventos.publishEvent(new MembershipRolesChanged(
                condominiumId,
                membership.getId(),
                membership.getAccountId(),
                oldValues,
                membership.getRoles(),
                reason,
                concentraFunciones,
                closedSessions,
                actor,
                ahora));

        return view(membership);
    }

    /**
     * Los roles que puede tener una membresia: al menos uno, y ninguno de plataforma.
     *
     * <p><strong>Al menos uno</strong> porque el diagrama de Seguridad 2 marca esa relacion como
     * obligatoria, de uno a muchos: una membresia sin roles no autoriza nada y solo confunde a
     * quien lea la tabla.
     *
     * <p><strong>Ninguno de plataforma</strong> porque {@code PLATFORM_ADMIN} es el unico rol con
     * alcance de plataforma (Seguridad 3): no cuelga de ningun condominio y por eso vive en su
     * propia tabla. Concederlo como membresia daria un superadministrador "de un condominio", que no
     * significa nada, y dejaria dos sitios donde mirar quien administra la plataforma. Es el acote
     * que el issue #16 le debia al catalogo de roles, y desde el <strong>#20</strong> ese rol tiene
     * su propia operacion: {@code Identities.grantPlatformAdmin}.
     *
     * <p>Lo comparten el alta y el cambio para que no puedan separarse: un alta que rechaza
     * {@code PLATFORM_ADMIN} y un cambio que lo admite dejarian entrar por la segunda puerta justo
     * lo que la primera prohibe.
     */
    private static void requireMembershipRoles(Set<MembershipRole> roles) {
        if (roles.isEmpty()) {
            throw new BusinessRuleViolated("Una membresia necesita al menos un rol.");
        }
        if (roles.contains(MembershipRole.PLATFORM_ADMIN)) {
            throw new BusinessRuleViolated(
                    "PLATFORM_ADMIN no se concede como membresia: su alcance es la plataforma, no un condominio.");
        }
    }

    /**
     * El aviso de concentracion que pide el alcance del issue #20: la misma persona con
     * {@code CONDO_ADMIN} y {@code FINANCE_ADMIN}.
     *
     * <p><strong>Avisa, no impide.</strong> Seguridad 10.1 permite la concentracion como decision
     * explicita del condominio --en edificios pequeños una sola persona administra y maneja el
     * dinero-- a condicion de que quede registrada. De ahi las dos salidas: esta linea de log, para
     * quien opera, y el valor que viaja en el evento hasta la bitacora, que es donde el analisis lo
     * exige.
     *
     * <p>No va en la respuesta de la API, y es deliberado: quien hace el cambio ya sabe que roles
     * mando. El aviso existe para quien lea despues y se pregunte si en ese edificio hay separacion
     * de funciones.
     *
     * @return si concentra, para que el evento lo lleve
     */
    private static boolean avisarSiConcentraFunciones(
            Set<MembershipRole> roles, UUID condominiumId, UUID accountId, String actor) {
        if (!roles.contains(MembershipRole.CONDO_ADMIN) || !roles.contains(MembershipRole.FINANCE_ADMIN)) {
            return false;
        }
        log.warn(
                "La cuenta {} queda con CONDO_ADMIN y FINANCE_ADMIN a la vez en el condominio {}: ese condominio no tiene separacion de funciones (Seguridad 10.1). Lo decidio {}.",
                accountId,
                condominiumId,
                actor);
        return true;
    }

    /**
     * La membresia de este condominio, abierta, y que no sea la de quien pide el cambio.
     *
     * <p>Las tres comprobaciones juntas porque la baja, la suspension y el cambio de roles las
     * necesitan las tres y en
     * este orden: si no es de este condominio, responder 404 antes de mirar nada mas (Seguridad 7.1);
     * si ya esta cerrada, es un conflicto de estado y no un recurso que no existe, porque existe y
     * se puede leer; y lo de uno mismo se mira al final, cuando ya se sabe que la operacion tenia
     * sentido.
     *
     * <p><strong>Nadie se da de baja, se suspende ni se cambia los roles a si mismo.</strong> No es
     * una regla de seguridad --quien puede hacerlo con los demas podria pedirle a otro administrador
     * que se lo haga-- sino de recuperacion: el unico administrador de un edificio que se diera de
     * baja, o que se quitara {@code CONDO_ADMIN}, se dejaria fuera de la unica pantalla desde la que
     * podria volver a entrar, y arreglarlo exigiria al superadministrador. El actor es el
     * identificador de la cuenta, que es lo que pone {@code AuthenticatedIdentity}.
     *
     * <p>El issue <strong>#20</strong> la hereda tal cual, y eso tiene un precio que conviene
     * conocer: un administrador que quiera <em>quitarse</em> {@code FINANCE_ADMIN} para que haya
     * separacion de funciones tiene que pedirselo a otro. La alternativa --permitir el cambio propio
     * salvo que se quite {@code CONDO_ADMIN}-- es una regla mas y no cierra nada que esta no cierre,
     * porque la ultima membresia administrativa ya la protege {@code exigirQueNoSeaElUltimoAdministrador}.
     */
    private Membership currentForChange(UUID condominiumId, UUID membershipId, String actor) {
        Membership membership = ofThisCondominium(condominiumId, membershipId);
        requireCurrent(membership, "cambiar");
        if (membership.getAccountId().toString().equals(actor)) {
            throw new BusinessRuleViolated(
                    "Nadie se da de baja, se suspende ni se cambia los roles a si mismo: pidelo a otro administrador del condominio.");
        }
        return membership;
    }

    private Membership ofThisCondominium(UUID condominiumId, UUID membershipId) {
        return memberships
                .findById(membershipId)
                .filter(membership -> membership.getCondominiumId().equals(condominiumId))
                .orElseThrow(() -> ResourceNotFound.de("la membresia", membershipId));
    }

    private static void requireCurrent(Membership membership, String queSeQueriaHacer) {
        if (membership.getValidTo() != null) {
            throw new StateConflict(
                    "La membresia %s esta dada de baja desde %s y no se puede %s; lo que se hace es conceder una membresia nueva."
                            .formatted(membership.getId(), membership.getValidTo(), queSeQueriaHacer));
        }
    }

    /**
     * Un condominio no se queda sin administrador.
     *
     * <p>Si la membresia que se va a cerrar, suspender o dejar sin ese rol tiene {@code CONDO_ADMIN}
     * y no hay otra vigente y activa que lo tenga, se rechaza. El edificio quedaria sin nadie que pueda invitar,
     * asignar roles ni dar de alta unidades, y devolverselo exigiria al superadministrador --que
     * segun la matriz solo puede conceder roles administrativos, asi que podria arreglarlo, pero es
     * una llamada a soporte que no tiene por que existir--.
     */
    private void requireNotTheLastAdmin(Membership membership, String queSeQueriaHacer) {
        if (!membership.getRoles().contains(MembershipRole.CONDO_ADMIN)) {
            return;
        }
        if (!memberships.anotherCurrentExistsWithRole(
                membership.getCondominiumId(), MembershipRole.CONDO_ADMIN, membership.getId())) {
            throw new BusinessRuleViolated(
                    "No se puede %s la ultima membresia con CONDO_ADMIN del condominio %s: nombra oldValues a otro administrador."
                            .formatted(queSeQueriaHacer, membership.getCondominiumId()));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<MembershipView> allOfCondominium(UUID condominiumId) {
        return memberships.findByCondominiumIdOrderByValidFromDescIdDesc(condominiumId).stream()
                .map(MembershipsService::view)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MembershipView> ofCondominium(UUID condominiumId) {
        return memberships.findByCondominiumIdAndValidToIsNull(condominiumId).stream()
                .map(MembershipsService::view)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MembershipView> ofAccount(UUID accountId) {
        return memberships.findByAccountIdAndValidToIsNull(accountId).stream()
                .map(MembershipsService::view)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @Nullable MembershipView of(UUID condominiumId, UUID accountId) {
        return memberships
                .findByCondominiumIdAndAccountIdAndValidToIsNull(condominiumId, accountId)
                .map(MembershipsService::view)
                .orElse(null);
    }

    /**
     * La membresia tiene que ser de este condominio.
     *
     * <p>No basta con buscarla por su identificador: pedir la de otro condominio desde la ruta de
     * este responde lo mismo que pedir una que no existe (Seguridad 7.1, criterio 404-no-403).
     */
    @Override
    @Transactional(readOnly = true)
    public MembershipView find(UUID condominiumId, UUID membershipId) {
        return view(ofThisCondominium(condominiumId, membershipId));
    }

    private static MembershipView view(Membership membership) {
        return new MembershipView(
                membership.getId(),
                membership.getAccountId(),
                membership.getCondominiumId(),
                membership.getStatus(),
                membership.getRoles(),
                membership.getValidFrom(),
                membership.getValidTo());
    }
}
