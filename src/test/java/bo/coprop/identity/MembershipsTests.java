package bo.coprop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.shared.RecursoNoEncontrado;
import bo.coprop.shared.ReglaDeNegocioViolada;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
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
 * El segundo criterio de aceptacion del issue #11: una membresia vigente por cuenta y condominio.
 *
 * <p>El condominio se inserta con SQL y no por su modulo a proposito: {@code identity} no depende
 * de {@code condominium} (regla 2 de ARCHITECTURE.md), asi que desde aqui no hay forma de crear
 * uno, y este test arranca solo este modulo. Que haga falta ese apaño es la prueba de que el
 * limite esta puesto.
 */
@Import({TestcontainersConfiguration.class, MembershipsTests.RelojFijo.class})
@ApplicationModuleTest(BootstrapMode.DIRECT_DEPENDENCIES)
@Transactional
class MembershipsTests {

    private static final Instant AHORA = Instant.parse("2026-10-12T12:00:00Z");

    private final JdbcTemplate jdbc;
    private final People personas;
    private final Memberships membresias;

    MembershipsTests(@Autowired DataSource dataSource, @Autowired People personas, @Autowired Memberships membresias) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.personas = personas;
        this.membresias = membresias;
    }

    @Test
    @DisplayName("una membresia se concede activa y con sus roles")
    void seConcedeActivaYConSusRoles() {
        UUID condominio = condominio("2000000001");
        UUID cuenta = cuentaDe("uno@coprop.bo", "7000001");

        MembershipView creada = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        assertThat(creada.accountId()).isEqualTo(cuenta);
        assertThat(creada.condominiumId()).isEqualTo(condominio);
        assertThat(creada.status()).isEqualTo(MembershipStatus.ACTIVA);
        assertThat(creada.roles()).containsExactly(MembershipRole.OWNER);

        assertThat(membresias.find(condominio, creada.id())).isEqualTo(creada);
    }

    @Test
    @DisplayName("no se pueden crear dos membresias vigentes de la misma cuenta en el mismo condominio")
    void noDosMembresiasVigentesEnElMismoCondominio() {
        UUID condominio = condominio("2000000002");
        UUID cuenta = cuentaDe("dos@coprop.bo", "7000002");
        membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        assertThatThrownBy(() -> membresias.grant(condominio, cuenta, Set.of(MembershipRole.TENANT), null, "admin"))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("ya tiene membresia vigente");
    }

    @Test
    @DisplayName("la misma cuenta si puede tener membresia en dos condominios")
    void laMismaCuentaEnDosCondominios() {
        UUID uno = condominio("2000000003");
        UUID otro = condominio("2000000004");
        UUID cuenta = cuentaDe("tres@coprop.bo", "7000003");

        // RF-03 al nivel de la membresia: propietaria en uno, inquilina en otro.
        membresias.grant(uno, cuenta, Set.of(MembershipRole.OWNER), null, "admin");
        membresias.grant(otro, cuenta, Set.of(MembershipRole.TENANT), null, "admin");

        assertThat(membresias.of(uno, cuenta)).isNotNull();
        assertThat(membresias.of(otro, cuenta)).isNotNull();
    }

    @Test
    @DisplayName("una membresia puede llevar varios roles: propietario que tambien es directivo")
    void unaMembresiaLlevaVariosRoles() {
        UUID condominio = condominio("2000000005");
        UUID cuenta = cuentaDe("cuatro@coprop.bo", "7000004");

        MembershipView creada =
                membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER, MembershipRole.BOARD), null, "admin");

        assertThat(creada.roles()).containsExactlyInAnyOrder(MembershipRole.OWNER, MembershipRole.BOARD);
    }

    @Test
    @DisplayName("una membresia sin roles se rechaza: no autorizaria nada")
    void unaMembresiaSinRolesSeRechaza() {
        UUID condominio = condominio("2000000006");
        UUID cuenta = cuentaDe("cinco@coprop.bo", "7000005");

        assertThatThrownBy(() -> membresias.grant(condominio, cuenta, Set.of(), null, "admin"))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("al menos un rol");
    }

    @Test
    @DisplayName("nadie se invita a si mismo")
    void nadieSeInvitaASiMismo() {
        UUID condominio = condominio("2000000007");
        UUID cuenta = cuentaDe("seis@coprop.bo", "7000006");

        assertThatThrownBy(() -> membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), cuenta, "admin"))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("si mismo");
    }

    @Test
    @DisplayName("no se concede membresia a una cuenta que no existe")
    void noSeConcedeACuentaInexistente() {
        UUID condominio = condominio("2000000008");

        assertThatThrownBy(() ->
                        membresias.grant(condominio, UUID.randomUUID(), Set.of(MembershipRole.OWNER), null, "admin"))
                .isInstanceOf(RecursoNoEncontrado.class);
    }

    @Test
    @DisplayName("la membresia de otro condominio no se encuentra: 404 y no 403")
    void laMembresiaDeOtroCondominioNoSeEncuentra() {
        UUID propio = condominio("2000000009");
        UUID ajeno = condominio("2000000010");
        UUID cuenta = cuentaDe("siete@coprop.bo", "7000007");
        MembershipView enElAjeno = membresias.grant(ajeno, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        assertThatThrownBy(() -> membresias.find(propio, enElAjeno.id())).isInstanceOf(RecursoNoEncontrado.class);
    }

    @Test
    @DisplayName("sin membresia vigente, consultarla devuelve nulo en vez de fallar")
    void sinMembresiaDevuelveNulo() {
        UUID condominio = condominio("2000000011");
        UUID cuenta = cuentaDe("ocho@coprop.bo", "7000008");

        // Es lo que leera el filtro del issue #17: la ausencia de membresia es una respuesta, no
        // un error.
        assertThat(membresias.of(condominio, cuenta)).isNull();
    }

    @Test
    @DisplayName("se listan solo las membresias vigentes del condominio")
    void seListanLasVigentesDelCondominio() {
        UUID condominio = condominio("2000000012");
        UUID otro = condominio("2000000013");
        UUID una = cuentaDe("nueve@coprop.bo", "7000009");
        UUID otraCuenta = cuentaDe("diez@coprop.bo", "7000010");
        membresias.grant(condominio, una, Set.of(MembershipRole.OWNER), null, "admin");
        membresias.grant(condominio, otraCuenta, Set.of(MembershipRole.TENANT), null, "admin");
        membresias.grant(otro, una, Set.of(MembershipRole.OWNER), null, "admin");

        assertThat(membresias.ofCondominium(condominio))
                .extracting(MembershipView::accountId)
                .containsExactlyInAnyOrder(una, otraCuenta);
    }

    @Test
    @DisplayName("conceder una membresia publica su evento con los roles de ese momento")
    void concederPublicaSuEvento(PublishedEvents eventos) {
        UUID condominio = condominio("2000000014");
        UUID cuenta = cuentaDe("once@coprop.bo", "7000011");

        MembershipView creada = membresias.grant(condominio, cuenta, Set.of(MembershipRole.CONDO_ADMIN), null, "emma");

        assertThat(eventos.ofType(MembershipGranted.class))
                .containsExactly(new MembershipGranted(
                        condominio, creada.id(), cuenta, Set.of(MembershipRole.CONDO_ADMIN), "emma", AHORA));
    }

    /**
     * El condominio por SQL: este modulo no puede crear uno, y es justo lo que la regla 2 quiere.
     */
    private UUID condominio(String nit) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO condominium (id, name, nit, type, time_zone, currency, status, created_at)
                VALUES (?, 'Condominio de prueba', ?, 'EDIFICIO', 'America/La_Paz', 'BOB', 'ACTIVO', ?)
                """,
                id,
                nit,
                OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC));
        return id;
    }

    private UUID cuentaDe(String correo, String carnet) {
        PersonView persona = personas.register(new NewPerson("Emma", "Tola", carnet), "admin");
        return personas.openAccount(persona.id(), correo, null, "admin").id();
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
