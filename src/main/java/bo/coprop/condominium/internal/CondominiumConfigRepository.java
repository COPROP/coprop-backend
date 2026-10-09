package bo.coprop.condominium.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CondominiumConfigRepository extends JpaRepository<CondominiumConfig, UUID> {

    /** La version vigente es la unica sin fecha de cierre. Lo garantiza un indice parcial. */
    Optional<CondominiumConfig> findByCondominiumIdAndValidToIsNull(UUID condominiumId);
}
