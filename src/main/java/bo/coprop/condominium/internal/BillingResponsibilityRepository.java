package bo.coprop.condominium.internal;

import java.util.List;
import java.util.Optional;
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

    /**
     * Si responde por algun concepto de esa unidad, el general incluido.
     *
     * <p>Aqui el concepto no entra en la pregunta a proposito: para la cadena de autorizacion del
     * issue #15 basta con responder por algo de esa unidad. Acotar por concepto es de la operacion
     * que se autorice --un QR de agua, un comprobante de expensas-- y le toca a M2, cuando el
     * concepto exista como entidad.
     */
    boolean existsByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(UUID condominiumId, UUID unitId, UUID personId);

    List<BillingResponsibility> findByCondominiumIdAndPersonIdAndValidToIsNull(UUID condominiumId, UUID personId);

    /**
     * La responsabilidad general vigente de una persona sobre una unidad, la de concepto nulo.
     *
     * <p>Consulta aparte de la de abajo por lo mismo que en el alta: {@code = NULL} no es cierto
     * nunca, asi que el metodo derivado con un parametro nulo no encontraria la fila que si existe.
     */
    Optional<BillingResponsibility> findByCondominiumIdAndUnitIdAndPersonIdAndConceptIdIsNullAndValidToIsNull(
            UUID condominiumId, UUID unitId, UUID personId);

    /** La de un concepto concreto. */
    Optional<BillingResponsibility> findByCondominiumIdAndUnitIdAndPersonIdAndConceptIdAndValidToIsNull(
            UUID condominiumId, UUID unitId, UUID personId, UUID conceptId);

    /**
     * Todas las vigentes de una persona sobre una unidad, por cualquier concepto.
     *
     * <p>Las cierra la venta de la unidad (#19): si el vendedor respondia por algo de ella, esa
     * fila le seguiria dando acceso. Son varias porque se puede responder por la unidad entera y
     * ademas por conceptos sueltos.
     */
    List<BillingResponsibility> findByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(
            UUID condominiumId, UUID unitId, UUID personId);
}
