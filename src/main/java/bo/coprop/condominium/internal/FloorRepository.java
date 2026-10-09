package bo.coprop.condominium.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface FloorRepository extends JpaRepository<Floor, UUID> {

    boolean existsByBlockIdAndNumber(UUID blockId, int number);
}
