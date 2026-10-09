package bo.coprop.condominium.internal;

import bo.coprop.condominium.BlockRegistered;
import bo.coprop.condominium.BlockView;
import bo.coprop.condominium.FloorRegistered;
import bo.coprop.condominium.FloorView;
import bo.coprop.condominium.NewUnit;
import bo.coprop.condominium.UnitView;
import bo.coprop.condominium.Units;
import bo.coprop.condominium.UnitsRegistered;
import bo.coprop.shared.RecursoNoEncontrado;
import bo.coprop.shared.ReglaDeNegocioViolada;
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

    private final UnitRepository unidades;
    private final BlockRepository bloques;
    private final FloorRepository pisos;
    private final CondominiumRepository condominios;
    private final ApplicationEventPublisher eventos;
    private final Clock reloj;

    UnitsService(
            UnitRepository unidades,
            BlockRepository bloques,
            FloorRepository pisos,
            CondominiumRepository condominios,
            ApplicationEventPublisher eventos,
            Clock reloj) {
        this.unidades = unidades;
        this.bloques = bloques;
        this.pisos = pisos;
        this.condominios = condominios;
        this.eventos = eventos;
        this.reloj = reloj;
    }

    @Override
    @Transactional
    public UnitView register(UUID condominiumId, NewUnit nueva, String actor) {
        return registerAll(condominiumId, List.of(nueva), actor).getFirst();
    }

    @Override
    @Transactional
    public List<UnitView> registerAll(UUID condominiumId, List<NewUnit> nuevas, String actor) {
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

        // Un evento por llamada y no uno por unidad: el alta masiva es una sola decision de una
        // sola persona, y la bitacora del #21 no gana nada con doscientas lineas identicas.
        eventos.publishEvent(new UnitsRegistered(
                condominiumId, creadas.stream().map(Unit::getId).toList(), actor, reloj.instant()));

        return creadas.stream().map(UnitsService::vista).toList();
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
        return unidades.findById(unitId)
                .filter(unidad -> unidad.getCondominiumId().equals(condominiumId))
                .map(UnitsService::vista)
                .orElseThrow(() -> RecursoNoEncontrado.de("la unidad", unitId));
    }

    @Override
    @Transactional
    public BlockView addBlock(UUID condominiumId, String name, String actor) {
        exigirCondominio(condominiumId);
        if (bloques.existsByCondominiumIdAndName(condominiumId, name)) {
            throw new ReglaDeNegocioViolada("El condominio ya tiene un bloque llamado %s.".formatted(name));
        }
        Block bloque = bloques.save(new Block(condominiumId, name));

        eventos.publishEvent(
                new BlockRegistered(condominiumId, bloque.getId(), bloque.getName(), actor, reloj.instant()));

        return new BlockView(bloque.getId(), bloque.getCondominiumId(), bloque.getName());
    }

    @Override
    @Transactional
    public FloorView addFloor(UUID condominiumId, UUID blockId, int number, String actor) {
        // El bloque llega por la ruta, debajo del condominio: uno de otro condominio responde "no
        // existe", igual que en find. Sin esta comprobacion, la ruta de un condominio sirve para
        // colgar pisos de las torres de cualquier otro.
        Block bloque = bloques.findById(blockId)
                .filter(candidato -> candidato.getCondominiumId().equals(condominiumId))
                .orElseThrow(() -> RecursoNoEncontrado.de("el bloque", blockId));

        if (pisos.existsByBlockIdAndNumber(blockId, number)) {
            throw new ReglaDeNegocioViolada("El bloque %s ya tiene el piso %d.".formatted(bloque.getName(), number));
        }
        Floor piso = pisos.save(new Floor(blockId, number));

        eventos.publishEvent(
                new FloorRegistered(condominiumId, blockId, piso.getId(), piso.getNumber(), actor, reloj.instant()));

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
