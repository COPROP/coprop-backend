package bo.coprop.condominium.internal;

import bo.coprop.condominium.BlockView;
import bo.coprop.condominium.FloorView;
import bo.coprop.condominium.NewUnit;
import bo.coprop.condominium.UnitView;
import bo.coprop.condominium.Units;
import bo.coprop.shared.RecursoNoEncontrado;
import bo.coprop.shared.ReglaDeNegocioViolada;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UnitsService implements Units {

    private final UnitRepository unidades;
    private final BlockRepository bloques;
    private final FloorRepository pisos;
    private final CondominiumRepository condominios;

    UnitsService(
            UnitRepository unidades,
            BlockRepository bloques,
            FloorRepository pisos,
            CondominiumRepository condominios) {
        this.unidades = unidades;
        this.bloques = bloques;
        this.pisos = pisos;
        this.condominios = condominios;
    }

    @Override
    @Transactional
    public UnitView register(UUID condominiumId, NewUnit nueva) {
        return registerAll(condominiumId, List.of(nueva)).getFirst();
    }

    @Override
    @Transactional
    public List<UnitView> registerAll(UUID condominiumId, List<NewUnit> nuevas) {
        exigirCondominio(condominiumId);
        if (nuevas.isEmpty()) {
            throw new ReglaDeNegocioViolada("El lote no trae ninguna unidad.");
        }

        rechazarCodigosRepetidosEnElLote(nuevas);
        rechazarCodigosYaUsados(condominiumId, nuevas);
        nuevas.forEach(nueva -> exigirJerarquiaCoherente(condominiumId, nueva));

        List<Unit> creadas = unidades.saveAll(nuevas.stream()
                .map(nueva -> new Unit(
                        condominiumId,
                        nueva.code(),
                        nueva.type(),
                        nueva.aliquot(),
                        nueva.areaM2(),
                        nueva.blockId(),
                        nueva.floorId()))
                .toList());

        return creadas.stream().map(UnitsService::vista).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public UnitView find(UUID unitId) {
        return unidades.findById(unitId)
                .map(UnitsService::vista)
                .orElseThrow(() -> RecursoNoEncontrado.de("la unidad", unitId));
    }

    @Override
    @Transactional
    public BlockView addBlock(UUID condominiumId, String name) {
        exigirCondominio(condominiumId);
        if (bloques.existsByCondominiumIdAndName(condominiumId, name)) {
            throw new ReglaDeNegocioViolada("El condominio ya tiene un bloque llamado %s.".formatted(name));
        }
        Block bloque = bloques.save(new Block(condominiumId, name));
        return new BlockView(bloque.getId(), bloque.getCondominiumId(), bloque.getName());
    }

    @Override
    @Transactional
    public FloorView addFloor(UUID blockId, int number) {
        Block bloque = bloques.findById(blockId).orElseThrow(() -> RecursoNoEncontrado.de("el bloque", blockId));
        if (pisos.existsByBlockIdAndNumber(blockId, number)) {
            throw new ReglaDeNegocioViolada("El bloque %s ya tiene el piso %d.".formatted(bloque.getName(), number));
        }
        Floor piso = pisos.save(new Floor(blockId, number));
        return new FloorView(piso.getId(), piso.getBlockId(), piso.getNumber());
    }

    private void exigirCondominio(UUID condominiumId) {
        if (!condominios.existsById(condominiumId)) {
            throw RecursoNoEncontrado.de("el condominio", condominiumId);
        }
    }

    /**
     * El lote no puede traer dos veces el mismo codigo.
     *
     * <p>Se comprueba antes de tocar la base: si se dejara a la restriccion unica, el error
     * llegaria como un choque de integridad sin decir cual de las 200 filas es la culpable.
     */
    private static void rechazarCodigosRepetidosEnElLote(List<NewUnit> nuevas) {
        Set<String> vistos = new HashSet<>();
        List<String> repetidos = nuevas.stream()
                .map(NewUnit::code)
                .filter(code -> !vistos.add(code))
                .distinct()
                .toList();
        if (!repetidos.isEmpty()) {
            throw new ReglaDeNegocioViolada("El lote repite estos codigos de unidad: %s.".formatted(repetidos));
        }
    }

    private void rechazarCodigosYaUsados(UUID condominiumId, List<NewUnit> nuevas) {
        List<String> codes = nuevas.stream().map(NewUnit::code).toList();
        List<String> yaUsados = unidades.findByCondominiumIdAndCodeIn(condominiumId, codes).stream()
                .map(Unit::getCode)
                .sorted()
                .toList();
        if (!yaUsados.isEmpty()) {
            throw new ReglaDeNegocioViolada(
                    "El condominio ya tiene unidades con estos codigos: %s.".formatted(yaUsados));
        }
    }

    /**
     * El bloque y el piso indicados tienen que ser de este condominio.
     *
     * <p>Sin esta comprobacion, una unidad del condominio A podria colgar de una torre del B. La
     * clave foranea no lo impide: apunta a bloque, no a bloque-de-este-condominio.
     */
    private void exigirJerarquiaCoherente(UUID condominiumId, NewUnit nueva) {
        UUID blockId = nueva.blockId();
        UUID floorId = nueva.floorId();

        if (floorId != null && blockId == null) {
            throw new ReglaDeNegocioViolada(
                    "La unidad %s indica piso pero no bloque, y un piso siempre cuelga de un bloque."
                            .formatted(nueva.code()));
        }
        if (blockId == null) {
            return;
        }

        Block bloque = bloques.findById(blockId).orElseThrow(() -> RecursoNoEncontrado.de("el bloque", blockId));
        if (!bloque.getCondominiumId().equals(condominiumId)) {
            throw new ReglaDeNegocioViolada("El bloque %s no es de este condominio.".formatted(bloque.getName()));
        }
        if (floorId != null) {
            Floor piso = pisos.findById(floorId).orElseThrow(() -> RecursoNoEncontrado.de("el piso", floorId));
            if (!piso.getBlockId().equals(blockId)) {
                throw new ReglaDeNegocioViolada(
                        "El piso %d no es del bloque %s.".formatted(piso.getNumber(), bloque.getName()));
            }
        }
    }

    private static UnitView vista(Unit unidad) {
        return new UnitView(
                unidad.getId(),
                unidad.getCondominiumId(),
                unidad.getCode(),
                unidad.getType(),
                unidad.getAliquot(),
                unidad.getAreaM2(),
                unidad.getBlockId(),
                unidad.getFloorId(),
                unidad.getStatus());
    }
}
