package bo.coprop.condominium.internal;

import bo.coprop.condominium.BillingResponsibilityView;
import bo.coprop.condominium.OccupancyRole;
import bo.coprop.condominium.OccupancyView;
import bo.coprop.condominium.OwnershipView;
import bo.coprop.condominium.UnitRelations;
import bo.coprop.shared.ControladorDeApi;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
 * de la matriz sobre un recurso de la ruta. Cerrar una relacion --un cambio de propietario, una
 * mudanza-- es el <strong>#19</strong>, asi que aqui solo hay alta y consulta.
 */
@ControladorDeApi
@RestController
@RequestMapping("/condominios/{condominiumId}/unidades/{unitId}")
class UnitRelationsController {

    private final UnitRelations relaciones;

    UnitRelationsController(UnitRelations relaciones) {
        this.relaciones = relaciones;
    }

    @PreAuthorize("@autorizacion.puede(authentication, 'GESTIONAR_UNIDADES')")
    @PostMapping("/propietarios")
    ResponseEntity<OwnershipView> registrarPropietario(
            @PathVariable UUID condominiumId,
            @PathVariable UUID unitId,
            @Valid @RequestBody NuevoPropietario nuevo,
            Principal quien) {
        OwnershipView creada =
                relaciones.grantOwnership(condominiumId, unitId, nuevo.personId(), nuevo.share(), quien.getName());
        return ResponseEntity.status(201).body(creada);
    }

    /**
     * Los propietarios vigentes, con su porcentaje. Es a quienes se les cobra.
     *
     * <p>Por eso lo gobierna {@code VER_ESTADO_DE_CUENTA} y no el permiso de gestionar unidades:
     * saber quien responde por una unidad es la cabecera de su estado de cuenta. La matriz da esa
     * fila entera a los dos administradores, "Propias" al propietario y al inquilino --que asi ven
     * a sus copropietarios y a nadie mas-- y nada a porteria ni al directorio.
     *
     * <p>Las tres consecuencias de esa celda, en orden: el administrador ve cualquier unidad; el
     * propietario que pide la de un vecino recibe <strong>404</strong>; y porteria recibe
     * <strong>403</strong>, porque ahi lo que falla es el rol y no el recurso.
     */
    @PreAuthorize("@acceso.puedeSobreLaUnidad(authentication, 'VER_ESTADO_DE_CUENTA', #condominiumId, #unitId)")
    @GetMapping("/propietarios")
    List<OwnershipView> propietarios(@PathVariable UUID condominiumId, @PathVariable UUID unitId) {
        return relaciones.ownersOf(condominiumId, unitId);
    }

    @PreAuthorize("@autorizacion.puede(authentication, 'GESTIONAR_UNIDADES')")
    @PostMapping("/ocupantes")
    ResponseEntity<OccupancyView> registrarOcupante(
            @PathVariable UUID condominiumId,
            @PathVariable UUID unitId,
            @Valid @RequestBody NuevoOcupante nuevo,
            Principal quien) {
        OccupancyView creada =
                relaciones.grantOccupancy(condominiumId, unitId, nuevo.personId(), nuevo.role(), quien.getName());
        return ResponseEntity.status(201).body(creada);
    }

    @PreAuthorize("@autorizacion.puede(authentication, 'GESTIONAR_UNIDADES')")
    @PostMapping("/responsables-de-pago")
    ResponseEntity<BillingResponsibilityView> asignarResponsable(
            @PathVariable UUID condominiumId,
            @PathVariable UUID unitId,
            @Valid @RequestBody NuevoResponsable nuevo,
            Principal quien) {
        BillingResponsibilityView creada =
                relaciones.assignBilling(condominiumId, unitId, nuevo.personId(), nuevo.conceptId(), quien.getName());
        return ResponseEntity.status(201).body(creada);
    }

    /**
     * @param share porcentaje de copropiedad. Seis decimales, igual que la alicuota de la unidad y
     *     por la misma razon: a dos, un edificio grande deja bolivianos sin repartir cada mes.
     */
    record NuevoPropietario(
            @NotNull UUID personId,
            @NotNull @Positive @DecimalMax("100.0") @Digits(integer = 3, fraction = 6) BigDecimal share) {}

    record NuevoOcupante(@NotNull UUID personId, @NotNull OccupancyRole role) {}

    /**
     * @param conceptId nulo = responde por todo lo que se cobre a la unidad. El concepto como
     *     entidad nace en M2, asi que hoy no se comprueba que exista.
     */
    record NuevoResponsable(@NotNull UUID personId, @Nullable UUID conceptId) {}
}
