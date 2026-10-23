package bo.coprop.condominium.internal;

import bo.coprop.condominium.BillingResponsibilityAssigned;
import bo.coprop.condominium.BillingResponsibilityEnded;
import bo.coprop.condominium.BillingResponsibilityView;
import bo.coprop.condominium.OccupancyEnded;
import bo.coprop.condominium.OccupancyGranted;
import bo.coprop.condominium.OccupancyRole;
import bo.coprop.condominium.OccupancyView;
import bo.coprop.condominium.OwnershipGranted;
import bo.coprop.condominium.OwnershipTransferred;
import bo.coprop.condominium.OwnershipView;
import bo.coprop.condominium.RelatedUnitView;
import bo.coprop.condominium.UnitRelation;
import bo.coprop.condominium.UnitRelations;
import bo.coprop.shared.BusinessRuleViolated;
import bo.coprop.shared.ResourceNotFound;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UnitRelationsService implements UnitRelations {

    private final UnitRepository units;
    private final UnitOwnershipRepository ownerships;
    private final UnitOccupancyRepository occupancies;
    private final BillingResponsibilityRepository billingResponsibilities;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    UnitRelationsService(
            UnitRepository units,
            UnitOwnershipRepository ownerships,
            UnitOccupancyRepository occupancies,
            BillingResponsibilityRepository billingResponsibilities,
            ApplicationEventPublisher eventos,
            Clock clock) {
        this.units = units;
        this.ownerships = ownerships;
        this.occupancies = occupancies;
        this.billingResponsibilities = billingResponsibilities;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OwnershipView grantOwnership(
            UUID condominiumId, UUID unitId, UUID personId, BigDecimal share, String actor) {
        requireUnitOfCondominium(condominiumId, unitId);
        if (ownerships.existsByUnitIdAndPersonIdAndValidToIsNull(unitId, personId)) {
            throw new BusinessRuleViolated(
                    "La persona %s ya es propietaria vigente de la unidad %s.".formatted(personId, unitId));
        }

        Instant ahora = clock.instant();
        UnitOwnership ownership = ownerships.save(new UnitOwnership(condominiumId, unitId, personId, share, ahora));

        eventos.publishEvent(new OwnershipGranted(condominiumId, unitId, personId, share, actor, ahora));

        return view(ownership);
    }

    @Override
    @Transactional
    public OccupancyView grantOccupancy(
            UUID condominiumId, UUID unitId, UUID personId, OccupancyRole role, String actor) {
        requireUnitOfCondominium(condominiumId, unitId);
        if (occupancies.existsByUnitIdAndPersonIdAndValidToIsNull(unitId, personId)) {
            throw new BusinessRuleViolated("La persona %s ya ocupa la unidad %s.".formatted(personId, unitId));
        }

        Instant ahora = clock.instant();
        UnitOccupancy occupancy = occupancies.save(new UnitOccupancy(condominiumId, unitId, personId, role, ahora));

        eventos.publishEvent(new OccupancyGranted(condominiumId, unitId, personId, role, actor, ahora));

        return view(occupancy);
    }

    @Override
    @Transactional
    public BillingResponsibilityView assignBilling(
            UUID condominiumId, UUID unitId, UUID personId, @Nullable UUID conceptId, String actor) {
        requireUnitOfCondominium(condominiumId, unitId);

        // El caso del responsable general, con concepto nulo, necesita su propia consulta: en SQL
        // la igualdad contra NULL no es cierta nunca, asi que preguntar con un parametro nulo no
        // encontraria la fila que si existe. En la base lo cubre NULLS NOT DISTINCT.
        boolean yaResponde = conceptId == null
                ? billingResponsibilities.existsByUnitIdAndPersonIdAndConceptIdIsNullAndValidToIsNull(unitId, personId)
                : billingResponsibilities.existsByUnitIdAndPersonIdAndConceptIdAndValidToIsNull(
                        unitId, personId, conceptId);
        if (yaResponde) {
            throw new BusinessRuleViolated(
                    "La persona %s ya responde por ese concepto de la unidad %s.".formatted(personId, unitId));
        }

        Instant ahora = clock.instant();
        BillingResponsibility billingResponsibility = billingResponsibilities.save(
                new BillingResponsibility(condominiumId, unitId, personId, conceptId, ahora));

        eventos.publishEvent(
                new BillingResponsibilityAssigned(condominiumId, unitId, personId, conceptId, actor, ahora));

        return view(billingResponsibility);
    }

    /**
     * La venta: cierra lo del vendedor, abre lo del comprador, y lo hace en una transaccion.
     *
     * <p><strong>Hace falta el {@code flush()} entre cerrar y abrir</strong>, y es la misma trampa
     * que enseño la configuracion con vigencia del issue #9: Hibernate ordena todos los INSERT antes
     * que los UPDATE dentro de un flush, asi que sin esto la titularidad nueva llegaria a la base
     * mientras la vieja sigue abierta y el indice parcial de "una sola vigente por persona y
     * unidad" la rechazaria. Aqui solo muerde cuando comprador y vendedor son la misma persona
     * --que se rechaza antes-- pero se deja puesto porque el orden correcto es el correcto: cerrar
     * y despues abrir.
     */
    @Override
    @Transactional
    public OwnershipView transferOwnership(
            UUID condominiumId, UUID unitId, UUID fromPersonId, UUID toPersonId, String actor) {
        requireUnitOfCondominium(condominiumId, unitId);
        if (fromPersonId.equals(toPersonId)) {
            throw new BusinessRuleViolated("Una unidad no se vende a su propio dueño.");
        }
        UnitOwnership delVendedor = ownerships
                .findByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(condominiumId, unitId, fromPersonId)
                .orElseThrow(() -> ResourceNotFound.de("la titularidad vigente de la persona", fromPersonId));
        // Una venta a quien ya es copropietario cambia el reparto de la unidad, y eso no es esta
        // operacion: el porcentaje del comprador tendria que ser la suma de los dos, y aqui el que
        // se transfiere es el del vendedor. Se registra cerrando y abriendo a mano.
        if (ownerships.existsByUnitIdAndPersonIdAndValidToIsNull(unitId, toPersonId)) {
            throw new BusinessRuleViolated(
                    "La persona %s ya es propietaria vigente de la unidad %s; una venta entre copropietarios cambia el reparto y se registra titularidad por titularidad."
                            .formatted(toPersonId, unitId));
        }

        Instant ahora = clock.instant();
        BigDecimal percentage = delVendedor.getShare();

        delVendedor.close(ahora);
        // Las otras dos relaciones del vendedor con esta unidad se cierran tambien: las tres dan
        // acceso por igual, asi que dejar una abierta dejaria al vendedor viendo lo que ya no es
        // suyo, que es justo el criterio del issue.
        int closed = 1
                + closeOccupancyIfAny(condominiumId, unitId, fromPersonId, ahora, actor)
                + closeBillingResponsibilities(condominiumId, unitId, fromPersonId, ahora, actor);
        ownerships.flush();

        UnitOwnership delComprador =
                ownerships.save(new UnitOwnership(condominiumId, unitId, toPersonId, percentage, ahora));

        eventos.publishEvent(new OwnershipTransferred(
                condominiumId, unitId, fromPersonId, toPersonId, percentage, closed, actor, ahora));

        return view(delComprador);
    }

    /**
     * Cierra la ocupacion del vendedor, si la tenia, y devuelve cuantas cerro.
     *
     * <p>Publica su evento igual que si la hubiera cerrado el administrador a mano: lo que pasa es
     * lo mismo --alguien dejo de ocupar-- y la bitacora no tiene por que saber que fue a cuenta de
     * una venta. El evento de la venta ya cuenta cuantas relaciones se cerraron con ella.
     */
    private int closeOccupancyIfAny(UUID condominiumId, UUID unitId, UUID personId, Instant ahora, String actor) {
        return occupancies
                .findByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(condominiumId, unitId, personId)
                .map(occupancy -> {
                    occupancy.close(ahora);
                    eventos.publishEvent(
                            new OccupancyEnded(condominiumId, unitId, personId, occupancy.getRole(), actor, ahora));
                    return 1;
                })
                .orElse(0);
    }

    /** Cierra todas las responsabilidades de pago del vendedor sobre esa unidad. */
    private int closeBillingResponsibilities(
            UUID condominiumId, UUID unitId, UUID personId, Instant ahora, String actor) {
        List<BillingResponsibility> suyas =
                billingResponsibilities.findByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(
                        condominiumId, unitId, personId);
        for (BillingResponsibility billingResponsibility : suyas) {
            billingResponsibility.close(ahora);
            eventos.publishEvent(new BillingResponsibilityEnded(
                    condominiumId, unitId, personId, billingResponsibility.getConceptId(), actor, ahora));
        }
        return suyas.size();
    }

    @Override
    @Transactional
    public void endOccupancy(UUID condominiumId, UUID unitId, UUID personId, String actor) {
        requireUnitOfCondominium(condominiumId, unitId);
        UnitOccupancy occupancy = occupancies
                .findByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(condominiumId, unitId, personId)
                .orElseThrow(() -> ResourceNotFound.de("la ocupacion vigente de la persona", personId));

        Instant ahora = clock.instant();
        occupancy.close(ahora);

        eventos.publishEvent(new OccupancyEnded(condominiumId, unitId, personId, occupancy.getRole(), actor, ahora));
    }

    @Override
    @Transactional
    public void endBilling(UUID condominiumId, UUID unitId, UUID personId, @Nullable UUID conceptId, String actor) {
        requireUnitOfCondominium(condominiumId, unitId);
        // Como en el alta, el caso del concepto nulo necesita su propia consulta.
        BillingResponsibility billingResponsibility = (conceptId == null
                        ? billingResponsibilities
                                .findByCondominiumIdAndUnitIdAndPersonIdAndConceptIdIsNullAndValidToIsNull(
                                        condominiumId, unitId, personId)
                        : billingResponsibilities.findByCondominiumIdAndUnitIdAndPersonIdAndConceptIdAndValidToIsNull(
                                condominiumId, unitId, personId, conceptId))
                .orElseThrow(() -> ResourceNotFound.de("la responsabilidad de pago vigente de la persona", personId));

        Instant ahora = clock.instant();
        billingResponsibility.close(ahora);

        eventos.publishEvent(new BillingResponsibilityEnded(condominiumId, unitId, personId, conceptId, actor, ahora));
    }

    /**
     * Las tres preguntas en el orden en que es mas probable que una conteste que si.
     *
     * <p>El cortocircuito del {@code ||} se aprovecha: la titularidad es la relacion que mas filas
     * tiene, asi que en el caso corriente --el propietario mirando lo suyo-- esto es una sola
     * consulta y no tres.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isRelatedTo(UUID condominiumId, UUID unitId, UUID personId) {
        return ownerships.existsByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(condominiumId, unitId, personId)
                || occupancies.existsByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(
                        condominiumId, unitId, personId)
                || billingResponsibilities.existsByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(
                        condominiumId, unitId, personId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> unitsOwnedBy(UUID condominiumId, UUID personId) {
        return ownerships.findByCondominiumIdAndPersonIdAndValidToIsNull(condominiumId, personId).stream()
                .map(UnitOwnership::getUnitId)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> unitsOccupiedBy(UUID condominiumId, UUID personId) {
        return occupancies.findByCondominiumIdAndPersonIdAndValidToIsNull(condominiumId, personId).stream()
                .map(UnitOccupancy::getUnitId)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> unitsBilledTo(UUID condominiumId, UUID personId) {
        return billingResponsibilities.findByCondominiumIdAndPersonIdAndValidToIsNull(condominiumId, personId).stream()
                .map(BillingResponsibility::getUnitId)
                .distinct()
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RelatedUnitView> relatedTo(UUID condominiumId, UUID personId) {
        Map<UUID, EnumSet<UnitRelation>> relations = new LinkedHashMap<>();
        anotar(relations, unitsOwnedBy(condominiumId, personId), UnitRelation.OWNER);
        anotar(relations, unitsOccupiedBy(condominiumId, personId), UnitRelation.OCCUPANT);
        anotar(relations, unitsBilledTo(condominiumId, personId), UnitRelation.BILLING_RESPONSIBLE);
        if (relations.isEmpty()) {
            return List.of();
        }

        // Las unidades, en bloque y no una por una: una persona con veinte parqueos no puede costar
        // veinte consultas. Es el mismo criterio con el que el alta masiva comprueba los codigos.
        return units.findAllById(relations.keySet()).stream()
                .map(unit -> new RelatedUnitView(
                        unit.getId(),
                        unit.getCode(),
                        unit.getType(),
                        Set.copyOf(Objects.requireNonNull(relations.get(unit.getId())))))
                .sorted(Comparator.comparing(RelatedUnitView::code))
                .toList();
    }

    /**
     * Acumula una relacion sobre las unidades que la tienen.
     *
     * <p>El mapa es la union de las tres tablas: una unidad aparece una sola vez, con todos sus
     * papeles. Es {@code LinkedHashMap} para que el orden no dependa del hash, aunque despues se
     * ordene por codigo.
     */
    private static void anotar(Map<UUID, EnumSet<UnitRelation>> relations, List<UUID> units, UnitRelation relation) {
        for (UUID unit : units) {
            relations
                    .computeIfAbsent(unit, id -> EnumSet.noneOf(UnitRelation.class))
                    .add(relation);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<BillingResponsibilityView> billingResponsibilitiesFor(UUID condominiumId, UUID conceptId) {
        return billingResponsibilities.currentFor(condominiumId, conceptId).stream()
                .map(UnitRelationsService::view)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OwnershipView> ownersOf(UUID condominiumId, UUID unitId) {
        requireUnitOfCondominium(condominiumId, unitId);
        return ownerships.findByCondominiumIdAndUnitIdAndValidToIsNull(condominiumId, unitId).stream()
                .map(UnitRelationsService::view)
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
    private void requireUnitOfCondominium(UUID condominiumId, UUID unitId) {
        boolean belongsToThisCondominium = units.findById(unitId)
                .map(unit -> unit.getCondominiumId().equals(condominiumId))
                .orElse(false);
        if (!belongsToThisCondominium) {
            throw ResourceNotFound.de("la unidad", unitId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<OwnershipView> ownershipHistoryOf(UUID condominiumId, UUID unitId) {
        requireUnitOfCondominium(condominiumId, unitId);
        return ownerships.findByCondominiumIdAndUnitIdOrderByValidFromDescIdDesc(condominiumId, unitId).stream()
                .map(UnitRelationsService::view)
                .toList();
    }

    private static OwnershipView view(UnitOwnership ownership) {
        return new OwnershipView(
                ownership.getId(),
                ownership.getCondominiumId(),
                ownership.getUnitId(),
                ownership.getPersonId(),
                ownership.getShare(),
                ownership.getValidFrom(),
                ownership.getValidTo());
    }

    private static OccupancyView view(UnitOccupancy occupancy) {
        return new OccupancyView(
                occupancy.getId(),
                occupancy.getCondominiumId(),
                occupancy.getUnitId(),
                occupancy.getPersonId(),
                occupancy.getRole());
    }

    private static BillingResponsibilityView view(BillingResponsibility billingResponsibility) {
        return new BillingResponsibilityView(
                billingResponsibility.getId(),
                billingResponsibility.getCondominiumId(),
                billingResponsibility.getUnitId(),
                billingResponsibility.getPersonId(),
                billingResponsibility.getConceptId());
    }
}
