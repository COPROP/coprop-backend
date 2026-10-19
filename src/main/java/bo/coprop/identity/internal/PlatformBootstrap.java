package bo.coprop.identity.internal;

import bo.coprop.identity.PlatformAdminGranted;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Abre el primer superadministrador si no hay ninguno.
 *
 * <p>Sin esto no se puede empezar: toda la API exige identidad, la identidad exige una cuenta, y
 * las cuentas las abre alguien que ya entro. Es el mismo arranque que hace el propio Keycloak con
 * {@code KC_BOOTSTRAP_ADMIN}, y por la misma razon.
 *
 * <p><strong>Solo actua si la plataforma esta vacia de superadministradores.</strong> No es una
 * puerta trasera permanente: en cuanto hay uno, cambiar la propiedad no hace nada. Asi, dejarla
 * puesta en produccion por descuido no abre nada, y quitarla no cierra lo ya abierto.
 *
 * <p>La cuenta nace <strong>sin vincular</strong>, en {@code INVITED}: no crea credenciales ni
 * toca el proveedor de identidad. Alguien tiene que entrar con ese correo para que la cuenta se
 * vincule, que es lo que ya hace el issue #13. La propiedad dice a quien se le abre la puerta, no
 * se la abre sola.
 */
@Component
class PlatformBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PlatformBootstrap.class);

    private final PersonRepository people;
    private final UserAccountRepository accounts;
    private final PlatformAdminRepository superadministradores;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;
    private final String initialEmail;

    PlatformBootstrap(
            PersonRepository people,
            UserAccountRepository accounts,
            PlatformAdminRepository superadministradores,
            ApplicationEventPublisher eventos,
            Clock clock,
            @Value("${coprop.superadministrador-inicial:}") String initialEmail) {
        this.people = people;
        this.accounts = accounts;
        this.superadministradores = superadministradores;
        this.eventos = eventos;
        this.clock = clock;
        this.initialEmail = initialEmail;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    void openTheFirstIfNeeded() {
        if (!StringUtils.hasText(initialEmail)) {
            return;
        }
        if (superadministradores.count() > 0) {
            log.debug("Ya hay superadministradores; coprop.superadministrador-inicial no hace nada.");
            return;
        }

        Instant ahora = clock.instant();
        UserAccount account = accounts.findByEmailIgnoreCase(initialEmail).orElseGet(() -> createAccount(ahora));

        superadministradores.save(new PlatformAdmin(account.getId(), null, ahora));
        eventos.publishEvent(new PlatformAdminGranted(account.getId(), null, ahora));

        log.warn(
                "Plataforma sin superadministradores: se abrio el primero para {}. Entra con ese correo para usarlo.",
                initialEmail);
    }

    /**
     * La persona que acompaña a la cuenta.
     *
     * <p>Nombres de relleno a proposito: quien entre los corregira desde su perfil, y poner el
     * correo como nombre dejaria un dato de contacto donde va un nombre. Sin carnet, porque
     * inventarlo seria peor que no tenerlo.
     */
    private UserAccount createAccount(Instant ahora) {
        Person person = people.save(new Person("Superadministrador", "inicial", null, ahora));
        return accounts.save(new UserAccount(person.getId(), initialEmail, null, ahora));
    }
}
