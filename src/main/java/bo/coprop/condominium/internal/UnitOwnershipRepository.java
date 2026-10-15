package bo.coprop.condominium.internal;

import java.util.List;
import java.util.Optional;
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

    /** La titularidad vigente de una persona sobre una unidad. La que cierra una venta (#19). */
    Optional<UnitOwnership> findByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(
            UUID condominiumId, UUID unitId, UUID personId);

    /**
     * Todas las que ha tenido la unidad, de la mas reciente a la mas antigua.
     *
     * <p>Por {@code validFrom} descendente y no por {@code validTo}: la vigente lo tiene nulo y en
     * PostgreSQL los nulos ordenan al final por defecto, asi que la actual saldria la ultima, que
     * es justo al contrario de como se lee un historico. El segundo criterio es el identificador,
     * para que dos titularidades abiertas el mismo instante --las dos mitades de una copropiedad--
     * no cambien de sitio entre dos consultas.
     */
    List<UnitOwnership> findByCondominiumIdAndUnitIdOrderByValidFromDescIdDesc(UUID condominiumId, UUID unitId);
}
