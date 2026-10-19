package bo.coprop.condominium.internal;

import bo.coprop.condominium.BlockRegistered;
import bo.coprop.condominium.BlockView;
import bo.coprop.condominium.FloorRegistered;
import bo.coprop.condominium.FloorView;
import bo.coprop.condominium.NewUnit;
import bo.coprop.condominium.UnitView;
import bo.coprop.condominium.Units;
import bo.coprop.condominium.UnitsRegistered;
import bo.coprop.shared.BusinessRuleViolated;
import bo.coprop.shared.ResourceNotFound;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UnitsService implements Units {

    private final UnitRepository units;
    private final BlockRepository blocks;
    private final FloorRepository floors;
    private final CondominiumRepository condominiums;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    UnitsService(
            UnitRepository units,
            BlockRepository blocks,
            FloorRepository floors,
            CondominiumRepository condominiums,
            ApplicationEventPublisher eventos,
            Clock clock) {
        this.units = units;
        this.blocks = blocks;
        this.floors = floors;
        this.condominiums = condominiums;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UnitView register(UUID condominiumId, NewUnit body, String actor) {
        return registerAll(condominiumId, List.of(body), actor).getFirst();
    }

    @Override
    @Transactional
    public List<UnitView> registerAll(UUID condominiumId, List<NewUnit> incoming, String actor) {
        requireCondominium(condominiumId);
        if (incoming.isEmpty()) {
            throw new BusinessRuleViolated("El lote no trae ninguna unidad.");
        }

        rejectDuplicateCodesInBatch(incoming);
        rejectAlreadyUsedCodes(condominiumId, incoming);
        incoming.forEach(body -> requireCoherentHierarchy(condominiumId, body));

        List<Unit> created = units.saveAll(incoming.stream()
                .map(body -> new Unit(
                        condominiumId,
                        body.code(),
                        body.type(),
                        body.aliquot(),
                        body.areaM2(),
                        body.blockId(),
                        body.floorId()))
                .toList());

        // Un evento por llamada y no uno por unidad: el alta masiva es una sola decision de una
        // sola persona, y la bitacora del #21 no gana nada con doscientas lineas identicas.
        eventos.publishEvent(new UnitsRegistered(
                condominiumId, created.stream().map(Unit::getId).toList(), actor, clock.instant()));

        return created.stream().map(UnitsService::view).toList();
    }

    /**
     * La unidad tiene que ser de este condominio.
     *
     * <p>No basta con buscarla por su identificador: pedir la unidad de otro condominio desde la
     * ruta de este tiene que responder lo mismo que pedir una que no existe (Seguridad 7.1, y el
     * criterio 404-no-403 que el issue #5 derivo al #17). La membresia la comprobara el filtro del
     * #17, pero que el recurso sea del condominio de la ruta se puede comprobar ya.
     */
    @Override
    @Transactional(readOnly = true)
    public UnitView find(UUID condominiumId, UUID unitId) {
        return units.findById(unitId)
                .filter(unit -> unit.getCondominiumId().equals(condominiumId))
                .map(UnitsService::view)
                .orElseThrow(() -> ResourceNotFound.de("la unidad", unitId));
    }

    @Override
    @Transactional
    public BlockView addBlock(UUID condominiumId, String name, String actor) {
        requireCondominium(condominiumId);
        if (blocks.existsByCondominiumIdAndName(condominiumId, name)) {
            throw new BusinessRuleViolated("El condominio ya tiene un bloque llamado %s.".formatted(name));
        }
        Block block = blocks.save(new Block(condominiumId, name));

        eventos.publishEvent(
                new BlockRegistered(condominiumId, block.getId(), block.getName(), actor, clock.instant()));

        return new BlockView(block.getId(), block.getCondominiumId(), block.getName());
    }

    @Override
    @Transactional
    public FloorView addFloor(UUID condominiumId, UUID blockId, int number, String actor) {
        // El bloque llega por la ruta, debajo del condominio: uno de otro condominio responde "no
        // existe", igual que en find. Sin esta comprobacion, la ruta de un condominio sirve para
        // colgar pisos de las torres de cualquier otro.
        Block block = blocks.findById(blockId)
                .filter(candidato -> candidato.getCondominiumId().equals(condominiumId))
                .orElseThrow(() -> ResourceNotFound.de("el bloque", blockId));

        if (floors.existsByBlockIdAndNumber(blockId, number)) {
            throw new BusinessRuleViolated("El bloque %s ya tiene el piso %d.".formatted(block.getName(), number));
        }
        Floor floor = floors.save(new Floor(condominiumId, blockId, number));

        eventos.publishEvent(
                new FloorRegistered(condominiumId, blockId, floor.getId(), floor.getNumber(), actor, clock.instant()));

        return new FloorView(floor.getId(), floor.getBlockId(), floor.getNumber());
    }

    private void requireCondominium(UUID condominiumId) {
        if (!condominiums.existsById(condominiumId)) {
            throw ResourceNotFound.de("el condominio", condominiumId);
        }
    }

    /**
     * El lote no puede traer dos veces el mismo codigo.
     *
     * <p>Se comprueba antes de tocar la base: si se dejara a la restriccion unica, el error
     * llegaria como un choque de integridad sin decir cual de las 200 filas es la culpable.
     */
    private static void rejectDuplicateCodesInBatch(List<NewUnit> incoming) {
        Set<String> vistos = new HashSet<>();
        List<String> duplicates = incoming.stream()
                .map(NewUnit::code)
                .filter(code -> !vistos.add(code))
                .distinct()
                .toList();
        if (!duplicates.isEmpty()) {
            throw new BusinessRuleViolated("El lote repite estos codigos de unidad: %s.".formatted(duplicates));
        }
    }

    private void rejectAlreadyUsedCodes(UUID condominiumId, List<NewUnit> incoming) {
        List<String> codes = incoming.stream().map(NewUnit::code).toList();
        List<String> alreadyUsed = units.findByCondominiumIdAndCodeIn(condominiumId, codes).stream()
                .map(Unit::getCode)
                .sorted()
                .toList();
        if (!alreadyUsed.isEmpty()) {
            throw new BusinessRuleViolated(
                    "El condominio ya tiene unidades con estos codigos: %s.".formatted(alreadyUsed));
        }
    }

    /**
     * El bloque y el piso indicados tienen que ser de este condominio.
     *
     * <p>Sin esta comprobacion, una unidad del condominio A podria colgar de una torre del B. La
     * clave foranea no lo impide: apunta a bloque, no a bloque-de-este-condominio.
     */
    private void requireCoherentHierarchy(UUID condominiumId, NewUnit body) {
        UUID blockId = body.blockId();
        UUID floorId = body.floorId();

        if (floorId != null && blockId == null) {
            throw new BusinessRuleViolated(
                    "La unidad %s indica piso pero no bloque, y un piso siempre cuelga de un bloque."
                            .formatted(body.code()));
        }
        if (blockId == null) {
            return;
        }

        Block block = blocks.findById(blockId).orElseThrow(() -> ResourceNotFound.de("el bloque", blockId));
        if (!block.getCondominiumId().equals(condominiumId)) {
            throw new BusinessRuleViolated("El bloque %s no es de este condominio.".formatted(block.getName()));
        }
        if (floorId != null) {
            Floor floor = floors.findById(floorId).orElseThrow(() -> ResourceNotFound.de("el piso", floorId));
            if (!floor.getBlockId().equals(blockId)) {
                throw new BusinessRuleViolated(
                        "El piso %d no es del bloque %s.".formatted(floor.getNumber(), block.getName()));
            }
        }
    }

    private static UnitView view(Unit unit) {
        return new UnitView(
                unit.getId(),
                unit.getCondominiumId(),
                unit.getCode(),
                unit.getType(),
                unit.getAliquot(),
                unit.getAreaM2(),
                unit.getBlockId(),
                unit.getFloorId(),
                unit.getStatus());
    }
}
