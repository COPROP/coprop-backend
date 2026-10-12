package bo.coprop.condominium.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface BillingResponsibilityRepository extends JpaRepository<BillingResponsibility, UUID> {

    /**
     * Para el caso del responsable general, con {@code conceptId} nulo, hace falta una consulta
     * aparte: en SQL {@code = NULL} no es cierto nunca, asi que el metodo derivado con un
     * parametro nulo no encontraria la fila que si existe. En la base lo cubre
     * {@code NULLS NOT DISTINCT}; aqui hay que preguntarlo explicitamente.
     */
    boolean existsByUnitIdAndPersonIdAndConceptIdIsNullAndValidToIsNull(UUID unitId, UUID personId);

    boolean existsByUnitIdAndPersonIdAndConceptIdAndValidToIsNull(UUID unitId, UUID personId, UUID conceptId);

    List<BillingResponsibility> findByCondominiumIdAndPersonIdAndValidToIsNull(UUID condominiumId, UUID personId);
}
