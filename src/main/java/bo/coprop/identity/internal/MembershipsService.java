package bo.coprop.identity.internal;

import bo.coprop.identity.MembershipGranted;
import bo.coprop.identity.MembershipReinstated;
import bo.coprop.identity.MembershipRevoked;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.MembershipStatus;
import bo.coprop.identity.MembershipSuspended;
import bo.coprop.identity.MembershipView;
import bo.coprop.identity.Memberships;
import bo.coprop.shared.ConflictoDeEstado;
import bo.coprop.shared.RecursoNoEncontrado;
import bo.coprop.shared.ReglaDeNegocioViolada;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MembershipsService implements Memberships {

    private final MembershipRepository membresias;
    private final UserAccountRepository cuentas;
    private final CierreDeAcceso cierre;
    private final ApplicationEventPublisher eventos;
    private final Clock reloj;

    MembershipsService(
            MembershipRepository membresias,
            UserAccountRepository cuentas,
            CierreDeAcceso cierre,
            ApplicationEventPublisher eventos,
            Clock reloj) {
        this.membresias = membresias;
        this.cuentas = cuentas;
        this.cierre = cierre;
        this.eventos = eventos;
        this.reloj = reloj;
    }

    @Override
    @Transactional
    public MembershipView grant(
            UUID condominiumId, UUID accountId, Set<MembershipRole> roles, @Nullable UUID invitedBy, String actor) {
        if (!cuentas.existsById(accountId)) {
            throw RecursoNoEncontrado.de("la cuenta", accountId);
        }
        // El diagrama de Seguridad 2 marca la relacion con los roles como obligatoria. Una
        // membresia sin roles no autoriza nada y solo confunde a quien lea la tabla.
        if (roles.isEmpty()) {
            throw new ReglaDeNegocioViolada("Una membresia necesita al menos un rol.");
        }
        // PLATFORM_ADMIN es el unico rol con alcance de plataforma (Seguridad 3): no cuelga de
        // ningun condominio y por eso vive en su propia tabla, no aqui. Concederlo como membresia
        // daria un superadministrador "de un condominio", que no significa nada, y dejaria dos
        // sitios donde mirar quien administra la plataforma. Es el acote que el issue #16 le debia
        // al catalogo de roles.
        if (roles.contains(MembershipRole.PLATFORM_ADMIN)) {
            throw new ReglaDeNegocioViolada(
                    "PLATFORM_ADMIN no se concede como membresia: su alcance es la plataforma, no un condominio.");
        }
        // El segundo criterio de aceptacion del issue #11. Tambien lo impone un indice parcial;
        // comprobarlo aqui permite explicar que pasa.
        if (membresias.existsByCondominiumIdAndAccountIdAndValidToIsNull(condominiumId, accountId)) {
            throw new ReglaDeNegocioViolada(
                    "La cuenta %s ya tiene membresia vigente en el condominio %s.".formatted(accountId, condominiumId));
        }
        if (invitedBy != null && invitedBy.equals(accountId)) {
            throw new ReglaDeNegocioViolada("Nadie se invita a si mismo.");
        }

        Instant ahora = reloj.instant();
        Membership membresia = membresias.save(new Membership(condominiumId, accountId, roles, invitedBy, ahora));

        eventos.publishEvent(
                new MembershipGranted(condominiumId, membresia.getId(), accountId, membresia.getRoles(), actor, ahora));

        return vista(membresia);
    }

    @Override
    @Transactional
    public MembershipView revoke(UUID condominiumId, UUID membershipId, String motivo, String actor) {
        Membership membresia = vigenteParaCambiar(condominiumId, membershipId, actor);
        exigirQueNoSeaElUltimoAdministrador(membresia, "dar de baja");

        Instant ahora = reloj.instant();
        membresia.close(ahora);
        boolean sesionesCerradas = cierre.siSeQuedaSinCondominios(membresia.getAccountId());

        eventos.publishEvent(new MembershipRevoked(
                condominiumId,
                membresia.getId(),
                membresia.getAccountId(),
                membresia.getRoles(),
                motivo,
                sesionesCerradas,
                actor,
                ahora));

        return vista(membresia);
    }

    @Override
    @Transactional
    public MembershipView suspend(UUID condominiumId, UUID membershipId, String motivo, String actor) {
        Membership membresia = vigenteParaCambiar(condominiumId, membershipId, actor);
        if (membresia.getStatus() == MembershipStatus.SUSPENDIDA) {
            throw new ConflictoDeEstado("La membresia %s ya esta suspendida.".formatted(membershipId));
        }
        exigirQueNoSeaElUltimoAdministrador(membresia, "suspender");

        Instant ahora = reloj.instant();
        membresia.suspender();
        boolean sesionesCerradas = cierre.siSeQuedaSinCondominios(membresia.getAccountId());

        eventos.publishEvent(new MembershipSuspended(
                condominiumId, membresia.getId(), membresia.getAccountId(), motivo, sesionesCerradas, actor, ahora));

        return vista(membresia);
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
    public MembershipView reinstate(UUID condominiumId, UUID membershipId, String motivo, String actor) {
        Membership membresia = deEsteCondominio(condominiumId, membershipId);
        exigirVigente(membresia, "reactivar");
        if (membresia.getStatus() == MembershipStatus.ACTIVA) {
            throw new ConflictoDeEstado("La membresia %s no esta suspendida.".formatted(membershipId));
        }

        Instant ahora = reloj.instant();
        membresia.reactivar();

        eventos.publishEvent(new MembershipReinstated(
                condominiumId, membresia.getId(), membresia.getAccountId(), motivo, actor, ahora));

        return vista(membresia);
    }

    /**
     * La membresia de este condominio, abierta, y que no sea la de quien pide el cambio.
     *
     * <p>Las tres comprobaciones juntas porque la baja y la suspension las necesitan las tres y en
     * este orden: si no es de este condominio, responder 404 antes de mirar nada mas (Seguridad 7.1);
     * si ya esta cerrada, es un conflicto de estado y no un recurso que no existe, porque existe y
     * se puede leer; y lo de uno mismo se mira al final, cuando ya se sabe que la operacion tenia
     * sentido.
     *
     * <p><strong>Nadie se da de baja ni se suspende a si mismo.</strong> No es una regla de
     * seguridad --quien puede hacerlo con los demas podria pedirle a otro administrador que se lo
     * haga-- sino de recuperacion: el unico administrador de un edificio que se diera de baja se
     * dejaria fuera de la unica pantalla desde la que podria volver a entrar, y arreglarlo exigiria
     * al superadministrador. El actor es el identificador de la cuenta, que es lo que pone
     * {@code IdentidadAutenticada}.
     */
    private Membership vigenteParaCambiar(UUID condominiumId, UUID membershipId, String actor) {
        Membership membresia = deEsteCondominio(condominiumId, membershipId);
        exigirVigente(membresia, "cambiar");
        if (membresia.getAccountId().toString().equals(actor)) {
            throw new ReglaDeNegocioViolada(
                    "Nadie se da de baja ni se suspende a si mismo: pidelo a otro administrador del condominio.");
        }
        return membresia;
    }

    private Membership deEsteCondominio(UUID condominiumId, UUID membershipId) {
        return membresias
                .findById(membershipId)
                .filter(membresia -> membresia.getCondominiumId().equals(condominiumId))
                .orElseThrow(() -> RecursoNoEncontrado.de("la membresia", membershipId));
    }

    private static void exigirVigente(Membership membresia, String queSeQueriaHacer) {
        if (membresia.getValidTo() != null) {
            throw new ConflictoDeEstado(
                    "La membresia %s esta dada de baja desde %s y no se puede %s; lo que se hace es conceder una membresia nueva."
                            .formatted(membresia.getId(), membresia.getValidTo(), queSeQueriaHacer));
        }
    }

    /**
     * Un condominio no se queda sin administrador.
     *
     * <p>Si la membresia que se va a cerrar o suspender tiene {@code CONDO_ADMIN} y no hay otra
     * vigente y activa que lo tenga, se rechaza. El edificio quedaria sin nadie que pueda invitar,
     * asignar roles ni dar de alta unidades, y devolverselo exigiria al superadministrador --que
     * segun la matriz solo puede conceder roles administrativos, asi que podria arreglarlo, pero es
     * una llamada a soporte que no tiene por que existir--.
     */
    private void exigirQueNoSeaElUltimoAdministrador(Membership membresia, String queSeQueriaHacer) {
        if (!membresia.getRoles().contains(MembershipRole.CONDO_ADMIN)) {
            return;
        }
        if (!membresias.existeOtraVigenteConRol(
                membresia.getCondominiumId(), MembershipRole.CONDO_ADMIN, membresia.getId())) {
            throw new ReglaDeNegocioViolada(
                    "No se puede %s la ultima membresia con CONDO_ADMIN del condominio %s: nombra antes a otro administrador."
                            .formatted(queSeQueriaHacer, membresia.getCondominiumId()));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<MembershipView> allOfCondominium(UUID condominiumId) {
        return membresias.findByCondominiumIdOrderByValidFromDescIdDesc(condominiumId).stream()
                .map(MembershipsService::vista)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MembershipView> ofCondominium(UUID condominiumId) {
        return membresias.findByCondominiumIdAndValidToIsNull(condominiumId).stream()
                .map(MembershipsService::vista)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MembershipView> ofAccount(UUID accountId) {
        return membresias.findByAccountIdAndValidToIsNull(accountId).stream()
                .map(MembershipsService::vista)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @Nullable MembershipView of(UUID condominiumId, UUID accountId) {
        return membresias
                .findByCondominiumIdAndAccountIdAndValidToIsNull(condominiumId, accountId)
                .map(MembershipsService::vista)
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
        return vista(deEsteCondominio(condominiumId, membershipId));
    }

    private static MembershipView vista(Membership membresia) {
        return new MembershipView(
                membresia.getId(),
                membresia.getAccountId(),
                membresia.getCondominiumId(),
                membresia.getStatus(),
                membresia.getRoles(),
                membresia.getValidFrom(),
                membresia.getValidTo());
    }
}
