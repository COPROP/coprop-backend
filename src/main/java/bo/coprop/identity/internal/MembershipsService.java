package bo.coprop.identity.internal;

import bo.coprop.identity.MembershipGranted;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.MembershipView;
import bo.coprop.identity.Memberships;
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
    private final ApplicationEventPublisher eventos;
    private final Clock reloj;

    MembershipsService(
            MembershipRepository membresias,
            UserAccountRepository cuentas,
            ApplicationEventPublisher eventos,
            Clock reloj) {
        this.membresias = membresias;
        this.cuentas = cuentas;
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
    @Transactional(readOnly = true)
    public List<MembershipView> ofCondominium(UUID condominiumId) {
        return membresias.findByCondominiumIdAndValidToIsNull(condominiumId).stream()
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
        return membresias
                .findById(membershipId)
                .filter(membresia -> membresia.getCondominiumId().equals(condominiumId))
                .map(MembershipsService::vista)
                .orElseThrow(() -> RecursoNoEncontrado.de("la membresia", membershipId));
    }

    private static MembershipView vista(Membership membresia) {
        return new MembershipView(
                membresia.getId(),
                membresia.getAccountId(),
                membresia.getCondominiumId(),
                membresia.getStatus(),
                membresia.getRoles());
    }
}
