package bo.coprop.condominium.internal;

import bo.coprop.condominium.CondominiumView;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import bo.coprop.shared.ApiController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.security.Principal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
 * {@code api} a todo lo anotado con {@link ApiController}, asi que esto se sirve bajo
 * {@code /api/v1/condominios}, que es lo que fija Seguridad 5.4.
 *
 * <p>El alta es del superadministrador y la configuracion del que maneja el dinero (#16). La
 * consulta no pide permiso a proposito, y el porque esta en ella.
 */
@ApiController
@RestController
@RequestMapping("/condominios")
class CondominiumController {

    private final Condominiums condominiums;

    CondominiumController(Condominiums condominiums) {
        this.condominiums = condominiums;
    }

    // Seguridad 3: crear condominios es del superadministrador, y solo de el.
    @PreAuthorize("@authorization.can(authentication, 'CREATE_CONDOMINIUMS')")
    @PostMapping
    ResponseEntity<CondominiumView> alta(@Valid @RequestBody NewCondominium body, Principal principal) {
        CondominiumView creado = condominiums.register(body, principal.getName());
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
     * una con su permiso. Lo sostiene un caso de {@code AuthorizationChainTests} que recorre los
     * siete roles.
     */
    @GetMapping("/{condominiumId}")
    CondominiumView consultar(@PathVariable UUID condominiumId) {
        return condominiums.find(condominiumId);
    }

    /**
     * Corrige los datos descriptivos del condominio. Issue #90.
     *
     * <p>Existe porque el alta no se puede deshacer y hasta ahora un NIT con un digito cambiado se
     * arreglaba en la base a mano.
     *
     * <p><strong>El permiso es {@code CREATE_CONDOMINIUMS}</strong>, y la matriz no tiene una fila que
     * diga "editar condominio". Se resuelve igual que lo hizo
     * {@code PUT /{condominiumId}/configuracion} con la suya: el registro del condominio --su
     * nombre, su NIT, donde esta-- es de quien lo dio de alta, que es el superadministrador y nadie
     * mas. El administrador del condominio administra lo que hay dentro, no la ficha.
     *
     * <p><strong>El segundo factor se pide solo si el NIT cambia</strong>, y la condicion mira la
     * base y no el cuerpo: en un {@code PUT} los tres campos viajan siempre, asi que exigirlo por
     * que el campo venga haria imposible corregir el nombre sin reautenticarse. Va despues de la
     * autorizacion en la expresion, como manda el #22: SpEL corta en el primer {@code false}, asi
     * que el 403 y el 404 ganan y no se le pide el factor a quien va a recibir una negativa igual.
     *
     * <p>Lo que este endpoint <strong>no</strong> cambia es el tipo y el estado, cada uno por su
     * motivo, y los dos estan escritos en {@link Condominiums#edit}.
     */
    @PreAuthorize("@authorization.can(authentication, 'CREATE_CONDOMINIUMS')"
            + " and @reauthentication.ifNitChanges(authentication, #condominiumId, #change.nit())")
    @PutMapping("/{condominiumId}")
    CondominiumView editar(@PathVariable UUID condominiumId, @Valid @RequestBody Edicion change, Principal principal) {
        return condominiums.edit(condominiumId, change.name(), change.nit(), change.address(), principal.getName());
    }

    // El dia de emision y el de vencimiento deciden CUANDO se cobra, asi que son del que maneja
    // el dinero. La matriz no tiene una fila con este nombre --lo mas cercano es "configurar
    // conceptos, tarifas de agua y politica de mora"-- y se resuelve del lado de la separacion de
    // funciones: quien administra personas y unidades no toca lo que se cobra.
    @PreAuthorize("@authorization.can(authentication, 'CONFIGURE_CHARGES')")
    @PutMapping("/{condominiumId}/configuracion")
    CondominiumView cambiarConfiguracion(
            @PathVariable UUID condominiumId, @Valid @RequestBody ConfigChange change, Principal principal) {
        return condominiums.changeConfig(condominiumId, change.issueDay(), change.dueDay(), principal.getName());
    }

    /**
     * El cuerpo de la edicion: los tres datos descriptivos, los tres obligatorios menos la
     * direccion.
     *
     * <p>La direccion admite nula porque hay condominios que no la tienen --el alta nunca la pidio--
     * y porque mandarla nula es la unica forma de decir que no se sabe. Las longitudes son las de la
     * tabla.
     */
    record Edicion(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 20) String nit,
            @Nullable @Size(max = 200) String address) {}

    /** Lo unico que hoy se puede cambiar de la configuracion. La mora y el agua llegan en M2. */
    record ConfigChange(@Min(1) @Max(28) int issueDay, @Min(1) @Max(28) int dueDay) {}
}
