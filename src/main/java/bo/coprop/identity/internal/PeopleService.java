package bo.coprop.identity.internal;

import bo.coprop.identity.AccountOpened;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.identity.PersonRegistered;
import bo.coprop.identity.PersonView;
import bo.coprop.shared.BusinessRuleViolated;
import bo.coprop.shared.ResourceNotFound;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PeopleService implements People {

    private final PersonRepository people;
    private final UserAccountRepository accounts;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    PeopleService(
            PersonRepository people, UserAccountRepository accounts, ApplicationEventPublisher eventos, Clock clock) {
        this.people = people;
        this.accounts = accounts;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PersonView register(NewPerson body, String actor) {
        // Responde igual exista o no. Si el carnet ya esta registrado se reutiliza la fila: la
        // persona es global, asi que un alta que distinguiera los dos casos le diria al
        // administrador de un condominio que esa persona existe en otro. Mismo criterio que el
        // 404-no-403 del issue #17.
        //
        // Sin carnet no hay con que reconocerla y siempre se crea una nueva: dos personas pueden
        // llamarse igual, y deducir que son la misma por el nombre fusionaria a dos propietarios
        // distintos, que es mucho peor que duplicar uno.
        String carnet = body.nationalId();
        if (carnet != null) {
            var yaRegistrada = people.findByNationalId(carnet);
            if (yaRegistrada.isPresent()) {
                return view(yaRegistrada.get());
            }
        }

        Instant ahora = clock.instant();
        Person person = people.save(new Person(body.firstNames(), body.lastNames(), carnet, ahora));

        // Solo cuando la fila se crea de verdad. Un evento por intento dejaria en la bitacora del
        // #21 el rastro de que esa persona ya estaba, que es justo lo que la respuesta uniforme
        // evita.
        eventos.publishEvent(new PersonRegistered(person.getId(), actor, ahora));

        return view(person);
    }

    @Override
    @Transactional(readOnly = true)
    public PersonView find(UUID personId) {
        return view(findPerson(personId));
    }

    @Override
    @Transactional
    public AccountView openAccount(UUID personId, String email, @Nullable String phone, String actor) {
        findPerson(personId);

        // Una cuenta por persona: el titulo del issue #11. La base tambien lo impone, pero
        // comprobarlo aqui permite decir que pasa en vez de devolver un choque de integridad.
        if (accounts.existsByPersonId(personId)) {
            throw new BusinessRuleViolated("La persona %s ya tiene cuenta.".formatted(personId));
        }
        // El correo identifica la cuenta de cara al login, asi que no puede repetirse. Esto si
        // revela que el correo esta en uso; es inevitable para no permitir dos cuentas con el
        // mismo, y el issue #18 --invitaciones-- es quien tiene que presentar bien ese caso.
        if (accounts.existsByEmail(email)) {
            throw new BusinessRuleViolated("El correo %s ya esta en uso.".formatted(email));
        }

        Instant ahora = clock.instant();
        UserAccount account = accounts.save(new UserAccount(personId, email, phone, ahora));

        eventos.publishEvent(new AccountOpened(account.getId(), personId, actor, ahora));

        return account.view();
    }

    @Override
    @Transactional(readOnly = true)
    public AccountView findAccount(UUID accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> ResourceNotFound.de("la cuenta", accountId))
                .view();
    }

    private Person findPerson(UUID personId) {
        return people.findById(personId).orElseThrow(() -> ResourceNotFound.de("la persona", personId));
    }

    private static PersonView view(Person person) {
        return new PersonView(person.getId(), person.getFirstNames(), person.getLastNames(), person.getNationalId());
    }
}
