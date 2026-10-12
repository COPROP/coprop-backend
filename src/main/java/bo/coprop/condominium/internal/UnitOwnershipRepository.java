package bo.coprop.condominium.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UnitOwnershipRepository extends JpaRepository<UnitOwnership, UUID> {

    boolean existsByUnitIdAndPersonIdAndValidToIsNull(UUID unitId, UUID personId);

    List<UnitOwnership> findByCondominiumIdAndPersonIdAndValidToIsNull(UUID condominiumId, UUID personId);

    List<UnitOwnership> findByCondominiumIdAndUnitIdAndValidToIsNull(UUID condominiumId, UUID unitId);
}
