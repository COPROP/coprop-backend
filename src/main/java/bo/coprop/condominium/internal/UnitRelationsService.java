package bo.coprop.condominium.internal;

import bo.coprop.condominium.BillingResponsibilityAssigned;
import bo.coprop.condominium.BillingResponsibilityView;
import bo.coprop.condominium.OccupancyGranted;
import bo.coprop.condominium.OccupancyRole;
import bo.coprop.condominium.OccupancyView;
import bo.coprop.condominium.OwnershipGranted;
import bo.coprop.condominium.OwnershipView;
import bo.coprop.condominium.UnitRelations;
import bo.coprop.shared.RecursoNoEncontrado;
import bo.coprop.shared.ReglaDeNegocioViolada;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UnitRelationsService implements UnitRelations {

    private final UnitRepository unidades;
    private final UnitOwnershipRepository titularidades;
    private final UnitOccupancyRepository ocupaciones;
    private final BillingResponsibilityRepository responsabilidades;
    private final ApplicationEventPublisher eventos;
    private final Clock reloj;

    UnitRelationsService(
            UnitRepository unidades,
            UnitOwnershipRepository titularidades,
            UnitOccupancyRepository ocupaciones,
            BillingResponsibilityRepository responsabilidades,
            ApplicationEventPublisher eventos,
            Clock reloj) {
        this.unidades = unidades;
        this.titularidades = titularidades;
        this.ocupaciones = ocupaciones;
        this.responsabilidades = responsabilidades;
        this.eventos = eventos;
        this.reloj = reloj;
    }

    @Override
    @Transactional
    public OwnershipView grantOwnership(
            UUID condominiumId, UUID unitId, UUID personId, BigDecimal share, String actor) {
        exigirUnidadDelCondominio(condominiumId, unitId);
        if (titularidades.existsByUnitIdAndPersonIdAndValidToIsNull(unitId, personId)) {
            throw new ReglaDeNegocioViolada(
                    "La persona %s ya es propietaria vigente de la unidad %s.".formatted(personId, unitId));
        }

        Instant ahora = reloj.instant();
        UnitOwnership titularidad =
                titularidades.save(new UnitOwnership(condominiumId, unitId, personId, share, ahora));

        eventos.publishEvent(new OwnershipGranted(condominiumId, unitId, personId, share, actor, ahora));

        return vista(titularidad);
    }

    @Override
    @Transactional
    public OccupancyView grantOccupancy(
            UUID condominiumId, UUID unitId, UUID personId, OccupancyRole role, String actor) {
        exigirUnidadDelCondominio(condominiumId, unitId);
        if (ocupaciones.existsByUnitIdAndPersonIdAndValidToIsNull(unitId, personId)) {
            throw new ReglaDeNegocioViolada("La persona %s ya ocupa la unidad %s.".formatted(personId, unitId));
        }

        Instant ahora = reloj.instant();
        UnitOccupancy ocupacion = ocupaciones.save(new UnitOccupancy(condominiumId, unitId, personId, role, ahora));

        eventos.publishEvent(new OccupancyGranted(condominiumId, unitId, personId, role, actor, ahora));

        return vista(ocupacion);
    }

    @Override
    @Transactional
    public BillingResponsibilityView assignBilling(
            UUID condominiumId, UUID unitId, UUID personId, @Nullable UUID conceptId, String actor) {
        exigirUnidadDelCondominio(condominiumId, unitId);

        // El caso del responsable general, con concepto nulo, necesita su propia consulta: en SQL
        // la igualdad contra NULL no es cierta nunca, asi que preguntar con un parametro nulo no
        // encontraria la fila que si existe. En la base lo cubre NULLS NOT DISTINCT.
        boolean yaResponde = conceptId == null
                ? responsabilidades.existsByUnitIdAndPersonIdAndConceptIdIsNullAndValidToIsNull(unitId, personId)
                : responsabilidades.existsByUnitIdAndPersonIdAndConceptIdAndValidToIsNull(unitId, personId, conceptId);
        if (yaResponde) {
            throw new ReglaDeNegocioViolada(
                    "La persona %s ya responde por ese concepto de la unidad %s.".formatted(personId, unitId));
        }

        Instant ahora = reloj.instant();
        BillingResponsibility responsabilidad =
                responsabilidades.save(new BillingResponsibility(condominiumId, unitId, personId, conceptId, ahora));

        eventos.publishEvent(
                new BillingResponsibilityAssigned(condominiumId, unitId, personId, conceptId, actor, ahora));

        return vista(responsabilidad);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> unitsOwnedBy(UUID condominiumId, UUID personId) {
        return titularidades.findByCondominiumIdAndPersonIdAndValidToIsNull(condominiumId, personId).stream()
                .map(UnitOwnership::getUnitId)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> unitsOccupiedBy(UUID condominiumId, UUID personId) {
        return ocupaciones.findByCondominiumIdAndPersonIdAndValidToIsNull(condominiumId, personId).stream()
                .map(UnitOccupancy::getUnitId)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> unitsBilledTo(UUID condominiumId, UUID personId) {
        return responsabilidades.findByCondominiumIdAndPersonIdAndValidToIsNull(condominiumId, personId).stream()
                .map(BillingResponsibility::getUnitId)
                .distinct()
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OwnershipView> ownersOf(UUID condominiumId, UUID unitId) {
        exigirUnidadDelCondominio(condominiumId, unitId);
        return titularidades.findByCondominiumIdAndUnitIdAndValidToIsNull(condominiumId, unitId).stream()
                .map(UnitRelationsService::vista)
                .toList();
    }

    /**
     * La unidad tiene que ser de este condominio.
     *
     * <p>Es la razon de que estas tres relaciones vivan en este modulo y no en {@code identity}:
     * desde alli no se puede comprobar, porque la regla 2 prohibe la dependencia, y sin la
     * comprobacion la ruta de un condominio serviria para colgar titularidades de las unidades de
     * cualquier otro. La clave foranea compuesta de la base lo impediria, pero llegaria como un
     * choque de integridad en vez de un 404.
     */
    private void exigirUnidadDelCondominio(UUID condominiumId, UUID unitId) {
        boolean esDeEsteCondominio = unidades.findById(unitId)
                .map(unidad -> unidad.getCondominiumId().equals(condominiumId))
                .orElse(false);
        if (!esDeEsteCondominio) {
            throw RecursoNoEncontrado.de("la unidad", unitId);
        }
    }

    private static OwnershipView vista(UnitOwnership titularidad) {
        return new OwnershipView(
                titularidad.getId(),
                titularidad.getCondominiumId(),
                titularidad.getUnitId(),
                titularidad.getPersonId(),
                titularidad.getShare());
    }

    private static OccupancyView vista(UnitOccupancy ocupacion) {
        return new OccupancyView(
                ocupacion.getId(),
                ocupacion.getCondominiumId(),
                ocupacion.getUnitId(),
                ocupacion.getPersonId(),
                ocupacion.getRole());
    }

    private static BillingResponsibilityView vista(BillingResponsibility responsabilidad) {
        return new BillingResponsibilityView(
                responsabilidad.getId(),
                responsabilidad.getCondominiumId(),
                responsabilidad.getUnitId(),
                responsabilidad.getPersonId(),
                responsabilidad.getConceptId());
    }
}
