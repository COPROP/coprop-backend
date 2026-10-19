package bo.coprop.condominium.internal;

import bo.coprop.condominium.BillingResponsibilityView;
import bo.coprop.condominium.OccupancyRole;
import bo.coprop.condominium.OccupancyView;
import bo.coprop.condominium.OwnershipView;
import bo.coprop.condominium.UnitRelations;
import bo.coprop.shared.ApiController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las tres relaciones de una unidad: sus propietarios, quien la ocupa y quien responde por sus
 * cobros.
 *
 * <p>Cuelgan de la unidad y no de la persona porque la unidad es el recurso acotado por
 * condominio: asi la comprobacion de que pertenece a este condominio es la misma que en el resto
 * del modulo, y un identificador de otro condominio responde 404 y no 403.
 *
 * <p>Registrar una relacion es del administrador del condominio (#16). Consultarlas va por la
 * cadena completa del <strong>#15</strong>, y es el unico endpoint de hoy que resuelve un "Propias"
 * de la matriz sobre un recurso de la ruta.
 *
 * <p>Cerrarlas es el <strong>#19</strong>, y son tres operaciones y no una: la venta de la unidad
 * --que cierra las tres relaciones del vendedor de una vez-- la mudanza del ocupante y el fin de
 * una responsabilidad de pago. Ninguna borra nada: cierran la vigencia, de modo que lo historico
 * queda consultable, que es el segundo criterio de ese issue.
 */
@ApiController
@RestController
@RequestMapping("/condominios/{condominiumId}/unidades/{unitId}")
class UnitRelationsController {

    private final UnitRelations relaciones;

    UnitRelationsController(UnitRelations relaciones) {
        this.relaciones = relaciones;
    }

    @PreAuthorize("@authorization.can(authentication, 'MANAGE_UNITS')")
    @PostMapping("/propietarios")
    ResponseEntity<OwnershipView> registerOwner(
            @PathVariable UUID condominiumId,
            @PathVariable UUID unitId,
            @Valid @RequestBody NewOwner body,
            Principal principal) {
        OwnershipView created =
                relaciones.grantOwnership(condominiumId, unitId, body.personId(), body.share(), principal.getName());
        return ResponseEntity.status(201).body(created);
    }

    /**
     * Los propietarios vigentes, con su porcentaje. Es a quienes se les cobra.
     *
     * <p>Por eso lo gobierna {@code VIEW_ACCOUNT_STATEMENT} y no el permiso de gestionar unidades:
     * saber quien responde por una unidad es la cabecera de su estado de cuenta. La matriz da esa
     * fila entera a los dos administradores, "Propias" al propietario y al inquilino --que asi ven
     * a sus copropietarios y a nadie mas-- y nada a porteria ni al directorio.
     *
     * <p>Las tres consecuencias de esa celda, en orden: el administrador ve cualquier unidad; el
     * propietario que pide la de un vecino recibe <strong>404</strong>; y porteria recibe
     * <strong>403</strong>, porque ahi lo que falla es el rol y no el recurso.
     */
    @PreAuthorize("@access.canOnUnit(authentication, 'VIEW_ACCOUNT_STATEMENT', #condominiumId, #unitId)")
    @GetMapping("/propietarios")
    List<OwnershipView> owners(@PathVariable UUID condominiumId, @PathVariable UUID unitId) {
        return relaciones.ownersOf(condominiumId, unitId);
    }

    @PreAuthorize("@authorization.can(authentication, 'MANAGE_UNITS')")
    @PostMapping("/ocupantes")
    ResponseEntity<OccupancyView> registerOccupant(
            @PathVariable UUID condominiumId,
            @PathVariable UUID unitId,
            @Valid @RequestBody NewOccupant body,
            Principal principal) {
        OccupancyView created =
                relaciones.grantOccupancy(condominiumId, unitId, body.personId(), body.role(), principal.getName());
        return ResponseEntity.status(201).body(created);
    }

    @PreAuthorize("@authorization.can(authentication, 'MANAGE_UNITS')")
    @PostMapping("/responsables-de-pago")
    ResponseEntity<BillingResponsibilityView> asignarResponsable(
            @PathVariable UUID condominiumId,
            @PathVariable UUID unitId,
            @Valid @RequestBody NewBillingResponsible body,
            Principal principal) {
        BillingResponsibilityView created =
                relaciones.assignBilling(condominiumId, unitId, body.personId(), body.conceptId(), principal.getName());
        return ResponseEntity.status(201).body(created);
    }

    /**
     * El historico de titularidades: quien ha sido dueño de esta unidad y cuando. Issue #19.
     *
     * <p>Lo pide {@code MANAGE_UNITS} y no {@code VIEW_ACCOUNT_STATEMENT} como el listado de
     * arriba, y la diferencia es la que marca Seguridad 6.2: al propietario anterior se le entrega
     * un comprobante de su epoca <strong>a traves del administrador</strong>, no mirando el
     * historico el mismo. Dejar que un copropietario de hoy vea la cadena entera de dueños le daria
     * el nombre de gente que ya no tiene nada que ver con el edificio.
     *
     * <p>Incluye la titularidad vigente, para que la lista se lea como una cadena y no haya que
     * juntarla con la otra consulta.
     */
    @PreAuthorize("@authorization.can(authentication, 'MANAGE_UNITS')")
    @GetMapping("/propietarios/historico")
    List<OwnershipView> ownersHistory(@PathVariable UUID condominiumId, @PathVariable UUID unitId) {
        return relaciones.ownershipHistoryOf(condominiumId, unitId);
    }

    /**
     * La venta de la unidad: la titularidad pasa de una persona a otra. Issue #19.
     *
     * <p>Es un {@code POST} a un sub-recurso propio y no un {@code PUT} sobre los propietarios: lo
     * que se registra no es un estado nuevo de la lista de dueños sino <em>un hecho con fecha</em>,
     * y de ese hecho sale el cierre de las relaciones del vendedor. Un {@code PUT} con la lista
     * nueva dejaria que el cliente decidiera que se cierra y que no.
     *
     * <p>Responde la titularidad del comprador, que es lo unico que queda abierto al terminar.
     */
    @PreAuthorize("@authorization.can(authentication, 'MANAGE_UNITS')")
    @PostMapping("/transferencia")
    OwnershipView transferir(
            @PathVariable UUID condominiumId,
            @PathVariable UUID unitId,
            @Valid @RequestBody Transferencia venta,
            Principal principal) {
        return relaciones.transferOwnership(
                condominiumId, unitId, venta.fromPersonId(), venta.toPersonId(), principal.getName());
    }

    /**
     * La mudanza: esa persona deja de ocupar la unidad. Issue #19.
     *
     * <p>{@code DELETE} aunque la fila no se borre: lo que el cliente pide es que la relacion deje
     * de existir, y que por debajo se cierre una vigencia en vez de borrarse es asunto del dominio.
     * Responde 204 por lo mismo: no hay nada vigente que devolver.
     */
    @PreAuthorize("@authorization.can(authentication, 'MANAGE_UNITS')")
    @DeleteMapping("/ocupantes/{personId}")
    ResponseEntity<Void> endOccupancy(
            @PathVariable UUID condominiumId,
            @PathVariable UUID unitId,
            @PathVariable UUID personId,
            Principal principal) {
        relaciones.endOccupancy(condominiumId, unitId, personId, principal.getName());
        return ResponseEntity.noContent().build();
    }

    /**
     * Esa persona deja de responder por los cobros de la unidad. Issue #19.
     *
     * @param conceptId sin valor = la responsabilidad general. Hay que pasar el mismo con el que se
     *     asigno: cerrar la general no cierra las de un concepto concreto, porque son filas
     *     distintas y responden por cosas distintas
     */
    @PreAuthorize("@authorization.can(authentication, 'MANAGE_UNITS')")
    @DeleteMapping("/responsables-de-pago/{personId}")
    ResponseEntity<Void> endBillingResponsibility(
            @PathVariable UUID condominiumId,
            @PathVariable UUID unitId,
            @PathVariable UUID personId,
            @RequestParam(required = false) @Nullable UUID conceptId,
            Principal principal) {
        relaciones.endBilling(condominiumId, unitId, personId, conceptId, principal.getName());
        return ResponseEntity.noContent().build();
    }

    /**
     * Lo que hace falta para registrar una venta.
     *
     * @param fromPersonId quien vende, que tiene que ser propietario vigente de la unidad
     * @param toPersonId quien compra. Recibe el mismo porcentaje que tenia el vendedor, y por eso
     *     el porcentaje no viaja aqui: una venta parcial cambia el reparto de la unidad y se
     *     registra titularidad por titularidad
     */
    record Transferencia(@NotNull UUID fromPersonId, @NotNull UUID toPersonId) {}

    /**
     * @param share porcentaje de copropiedad. Seis decimales, igual que la alicuota de la unidad y
     *     por la misma razon: a dos, un edificio grande deja bolivianos sin repartir cada mes.
     */
    record NewOwner(
            @NotNull UUID personId,
            @NotNull @Positive @DecimalMax("100.0") @Digits(integer = 3, fraction = 6) BigDecimal share) {}

    record NewOccupant(@NotNull UUID personId, @NotNull OccupancyRole role) {}

    /**
     * @param conceptId nulo = responde por todo lo que se cobre a la unidad. El concepto como
     *     entidad nace en M2, asi que hoy no se comprueba que exista.
     */
    record NewBillingResponsible(@NotNull UUID personId, @Nullable UUID conceptId) {}
}
