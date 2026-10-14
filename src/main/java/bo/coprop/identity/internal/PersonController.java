package bo.coprop.identity.internal;

import bo.coprop.identity.AccountView;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.identity.PersonView;
import bo.coprop.shared.ControladorDeApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alta y consulta de personas, y apertura de sus cuentas.
 *
 * <p>La ruta no lleva condominio, y es deliberado: la persona y la cuenta son globales (Seguridad
 * 2), asi que no cuelgan de {@code /condominios/{id}} como todo lo demas. Lo que si cuelga del
 * condominio es la membresia, en {@link MembershipController}.
 *
 * <p><strong>Falta la restriccion por rol.</strong> Dar de alta personas en un condominio le toca
 * a su administrador, pero los roles llegan con el issue <strong>#16</strong> y la identidad con el
 * <strong>#13</strong>. Hoy solo exige estar autenticado, y como no hay forma de autenticarse,
 * nadie llega aqui.
 */
@ControladorDeApi
@RestController
@RequestMapping("/personas")
class PersonController {

    private final People personas;

    PersonController(People personas) {
        this.personas = personas;
    }

    /**
     * Da de alta una persona.
     *
     * <p>Devuelve <strong>201 siempre</strong>, tambien cuando la persona ya estaba y se reutilizo
     * la fila. Es el reverso del criterio de que el alta responda igual exista o no: un 200 para
     * "ya existia" y un 201 para "la cree" le diria al administrador de un condominio que esa
     * persona esta en otro.
     */
    // La persona es global, asi que la ruta no cuelga de un condominio y no hay autoridades de
    // condominio que mirar. Basta con administrar alguno: quien da de alta a una persona lo hace
    // para su edificio.
    @PreAuthorize("@autorizacion.puedeEnAlgunCondominio(authentication, 'GESTIONAR_UNIDADES')")
    @PostMapping
    ResponseEntity<PersonView> alta(@Valid @RequestBody NewPerson nueva, Principal quien) {
        PersonView persona = personas.register(nueva, quien.getName());
        return ResponseEntity.created(URI.create("/api/v1/personas/" + persona.id()))
                .body(persona);
    }

    @GetMapping("/{personId}")
    PersonView consultar(@PathVariable UUID personId) {
        return personas.find(personId);
    }

    /** Abre la cuenta con la que la persona entrara. El envio de la invitacion es el issue #18. */
    @PreAuthorize("@autorizacion.puedeEnAlgunCondominio(authentication, 'GESTIONAR_UNIDADES')")
    @PostMapping("/{personId}/cuenta")
    ResponseEntity<AccountView> abrirCuenta(
            @PathVariable UUID personId, @Valid @RequestBody AperturaDeCuenta apertura, Principal quien) {
        AccountView cuenta = personas.openAccount(personId, apertura.email(), apertura.phone(), quien.getName());
        return ResponseEntity.created(URI.create("/api/v1/cuentas/" + cuenta.id()))
                .body(cuenta);
    }

    /**
     * Lo que hace falta para abrir una cuenta.
     *
     * <p>No hay contraseña que pedir: la guarda el proveedor de identidad, no esta aplicacion.
     */
    record AperturaDeCuenta(@NotBlank @Email @Size(max = 160) String email, @Nullable @Size(max = 32) String phone) {}
}
