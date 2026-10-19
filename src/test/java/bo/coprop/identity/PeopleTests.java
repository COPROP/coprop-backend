package bo.coprop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.shared.BusinessRuleViolated;
import bo.coprop.shared.ResourceNotFound;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personas y cuentas: la primera mitad del issue #11.
 *
 * <p>Arranca el modulo {@code identity} y <strong>sus dependencias declaradas</strong>, que desde el
 * issue #18 son {@code shared} y {@code notifications}: la emision de una invitacion manda el correo
 * por el puerto {@code Mail}, porque el token no puede viajar en un evento.
 *
 * <p>El modo era {@code STANDALONE} --el de por defecto-- mientras {@code identity} no dependia de
 * nadie, y entonces decia algo util: que el modulo arrancaba solo. Ya no es cierto, y dejarlo asi
 * solo obligaria a poner un doble de {@code Mail} en cada test de este modulo. Que nadie meta una
 * dependencia <em>sin declararla</em> lo sigue comprobando {@code ModularityTests}, que es su
 * trabajo.
 */
@Import({TestcontainersConfiguration.class, AccessDoubles.class, PeopleTests.RelojFijo.class})
@ApplicationModuleTest(BootstrapMode.DIRECT_DEPENDENCIES)
@Transactional
class PeopleTests {

    private static final Instant AHORA = Instant.parse("2026-10-12T12:00:00Z");

    @Autowired
    private People people;

    private static NewPerson emma(@Nullable String carnet) {
        return new NewPerson("Emma", "Tola", carnet);
    }

    @Test
    @DisplayName("una persona se da de alta con sus nombres y su carnet")
    void itIsRegisteredWithItsData() {
        PersonView created = people.register(emma("8123456"), "admin");

        assertThat(created.id()).isNotNull();
        assertThat(created.firstNames()).isEqualTo("Emma");
        assertThat(created.lastNames()).isEqualTo("Tola");
        assertThat(created.nationalId()).isEqualTo("8123456");

        assertThat(people.find(created.id())).isEqualTo(created);
    }

    @Test
    @DisplayName("el carnet es opcional: de un copropietario heredado no siempre se tiene")
    void theNationalIdIsOptional() {
        PersonView created = people.register(emma(null), "admin");

        assertThat(created.nationalId()).isNull();
    }

    @Test
    @DisplayName("el alta de una persona que ya existe devuelve la misma, sin error")
    void aRepeatedRegistrationReturnsTheSameOne() {
        PersonView first = people.register(emma("8123456"), "admin");

        // Decision 7: responde igual exista o no. La persona es global, asi que distinguir los dos
        // casos le diria al administrador de un condominio que esa persona esta en otro.
        PersonView segunda = people.register(new NewPerson("Emma Lucia", "Tola Vargas", "8123456"), "otro");

        assertThat(segunda.id()).isEqualTo(first.id());
        // Se reutiliza la fila tal como esta: el alta no es una via para editar a alguien.
        assertThat(segunda.firstNames()).isEqualTo("Emma");
    }

    @Test
    @DisplayName("el alta repetida no publica evento, para no delatar que ya estaba")
    void elAltaRepetidaNoPublicaEvento(PublishedEvents eventos) {
        people.register(emma("8123456"), "admin");
        people.register(emma("8123456"), "otro");

        assertThat(eventos.ofType(PersonRegistered.class)).hasSize(1);
    }

    @Test
    @DisplayName("sin carnet no hay con que reconocerla, asi que siempre crea una nueva")
    void withoutANationalIdItAlwaysCreatesANewOne() {
        PersonView una = people.register(emma(null), "admin");
        PersonView otra = people.register(emma(null), "admin");

        // Dos personas pueden llamarse igual. Deducir que son la misma por el nombre fusionaria a
        // dos propietarios distintos, que es peor que duplicar uno.
        assertThat(otra.id()).isNotEqualTo(una.id());
    }

    @Test
    @DisplayName("la cuenta nace invitada, sin segundo factor y sin haber entrado nunca")
    void theAccountIsBornInvited() {
        PersonView person = people.register(emma("8123456"), "admin");

        AccountView account = people.openAccount(person.id(), "emma@coprop.bo", null, "admin");

        assertThat(account.personId()).isEqualTo(person.id());
        assertThat(account.email()).isEqualTo("emma@coprop.bo");
        assertThat(account.phone()).isNull();
        assertThat(account.status()).isEqualTo(AccountStatus.INVITED);
        assertThat(account.mfaEnabled()).isFalse();

        assertThat(people.findAccount(account.id())).isEqualTo(account);
    }

    @Test
    @DisplayName("una persona no puede tener dos cuentas")
    void aPersonCannotHaveTwoAccounts() {
        PersonView person = people.register(emma("8123456"), "admin");
        people.openAccount(person.id(), "primera@coprop.bo", null, "admin");

        assertThatThrownBy(() -> people.openAccount(person.id(), "segunda@coprop.bo", null, "admin"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("ya tiene cuenta");
    }

    @Test
    @DisplayName("dos cuentas no pueden compartir el correo")
    void twoAccountsDoNotShareAnEmail() {
        PersonView una = people.register(emma("8123456"), "admin");
        PersonView otra = people.register(new NewPerson("Ana", "Perez", "9000001"), "admin");
        people.openAccount(una.id(), "repetido@coprop.bo", null, "admin");

        assertThatThrownBy(() -> people.openAccount(otra.id(), "repetido@coprop.bo", null, "admin"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("ya esta en uso");
    }

    @Test
    @DisplayName("no se abre cuenta a una persona que no existe")
    void noAccountIsOpenedForSomeoneWhoDoesNotExist() {
        assertThatThrownBy(() -> people.openAccount(UUID.randomUUID(), "fantasma@coprop.bo", null, "admin"))
                .isInstanceOf(ResourceNotFound.class);
    }

    @Test
    @DisplayName("una persona que no existe no se encuentra")
    void aPersonWhoDoesNotExistIsNotFound() {
        assertThatThrownBy(() -> people.find(UUID.randomUUID())).isInstanceOf(ResourceNotFound.class);
    }

    @Test
    @DisplayName("el alta publica el evento con su actor y la hora del reloj inyectado")
    void elAltaPublicaSuEvento(PublishedEvents eventos) {
        PersonView created = people.register(emma("8123456"), "emma");

        assertThat(eventos.ofType(PersonRegistered.class))
                .containsExactly(new PersonRegistered(created.id(), "emma", AHORA));
    }

    /** Reloj parado: asi el evento se puede comparar contra una fecha concreta. */
    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {

        @Bean
        @Primary
        Clock relojDePrueba() {
            return Clock.fixed(AHORA, ZoneOffset.UTC);
        }
    }
}
