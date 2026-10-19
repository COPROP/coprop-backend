package bo.coprop.identity;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import bo.coprop.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * El vinculo entre el sujeto del token y la cuenta de COPROP: el issue #13 visto desde el dominio.
 *
 * <p>Lo que se prueba aqui es la regla, no el HTTP: quien entra, quien no, y que queda escrito
 * cuando entra por primera vez. La cadena de seguridad que invoca esto se prueba en
 * {@code ApiAuthenticationTests}.
 */
@Import({TestcontainersConfiguration.class, AccessDoubles.class, IdentitiesTests.RelojFijo.class})
@ApplicationModuleTest(BootstrapMode.DIRECT_DEPENDENCIES)
@Transactional
class IdentitiesTests {

    private static final Instant AHORA = Instant.parse("2026-10-13T12:00:00Z");
    private static final String SUJETO = "9f1c2d3e-0000-4444-8888-aaaabbbbcccc";

    @Autowired
    private People personas;

    @Autowired
    private Identities identidades;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

    private AccountView cuentaDe(String correo) {
        PersonView persona = personas.register(new NewPerson("Emma", "Tola", null), "admin");
        return personas.openAccount(persona.id(), correo, null, "admin");
    }

    @Test
    @DisplayName("el primer login vincula la cuenta al sujeto del token y la activa")
    void elPrimerLoginVincula(PublishedEvents eventos) {
        AccountView invitada = cuentaDe("emma@coprop.bo");
        assertThat(invitada.status()).isEqualTo(AccountStatus.INVITED);

        AccountView entrada = requireNonNull(identidades.signIn(SUJETO, "emma@coprop.bo", true), "tenia que entrar");

        assertThat(entrada.id()).isEqualTo(invitada.id());
        assertThat(entrada.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(eventos.ofType(AccountActivated.class))
                .containsExactly(new AccountActivated(invitada.id(), invitada.personId(), SUJETO, AHORA));
    }

    @Test
    @DisplayName("el segundo login no vuelve a vincular ni publica nada")
    void elSegundoLoginNoPublicaNada(PublishedEvents eventos) {
        cuentaDe("emma@coprop.bo");
        identidades.signIn(SUJETO, "emma@coprop.bo", true);

        AccountView otraVez = identidades.signIn(SUJETO, "emma@coprop.bo", true);

        assertThat(otraVez).isNotNull();
        assertThat(eventos.ofType(AccountActivated.class)).hasSize(1);
    }

    @Test
    @DisplayName("una vez vinculada, se reconoce por el sujeto aunque cambie el correo")
    void seReconocePorElSujetoAunqueCambieElCorreo() {
        AccountView cuenta = cuentaDe("emma@coprop.bo");
        identidades.signIn(SUJETO, "emma@coprop.bo", true);

        // El correo se cambia en el proveedor de identidad y el token empieza a traer otro. Sigue
        // siendo la misma persona: el sujeto no cambia nunca.
        AccountView newValues =
                requireNonNull(identidades.signIn(SUJETO, "emma.tola@coprop.bo", true), "tenia que entrar");

        assertThat(newValues.id()).isEqualTo(cuenta.id());
    }

    @Test
    @DisplayName("un correo sin verificar no vincula nada")
    void unCorreoSinVerificarNoVincula(PublishedEvents eventos) {
        AccountView cuenta = cuentaDe("emma@coprop.bo");

        // Vincular por correo es lo que hace que la contraseña y Google caigan en la misma cuenta.
        // Si el proveedor no garantiza el correo, eso mismo seria la via para quedarse con la
        // cuenta ajena.
        assertThat(identidades.signIn(SUJETO, "emma@coprop.bo", false)).isNull();

        assertThat(personas.findAccount(cuenta.id()).status()).isEqualTo(AccountStatus.INVITED);
        assertThat(eventos.ofType(AccountActivated.class)).isEmpty();
    }

    @Test
    @DisplayName("autenticarse no basta: sin cuenta en COPROP no hay identidad")
    void sinCuentaNoHayIdentidad() {
        // Es el caso de quien entra con Google y nadie lo invito. Seguridad 10.1: Google solo
        // autentica, el acceso lo da la invitacion.
        assertThat(identidades.signIn(SUJETO, "desconocida@gmail.com", true)).isNull();
    }

    @Test
    @DisplayName("una cuenta bloqueada no entra, y responde igual que si no existiera")
    void unaCuentaBloqueadaNoEntra() {
        AccountView cuenta = cuentaDe("emma@coprop.bo");

        // Se bloquea por SQL porque todavia no hay operacion de dominio que lo haga: bloquear y
        // dar de baja una cuenta es el issue #19. Hace falta vaciar el contexto de persistencia
        // despues, o Hibernate devolveria la instancia que ya tiene en memoria --con el estado
        // viejo-- y el test pasaria sin comprobar nada.
        entityManager.flush();
        new JdbcTemplate(dataSource).update("update user_account set status = 'BLOCKED' where id = ?", cuenta.id());
        entityManager.clear();

        assertThat(identidades.signIn(SUJETO, "emma@coprop.bo", true)).isNull();
    }

    @Test
    @DisplayName("el correo del token se compara sin distinguir mayusculas")
    void elCorreoSeComparaSinMayusculas() {
        AccountView cuenta = cuentaDe("emma@coprop.bo");

        // El proveedor de identidad no promete como escribe el correo, y UNIQUE (email) si
        // distingue. Sin esto, un token con el correo en mayusculas abriria el camino de "no hay
        // cuenta" para alguien que si la tiene.
        AccountView entrada = requireNonNull(identidades.signIn(SUJETO, "Emma@Coprop.bo", true), "tenia que entrar");

        assertThat(entrada.id()).isEqualTo(cuenta.id());
    }

    /** Reloj parado: asi el evento del primer login se compara contra una fecha concreta. */
    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {

        @Bean
        @Primary
        Clock relojDePrueba() {
            return Clock.fixed(AHORA, ZoneOffset.UTC);
        }
    }
}
