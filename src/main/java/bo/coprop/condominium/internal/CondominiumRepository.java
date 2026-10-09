package bo.coprop.condominium.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CondominiumRepository extends JpaRepository<Condominium, UUID> {

    boolean existsByNit(String nit);
}
