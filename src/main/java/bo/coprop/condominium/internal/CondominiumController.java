package bo.coprop.condominium.internal;

import bo.coprop.condominium.CondominiumView;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import bo.coprop.shared.ControladorDeApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alta y consulta de condominios.
 *
 * <p>La ruta se declara sin el prefijo de version: lo antepone la configuracion del modulo
 * {@code api} a todo lo anotado con {@link ControladorDeApi}, asi que esto se sirve bajo
 * {@code /api/v1/condominios}, que es lo que fija Seguridad 5.4.
 *
 * <p><strong>Falta la restriccion por rol.</strong> El alcance del issue #9 dice que el alta la
 * hace el superadministrador, pero los roles llegan con el issue <strong>#16</strong> y la
 * identidad con el <strong>#13</strong>. Hoy estos endpoints solo exigen estar autenticado, y como
 * todavia no hay forma de autenticarse, nadie puede llegar a ellos. Queda derivado al #16.
 */
@ControladorDeApi
@RestController
@RequestMapping("/condominios")
class CondominiumController {

    private final Condominiums condominios;

    CondominiumController(Condominiums condominios) {
        this.condominios = condominios;
    }

    @PostMapping
    ResponseEntity<CondominiumView> alta(@Valid @RequestBody NewCondominium nuevo, Principal quien) {
        CondominiumView creado = condominios.register(nuevo, quien.getName());
        return ResponseEntity.created(URI.create("/api/v1/condominios/" + creado.id()))
                .body(creado);
    }

    @GetMapping("/{condominiumId}")
    CondominiumView consultar(@PathVariable UUID condominiumId) {
        return condominios.find(condominiumId);
    }

    @PutMapping("/{condominiumId}/configuracion")
    CondominiumView cambiarConfiguracion(
            @PathVariable UUID condominiumId, @Valid @RequestBody CambioDeConfiguracion cambio, Principal quien) {
        return condominios.changeConfig(condominiumId, cambio.issueDay(), cambio.dueDay(), quien.getName());
    }

    /** Lo unico que hoy se puede cambiar de la configuracion. La mora y el agua llegan en M2. */
    record CambioDeConfiguracion(@Min(1) @Max(28) int issueDay, @Min(1) @Max(28) int dueDay) {}
}
