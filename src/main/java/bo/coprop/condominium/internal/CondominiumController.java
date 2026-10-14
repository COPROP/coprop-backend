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
import org.springframework.security.access.prepost.PreAuthorize;
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
 * <p>El alta es del superadministrador y la configuracion del que maneja el dinero (#16). La
 * consulta no pide permiso a proposito, y el porque esta en ella.
 */
@ControladorDeApi
@RestController
@RequestMapping("/condominios")
class CondominiumController {

    private final Condominiums condominios;

    CondominiumController(Condominiums condominios) {
        this.condominios = condominios;
    }

    // Seguridad 3: crear condominios es del superadministrador, y solo de el.
    @PreAuthorize("@autorizacion.puede(authentication, 'CREAR_CONDOMINIOS')")
    @PostMapping
    ResponseEntity<CondominiumView> alta(@Valid @RequestBody NewCondominium nuevo, Principal quien) {
        CondominiumView creado = condominios.register(nuevo, quien.getName());
        return ResponseEntity.created(URI.create("/api/v1/condominios/" + creado.id()))
                .body(creado);
    }

    /**
     * El condominio de la ruta.
     *
     * <p><strong>Sin {@code @PreAuthorize}, y es una decision, no un olvido.</strong> Lo que hace
     * falta para verlo es pertenecer a el, y eso ya lo exigio el filtro del #17: quien llega aqui
     * tiene membresia activa o administra la plataforma, y cualquier otro recibio 404 antes de que
     * existiera un controlador. Los siete roles lo ven, porque un propietario necesita el nombre y
     * los dias de emision y vencimiento del edificio donde vive tanto como su administrador.
     *
     * <p>Exigir un permiso aqui significaria elegir una fila de la matriz que no habla de esto, y
     * dejaria a porteria o al directorio sin saber en que edificio estan. Lo que no se devuelve
     * desde aqui es nada de los vecinos: para eso estan las rutas de unidades y membresias, cada
     * una con su permiso. Lo sostiene un caso de {@code CadenaDeAutorizacionTests} que recorre los
     * siete roles.
     */
    @GetMapping("/{condominiumId}")
    CondominiumView consultar(@PathVariable UUID condominiumId) {
        return condominios.find(condominiumId);
    }

    // El dia de emision y el de vencimiento deciden CUANDO se cobra, asi que son del que maneja
    // el dinero. La matriz no tiene una fila con este nombre --lo mas cercano es "configurar
    // conceptos, tarifas de agua y politica de mora"-- y se resuelve del lado de la separacion de
    // funciones: quien administra personas y unidades no toca lo que se cobra.
    @PreAuthorize("@autorizacion.puede(authentication, 'CONFIGURAR_COBROS')")
    @PutMapping("/{condominiumId}/configuracion")
    CondominiumView cambiarConfiguracion(
            @PathVariable UUID condominiumId, @Valid @RequestBody CambioDeConfiguracion cambio, Principal quien) {
        return condominios.changeConfig(condominiumId, cambio.issueDay(), cambio.dueDay(), quien.getName());
    }

    /** Lo unico que hoy se puede cambiar de la configuracion. La mora y el agua llegan en M2. */
    record CambioDeConfiguracion(@Min(1) @Max(28) int issueDay, @Min(1) @Max(28) int dueDay) {}
}
