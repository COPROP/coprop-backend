package bo.coprop.identity.internal;

import bo.coprop.identity.AccountOpened;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.identity.PersonRegistered;
import bo.coprop.identity.PersonView;
import bo.coprop.shared.RecursoNoEncontrado;
import bo.coprop.shared.ReglaDeNegocioViolada;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PeopleService implements People {

    private final PersonRepository personas;
    private final UserAccountRepository cuentas;
    private final ApplicationEventPublisher eventos;
    private final Clock reloj;

    PeopleService(
            PersonRepository personas, UserAccountRepository cuentas, ApplicationEventPublisher eventos, Clock reloj) {
        this.personas = personas;
        this.cuentas = cuentas;
        this.eventos = eventos;
        this.reloj = reloj;
    }

    @Override
    @Transactional
    public PersonView register(NewPerson nueva, String actor) {
        // Responde igual exista o no. Si el carnet ya esta registrado se reutiliza la fila: la
        // persona es global, asi que un alta que distinguiera los dos casos le diria al
        // administrador de un condominio que esa persona existe en otro. Mismo criterio que el
        // 404-no-403 del issue #17.
        //
        // Sin carnet no hay con que reconocerla y siempre se crea una nueva: dos personas pueden
        // llamarse igual, y deducir que son la misma por el nombre fusionaria a dos propietarios
        // distintos, que es mucho peor que duplicar uno.
        String carnet = nueva.nationalId();
        if (carnet != null) {
            var yaRegistrada = personas.findByNationalId(carnet);
            if (yaRegistrada.isPresent()) {
                return vista(yaRegistrada.get());
            }
        }

        Instant ahora = reloj.instant();
        Person persona = personas.save(new Person(nueva.firstNames(), nueva.lastNames(), carnet, ahora));

        // Solo cuando la fila se crea de verdad. Un evento por intento dejaria en la bitacora del
        // #21 el rastro de que esa persona ya estaba, que es justo lo que la respuesta uniforme
        // evita.
        eventos.publishEvent(new PersonRegistered(persona.getId(), actor, ahora));

        return vista(persona);
    }

    @Override
    @Transactional(readOnly = true)
    public PersonView find(UUID personId) {
        return vista(buscarPersona(personId));
    }

    @Override
    @Transactional
    public AccountView openAccount(UUID personId, String email, @Nullable String phone, String actor) {
        buscarPersona(personId);

        // Una cuenta por persona: el titulo del issue #11. La base tambien lo impone, pero
        // comprobarlo aqui permite decir que pasa en vez de devolver un choque de integridad.
        if (cuentas.existsByPersonId(personId)) {
            throw new ReglaDeNegocioViolada("La persona %s ya tiene cuenta.".formatted(personId));
        }
        // El correo identifica la cuenta de cara al login, asi que no puede repetirse. Esto si
        // revela que el correo esta en uso; es inevitable para no permitir dos cuentas con el
        // mismo, y el issue #18 --invitaciones-- es quien tiene que presentar bien ese caso.
        if (cuentas.existsByEmail(email)) {
            throw new ReglaDeNegocioViolada("El correo %s ya esta en uso.".formatted(email));
        }

        Instant ahora = reloj.instant();
        UserAccount cuenta = cuentas.save(new UserAccount(personId, email, phone, ahora));

        eventos.publishEvent(new AccountOpened(cuenta.getId(), personId, actor, ahora));

        return vista(cuenta);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountView findAccount(UUID accountId) {
        return vista(cuentas.findById(accountId).orElseThrow(() -> RecursoNoEncontrado.de("la cuenta", accountId)));
    }

    private Person buscarPersona(UUID personId) {
        return personas.findById(personId).orElseThrow(() -> RecursoNoEncontrado.de("la persona", personId));
    }

    private static PersonView vista(Person persona) {
        return new PersonView(
                persona.getId(), persona.getFirstNames(), persona.getLastNames(), persona.getNationalId());
    }

    private static AccountView vista(UserAccount cuenta) {
        return new AccountView(
                cuenta.getId(),
                cuenta.getPersonId(),
                cuenta.getEmail(),
                cuenta.getPhone(),
                cuenta.getStatus(),
                cuenta.isMfaEnabled());
    }
}
