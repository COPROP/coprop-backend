package bo.coprop.identity.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MembershipRepository extends JpaRepository<Membership, UUID> {

    /** La vigente es la unica sin fecha de cierre. Lo garantiza un indice parcial. */
    Optional<Membership> findByCondominiumIdAndAccountIdAndValidToIsNull(UUID condominiumId, UUID accountId);

    boolean existsByCondominiumIdAndAccountIdAndValidToIsNull(UUID condominiumId, UUID accountId);

    List<Membership> findByCondominiumIdAndValidToIsNull(UUID condominiumId);
}
