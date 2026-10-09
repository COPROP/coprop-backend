package bo.coprop.condominium.internal;

import bo.coprop.condominium.BlockView;
import bo.coprop.condominium.FloorView;
import bo.coprop.condominium.NewUnit;
import bo.coprop.condominium.UnitView;
import bo.coprop.condominium.Units;
import bo.coprop.shared.ControladorDeApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unidades y jerarquia de un condominio.
 *
 * <p>El condominio va en la ruta, nunca en el cuerpo: asi no puede haber discrepancia entre uno y
 * otro, que es como se acaba creando una unidad en el condominio equivocado. La forma de la ruta
 * es la que fija Seguridad 5.4, {@code /api/v1/condominios/{condominioId}/...}, y es la misma
 * sobre la que el issue #17 montara el filtro de aislamiento.
 *
 * <p>Igual que en el alta de condominios, la restriccion por rol llega con el issue #16.
 */
@ControladorDeApi
@RestController
@RequestMapping("/condominios/{condominiumId}")
class UnitController {

    private final Units unidades;

    UnitController(Units unidades) {
        this.unidades = unidades;
    }

    @PostMapping("/unidades")
    ResponseEntity<UnitView> alta(@PathVariable UUID condominiumId, @Valid @RequestBody NewUnit nueva) {
        UnitView creada = unidades.register(condominiumId, nueva);
        return ResponseEntity.created(URI.create("/api/v1/unidades/" + creada.id()))
                .body(creada);
    }

    /**
     * Alta masiva. Todo o nada: si una falla no se crea ninguna.
     *
     * <p>Recibe una lista, no un archivo. La importacion desde Excel es el issue #30, que tiene
     * que resolver el parseo y el informe de errores fila a fila; hacerla tambien aqui seria
     * hacerla dos veces.
     */
    @PostMapping("/unidades/lote")
    ResponseEntity<List<UnitView>> altaMasiva(
            @PathVariable UUID condominiumId, @Valid @RequestBody LoteDeUnidades lote) {
        return ResponseEntity.status(201).body(unidades.registerAll(condominiumId, lote.unidades()));
    }

    @PostMapping("/bloques")
    ResponseEntity<BlockView> altaDeBloque(@PathVariable UUID condominiumId, @Valid @RequestBody NuevoBloque nuevo) {
        BlockView creado = unidades.addBlock(condominiumId, nuevo.name());
        return ResponseEntity.status(201).body(creado);
    }

    @PostMapping("/bloques/{blockId}/pisos")
    ResponseEntity<FloorView> altaDePiso(
            @PathVariable UUID condominiumId, @PathVariable UUID blockId, @Valid @RequestBody NuevoPiso nuevo) {
        return ResponseEntity.status(201).body(unidades.addFloor(blockId, nuevo.number()));
    }

    @GetMapping("/unidades/{unitId}")
    UnitView consultar(@PathVariable UUID condominiumId, @PathVariable UUID unitId) {
        return unidades.find(unitId);
    }

    /** Envuelto en un objeto y no un array suelto: deja sitio para añadir opciones al lote. */
    record LoteDeUnidades(@NotEmpty @Valid List<NewUnit> unidades) {}

    record NuevoBloque(@NotBlank @Size(max = 80) String name) {}

    record NuevoPiso(@Min(-5) @Max(200) int number) {}
}
