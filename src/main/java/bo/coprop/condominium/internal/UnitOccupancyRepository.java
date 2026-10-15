package bo.coprop.condominium.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UnitOccupancyRepository extends JpaRepository<UnitOccupancy, UUID> {

    boolean existsByUnitIdAndPersonIdAndValidToIsNull(UUID unitId, UUID personId);

    /** Con el condominio en el WHERE, por lo mismo que en {@link UnitOwnershipRepository}. */
    boolean existsByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(UUID condominiumId, UUID unitId, UUID personId);

    List<UnitOccupancy> findByCondominiumIdAndPersonIdAndValidToIsNull(UUID condominiumId, UUID personId);

    /** La ocupacion vigente de una persona en una unidad. La que cierra una mudanza (#19). */
    Optional<UnitOccupancy> findByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(
            UUID condominiumId, UUID unitId, UUID personId);
}
