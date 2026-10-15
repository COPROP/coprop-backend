package bo.coprop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.shared.RecursoNoEncontrado;
import bo.coprop.shared.ReglaDeNegocioViolada;
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
 * por el puerto {@code Correo}, porque el token no puede viajar en un evento.
 *
 * <p>El modo era {@code STANDALONE} --el de por defecto-- mientras {@code identity} no dependia de
 * nadie, y entonces decia algo util: que el modulo arrancaba solo. Ya no es cierto, y dejarlo asi
 * solo obligaria a poner un doble de {@code Correo} en cada test de este modulo. Que nadie meta una
 * dependencia <em>sin declararla</em> lo sigue comprobando {@code ModularityTests}, que es su
 * trabajo.
 */
@Import({TestcontainersConfiguration.class, DoblesDeAcceso.class, PeopleTests.RelojFijo.class})
@ApplicationModuleTest(BootstrapMode.DIRECT_DEPENDENCIES)
@Transactional
class PeopleTests {

    private static final Instant AHORA = Instant.parse("2026-10-12T12:00:00Z");

    @Autowired
    private People personas;

    private static NewPerson emma(@Nullable String carnet) {
        return new NewPerson("Emma", "Tola", carnet);
    }

    @Test
    @DisplayName("una persona se da de alta con sus nombres y su carnet")
    void seDaDeAltaConSusDatos() {
        PersonView creada = personas.register(emma("8123456"), "admin");

        assertThat(creada.id()).isNotNull();
        assertThat(creada.firstNames()).isEqualTo("Emma");
        assertThat(creada.lastNames()).isEqualTo("Tola");
        assertThat(creada.nationalId()).isEqualTo("8123456");

        assertThat(personas.find(creada.id())).isEqualTo(creada);
    }

    @Test
    @DisplayName("el carnet es opcional: de un copropietario heredado no siempre se tiene")
    void elCarnetEsOpcional() {
        PersonView creada = personas.register(emma(null), "admin");

        assertThat(creada.nationalId()).isNull();
    }

    @Test
    @DisplayName("el alta de una persona que ya existe devuelve la misma, sin error")
    void elAltaRepetidaDevuelveLaMisma() {
        PersonView primera = personas.register(emma("8123456"), "admin");

        // Decision 7: responde igual exista o no. La persona es global, asi que distinguir los dos
        // casos le diria al administrador de un condominio que esa persona esta en otro.
        PersonView segunda = personas.register(new NewPerson("Emma Lucia", "Tola Vargas", "8123456"), "otro");

        assertThat(segunda.id()).isEqualTo(primera.id());
        // Se reutiliza la fila tal como esta: el alta no es una via para editar a alguien.
        assertThat(segunda.firstNames()).isEqualTo("Emma");
    }

    @Test
    @DisplayName("el alta repetida no publica evento, para no delatar que ya estaba")
    void elAltaRepetidaNoPublicaEvento(PublishedEvents eventos) {
        personas.register(emma("8123456"), "admin");
        personas.register(emma("8123456"), "otro");

        assertThat(eventos.ofType(PersonRegistered.class)).hasSize(1);
    }

    @Test
    @DisplayName("sin carnet no hay con que reconocerla, asi que siempre crea una nueva")
    void sinCarnetSiempreCreaUnaNueva() {
        PersonView una = personas.register(emma(null), "admin");
        PersonView otra = personas.register(emma(null), "admin");

        // Dos personas pueden llamarse igual. Deducir que son la misma por el nombre fusionaria a
        // dos propietarios distintos, que es peor que duplicar uno.
        assertThat(otra.id()).isNotEqualTo(una.id());
    }

    @Test
    @DisplayName("la cuenta nace invitada, sin segundo factor y sin haber entrado nunca")
    void laCuentaNaceInvitada() {
        PersonView persona = personas.register(emma("8123456"), "admin");

        AccountView cuenta = personas.openAccount(persona.id(), "emma@coprop.bo", null, "admin");

        assertThat(cuenta.personId()).isEqualTo(persona.id());
        assertThat(cuenta.email()).isEqualTo("emma@coprop.bo");
        assertThat(cuenta.phone()).isNull();
        assertThat(cuenta.status()).isEqualTo(AccountStatus.INVITADO);
        assertThat(cuenta.mfaEnabled()).isFalse();

        assertThat(personas.findAccount(cuenta.id())).isEqualTo(cuenta);
    }

    @Test
    @DisplayName("una persona no puede tener dos cuentas")
    void unaPersonaNoPuedeTenerDosCuentas() {
        PersonView persona = personas.register(emma("8123456"), "admin");
        personas.openAccount(persona.id(), "primera@coprop.bo", null, "admin");

        assertThatThrownBy(() -> personas.openAccount(persona.id(), "segunda@coprop.bo", null, "admin"))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("ya tiene cuenta");
    }

    @Test
    @DisplayName("dos cuentas no pueden compartir el correo")
    void dosCuentasNoCompartenCorreo() {
        PersonView una = personas.register(emma("8123456"), "admin");
        PersonView otra = personas.register(new NewPerson("Ana", "Perez", "9000001"), "admin");
        personas.openAccount(una.id(), "repetido@coprop.bo", null, "admin");

        assertThatThrownBy(() -> personas.openAccount(otra.id(), "repetido@coprop.bo", null, "admin"))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("ya esta en uso");
    }

    @Test
    @DisplayName("no se abre cuenta a una persona que no existe")
    void noSeAbreCuentaAQuienNoExiste() {
        assertThatThrownBy(() -> personas.openAccount(UUID.randomUUID(), "fantasma@coprop.bo", null, "admin"))
                .isInstanceOf(RecursoNoEncontrado.class);
    }

    @Test
    @DisplayName("una persona que no existe no se encuentra")
    void unaPersonaQueNoExisteNoSeEncuentra() {
        assertThatThrownBy(() -> personas.find(UUID.randomUUID())).isInstanceOf(RecursoNoEncontrado.class);
    }

    @Test
    @DisplayName("el alta publica el evento con su actor y la hora del reloj inyectado")
    void elAltaPublicaSuEvento(PublishedEvents eventos) {
        PersonView creada = personas.register(emma("8123456"), "emma");

        assertThat(eventos.ofType(PersonRegistered.class))
                .containsExactly(new PersonRegistered(creada.id(), "emma", AHORA));
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
