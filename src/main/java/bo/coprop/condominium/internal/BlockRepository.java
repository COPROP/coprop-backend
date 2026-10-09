package bo.coprop.condominium.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface BlockRepository extends JpaRepository<Block, UUID> {

    boolean existsByCondominiumIdAndName(UUID condominiumId, String name);
}
