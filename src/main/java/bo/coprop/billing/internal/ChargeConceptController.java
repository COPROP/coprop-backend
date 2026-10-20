package bo.coprop.billing.internal;

import bo.coprop.billing.ChargeConceptView;
import bo.coprop.billing.ChargeConcepts;
import bo.coprop.billing.NewChargeConcept;
import bo.coprop.shared.ApiController;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El catalogo de conceptos de cobro de un condominio. Issue #100.
 *
 * <p>El condominio va en la ruta, nunca en el cuerpo, y la forma es la que fija Seguridad 5.4. El
 * aislamiento lo pone el filtro del #17 antes de llegar aqui: quien no tiene membresia activa en ese
 * condominio ya recibio 404.
 *
 * <p><strong>Las dos operaciones piden {@code CONFIGURE_CHARGES}</strong>, que es la fila "configurar
 * conceptos, tarifas de agua y politica de mora" de la matriz de Seguridad 3: la tiene el que maneja
 * el dinero y nadie mas, ni siquiera el administrador del condominio. Es la misma separacion de
 * funciones que gobierna {@code PUT /condominiums/{id}/configuration}.
 *
 * <p><strong>Tambien el listado</strong>, y eso merece una linea: un propietario no necesita el
 * catalogo --en su estado de cuenta vera el nombre del concepto de cada cargo, que es cosa del #31--
 * y el catalogo si dice cosas de la administracion del edificio, como que se cobra una multa nueva.
 * El dia que una pantalla de propietario necesite los nombres, lo que corresponde es que esa
 * respuesta los traiga, no abrir este endpoint.
 */
@ApiController
@RestController
@RequestMapping("/condominiums/{condominiumId}/charge-concepts")
class ChargeConceptController {

    private final ChargeConcepts concepts;

    ChargeConceptController(ChargeConcepts concepts) {
        this.concepts = concepts;
    }

    /**
     * El alta.
     *
     * <p>Devuelve 201 con el concepto en el cuerpo y <strong>sin cabecera {@code Location}</strong>,
     * igual que el alta de un bloque: no hay ruta que consulte un concepto por su identificador --el
     * ticket pide alta y listado-- y una {@code Location} que responde 404 es peor que ninguna.
     */
    @PreAuthorize("@authorization.can(authentication, 'CONFIGURE_CHARGES')")
    @PostMapping
    ResponseEntity<ChargeConceptView> register(
            @PathVariable UUID condominiumId, @Valid @RequestBody NewChargeConcept body, Principal principal) {
        return ResponseEntity.status(201).body(concepts.register(condominiumId, body, principal.getName()));
    }

    @PreAuthorize("@authorization.can(authentication, 'CONFIGURE_CHARGES')")
    @GetMapping
    List<ChargeConceptView> list(@PathVariable UUID condominiumId) {
        return concepts.list(condominiumId);
    }
}
