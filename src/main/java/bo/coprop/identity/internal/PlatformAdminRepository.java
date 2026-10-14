package bo.coprop.identity.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, UUID> {

    /** Lo pregunta el filtro de identidad en cada peticion, asi que va por clave primaria. */
    boolean existsById(UUID accountId);
}
