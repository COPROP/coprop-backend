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
import bo.coprop.shared.RecursoNoEncontrado;
import bo.coprop.shared.ReglaDeNegocioViolada;
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
        exigirUnidadDelCondominio(condominiumId, unitId);
        if (fromPersonId.equals(toPersonId)) {
            throw new ReglaDeNegocioViolada("Una unidad no se vende a su propio dueño.");
        }
        UnitOwnership delVendedor = titularidades
                .findByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(condominiumId, unitId, fromPersonId)
                .orElseThrow(() -> RecursoNoEncontrado.de("la titularidad vigente de la persona", fromPersonId));
        // Una venta a quien ya es copropietario cambia el reparto de la unidad, y eso no es esta
        // operacion: el porcentaje del comprador tendria que ser la suma de los dos, y aqui el que
        // se transfiere es el del vendedor. Se registra cerrando y abriendo a mano.
        if (titularidades.existsByUnitIdAndPersonIdAndValidToIsNull(unitId, toPersonId)) {
            throw new ReglaDeNegocioViolada(
                    "La persona %s ya es propietaria vigente de la unidad %s; una venta entre copropietarios cambia el reparto y se registra titularidad por titularidad."
                            .formatted(toPersonId, unitId));
        }

        Instant ahora = reloj.instant();
        BigDecimal porcentaje = delVendedor.getShare();

        delVendedor.close(ahora);
        // Las otras dos relaciones del vendedor con esta unidad se cierran tambien: las tres dan
        // acceso por igual, asi que dejar una abierta dejaria al vendedor viendo lo que ya no es
        // suyo, que es justo el criterio del issue.
        int cerradas = 1
                + cerrarOcupacionSiLaHay(condominiumId, unitId, fromPersonId, ahora, actor)
                + cerrarResponsabilidades(condominiumId, unitId, fromPersonId, ahora, actor);
        titularidades.flush();

        UnitOwnership delComprador =
                titularidades.save(new UnitOwnership(condominiumId, unitId, toPersonId, porcentaje, ahora));

        eventos.publishEvent(new OwnershipTransferred(
                condominiumId, unitId, fromPersonId, toPersonId, porcentaje, cerradas, actor, ahora));

        return vista(delComprador);
    }

    /**
     * Cierra la ocupacion del vendedor, si la tenia, y devuelve cuantas cerro.
     *
     * <p>Publica su evento igual que si la hubiera cerrado el administrador a mano: lo que pasa es
     * lo mismo --alguien dejo de ocupar-- y la bitacora no tiene por que saber que fue a cuenta de
     * una venta. El evento de la venta ya cuenta cuantas relaciones se cerraron con ella.
     */
    private int cerrarOcupacionSiLaHay(UUID condominiumId, UUID unitId, UUID personId, Instant ahora, String actor) {
        return ocupaciones
                .findByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(condominiumId, unitId, personId)
                .map(ocupacion -> {
                    ocupacion.close(ahora);
                    eventos.publishEvent(
                            new OccupancyEnded(condominiumId, unitId, personId, ocupacion.getRole(), actor, ahora));
                    return 1;
                })
                .orElse(0);
    }

    /** Cierra todas las responsabilidades de pago del vendedor sobre esa unidad. */
    private int cerrarResponsabilidades(UUID condominiumId, UUID unitId, UUID personId, Instant ahora, String actor) {
        List<BillingResponsibility> suyas = responsabilidades.findByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(
                condominiumId, unitId, personId);
        for (BillingResponsibility responsabilidad : suyas) {
            responsabilidad.close(ahora);
            eventos.publishEvent(new BillingResponsibilityEnded(
                    condominiumId, unitId, personId, responsabilidad.getConceptId(), actor, ahora));
        }
        return suyas.size();
    }

    @Override
    @Transactional
    public void endOccupancy(UUID condominiumId, UUID unitId, UUID personId, String actor) {
        exigirUnidadDelCondominio(condominiumId, unitId);
        UnitOccupancy ocupacion = ocupaciones
                .findByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(condominiumId, unitId, personId)
                .orElseThrow(() -> RecursoNoEncontrado.de("la ocupacion vigente de la persona", personId));

        Instant ahora = reloj.instant();
        ocupacion.close(ahora);

        eventos.publishEvent(new OccupancyEnded(condominiumId, unitId, personId, ocupacion.getRole(), actor, ahora));
    }

    @Override
    @Transactional
    public void endBilling(UUID condominiumId, UUID unitId, UUID personId, @Nullable UUID conceptId, String actor) {
        exigirUnidadDelCondominio(condominiumId, unitId);
        // Como en el alta, el caso del concepto nulo necesita su propia consulta.
        BillingResponsibility responsabilidad = (conceptId == null
                        ? responsabilidades.findByCondominiumIdAndUnitIdAndPersonIdAndConceptIdIsNullAndValidToIsNull(
                                condominiumId, unitId, personId)
                        : responsabilidades.findByCondominiumIdAndUnitIdAndPersonIdAndConceptIdAndValidToIsNull(
                                condominiumId, unitId, personId, conceptId))
                .orElseThrow(
                        () -> RecursoNoEncontrado.de("la responsabilidad de pago vigente de la persona", personId));

        Instant ahora = reloj.instant();
        responsabilidad.close(ahora);

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
        return titularidades.existsByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(condominiumId, unitId, personId)
                || ocupaciones.existsByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(
                        condominiumId, unitId, personId)
                || responsabilidades.existsByCondominiumIdAndUnitIdAndPersonIdAndValidToIsNull(
                        condominiumId, unitId, personId);
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
    public List<RelatedUnitView> relatedTo(UUID condominiumId, UUID personId) {
        Map<UUID, EnumSet<UnitRelation>> relaciones = new LinkedHashMap<>();
        anotar(relaciones, unitsOwnedBy(condominiumId, personId), UnitRelation.PROPIETARIO);
        anotar(relaciones, unitsOccupiedBy(condominiumId, personId), UnitRelation.OCUPANTE);
        anotar(relaciones, unitsBilledTo(condominiumId, personId), UnitRelation.RESPONSABLE_DE_PAGO);
        if (relaciones.isEmpty()) {
            return List.of();
        }

        // Las unidades, en bloque y no una por una: una persona con veinte parqueos no puede costar
        // veinte consultas. Es el mismo criterio con el que el alta masiva comprueba los codigos.
        return unidades.findAllById(relaciones.keySet()).stream()
                .map(unidad -> new RelatedUnitView(
                        unidad.getId(),
                        unidad.getCode(),
                        unidad.getType(),
                        Set.copyOf(Objects.requireNonNull(relaciones.get(unidad.getId())))))
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
    private static void anotar(
            Map<UUID, EnumSet<UnitRelation>> relaciones, List<UUID> unidades, UnitRelation relacion) {
        for (UUID unidad : unidades) {
            relaciones
                    .computeIfAbsent(unidad, id -> EnumSet.noneOf(UnitRelation.class))
                    .add(relacion);
        }
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

    @Override
    @Transactional(readOnly = true)
    public List<OwnershipView> ownershipHistoryOf(UUID condominiumId, UUID unitId) {
        exigirUnidadDelCondominio(condominiumId, unitId);
        return titularidades.findByCondominiumIdAndUnitIdOrderByValidFromDescIdDesc(condominiumId, unitId).stream()
                .map(UnitRelationsService::vista)
                .toList();
    }

    private static OwnershipView vista(UnitOwnership titularidad) {
        return new OwnershipView(
                titularidad.getId(),
                titularidad.getCondominiumId(),
                titularidad.getUnitId(),
                titularidad.getPersonId(),
                titularidad.getShare(),
                titularidad.getValidFrom(),
                titularidad.getValidTo());
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
