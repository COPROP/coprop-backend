package bo.coprop.billing.internal;

import bo.coprop.billing.BillingPeriodView;
import bo.coprop.billing.BillingPeriods;
import bo.coprop.billing.NewBillingPeriod;
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
 * Los periodos de facturacion de un condominio. Issue #101.
 *
 * <p>El condominio va en la ruta, nunca en el cuerpo, y el aislamiento lo pone el filtro del #17
 * antes de llegar aqui: quien no tiene membresia activa en ese condominio ya recibio 404.
 *
 * <p><strong>Las dos operaciones piden {@code CONFIGURE_CHARGES}</strong>, la misma separacion de
 * funciones que el catalogo de conceptos de cobro: preparar el mes que se va a cobrar es del que
 * maneja el dinero. Tambien el listado, porque los periodos abiertos dicen que meses estan sin
 * emitir, que es informacion de la administracion; el dia que un propietario necesite saber de que
 * mes es un cargo, eso vendra en su estado de cuenta (#31).
 *
 * <p><strong>No hay ruta para emitir ni para cerrar, y es una decision.</strong> Las dos
 * transiciones existen en {@link BillingPeriods}, no en HTTP: emitir es del #24 y del #25 --que
 * crean las obligaciones y ahi cambiar el estado es lo de menos-- y el calendario de cierre es del
 * #28. Abrir un {@code PUT .../status} ahora seria ofrecer una forma de emitir un mes sin emitir
 * ninguna obligacion.
 */
@ApiController
@RestController
@RequestMapping("/condominiums/{condominiumId}/billing-periods")
class BillingPeriodController {

    private final BillingPeriods periods;

    BillingPeriodController(BillingPeriods periods) {
        this.periods = periods;
    }

    /**
     * La apertura del mes.
     *
     * <p>Devuelve 201 con el periodo y <strong>sin cabecera {@code Location}</strong>, igual que el
     * alta de un concepto de cobro: no hay ruta que consulte un periodo por su identificador, y una
     * {@code Location} que responde 404 es peor que ninguna.
     */
    @PreAuthorize("@authorization.can(authentication, 'CONFIGURE_CHARGES')")
    @PostMapping
    ResponseEntity<BillingPeriodView> open(
            @PathVariable UUID condominiumId, @Valid @RequestBody NewBillingPeriod body, Principal principal) {
        return ResponseEntity.status(201).body(periods.open(condominiumId, body, principal.getName()));
    }

    @PreAuthorize("@authorization.can(authentication, 'CONFIGURE_CHARGES')")
    @GetMapping
    List<BillingPeriodView> list(@PathVariable UUID condominiumId) {
        return periods.list(condominiumId);
    }
}
