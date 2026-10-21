package bo.coprop.identity.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, UUID> {

    /** Lo pregunta el filtro de identidad en cada peticion, asi que va por clave primaria. */
    @Override
    boolean existsById(UUID accountId);

    /**
     * Quien administra la plataforma, del mas reciente al mas antiguo.
     *
     * <p>La cuenta desempata para que dos concesiones del mismo instante no cambien de sitio entre
     * dos consultas, por lo mismo que en el padron de membresias del #19.
     */
    List<PlatformAdmin> findAllByOrderByGrantedAtDescAccountIdDesc();
}
