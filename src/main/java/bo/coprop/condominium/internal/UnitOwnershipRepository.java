package bo.coprop.condominium.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UnitOwnershipRepository extends JpaRepository<UnitOwnership, UUID> {

    boolean existsByUnitIdAndPersonIdAndValidToIsNull(UUID unitId, UUID personId);

    /**
     * Lleva el condominio ademas de la unidad, aunque la unidad ya lo determine.
     *
     * <p>Es lo que pregunta la cadena de autorizacion del issue #15 en cada peticion, y preguntarlo
     * asi hace que la titularidad de una unidad de otro condominio no empareje nunca: sin la
     * columna en el WHERE habria que comprobar aparte de quien es la unidad, y ese es justo el
     * descuido que el PR #72 encontro en {@code UnitController}.
     */
    boolean existsByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(UUID condominiumId, UUID unitId, UUID personId);

    List<UnitOwnership> findByCondominiumIdAndPersonIdAndValidToIsNull(UUID condominiumId, UUID personId);

    List<UnitOwnership> findByCondominiumIdAndUnitIdAndValidToIsNull(UUID condominiumId, UUID unitId);
}
