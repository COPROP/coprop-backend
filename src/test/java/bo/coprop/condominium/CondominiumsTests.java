package bo.coprop.condominium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.shared.RecursoNoEncontrado;
import bo.coprop.shared.ReglaDeNegocioViolada;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los dos criterios de aceptacion del issue #9.
 *
 * <p>Es un {@link ApplicationModuleTest}, no un {@code @SpringBootTest} entero: arranca solo el
 * modulo {@code condominium} y sus dependencias declaradas. Si manana alguien le mete una
 * dependencia que no declaro, este test deja de arrancar, que es antes de que lo note nadie.
 */
@Import({TestcontainersConfiguration.class, CondominiumsTests.RelojFijo.class})
@ApplicationModuleTest
// Cada test se revierte al terminar. Sin esto comparten base y el segundo que da de alta "Las
// Palmas" choca con el NIT unico del primero.
@Transactional
class CondominiumsTests {

    private static final Instant AHORA = Instant.parse("2026-10-09T12:00:00Z");

    @Autowired
    private Condominiums condominios;

    private static NewCondominium lasPalmas() {
        return new NewCondominium("Las Palmas", "1234567890", CondominiumType.EDIFICIO, "America/La_Paz", 1, 15);
    }

    @Test
    @DisplayName("un condominio se crea con su configuracion minima y en estado activo")
    void seCreaConSuConfiguracionMinima() {
        CondominiumView creado = condominios.register(lasPalmas(), "emma");

        assertThat(creado.id()).isNotNull();
        assertThat(creado.name()).isEqualTo("Las Palmas");
        assertThat(creado.status()).isEqualTo(CondominiumStatus.ACTIVO);
        assertThat(creado.currency()).isEqualTo("BOB");
        assertThat(creado.issueDay()).isEqualTo(1);
        assertThat(creado.dueDay()).isEqualTo(15);

        assertThat(condominios.find(creado.id())).isEqualTo(creado);
    }

    @Test
    @DisplayName("el alta publica el evento que auditara el #21, con actor y fecha")
    void elAltaQuedaAuditada(PublishedEvents eventos) {
        CondominiumView creado = condominios.register(lasPalmas(), "emma");

        assertThat(eventos.ofType(CondominiumRegistered.class)).singleElement().satisfies(evento -> {
            assertThat(evento.condominiumId()).isEqualTo(creado.id());
            assertThat(evento.name()).isEqualTo("Las Palmas");
            assertThat(evento.actor()).isEqualTo("emma");
            assertThat(evento.occurredAt()).isEqualTo(AHORA);
        });
    }

    @Test
    @DisplayName("cambiar la configuracion no pisa la anterior: la cierra y abre otra")
    void cambiarLaConfiguracionNoPisaLaAnterior() {
        CondominiumView creado = condominios.register(lasPalmas(), "emma");

        CondominiumView cambiado = condominios.changeConfig(creado.id(), 5, 20, "emma");

        // Lo vigente es lo nuevo...
        assertThat(cambiado.issueDay()).isEqualTo(5);
        assertThat(cambiado.dueDay()).isEqualTo(20);
        assertThat(condominios.find(creado.id()).issueDay()).isEqualTo(5);

        // ...y lo anterior sigue existiendo, cerrado. Esto es lo que hara que una obligacion
        // emitida con la configuracion vieja no cambie bajo los pies cuando llegue M2.
        assertThat(configuracionesDe(creado.id())).isEqualTo(2);
        assertThat(vigentesDe(creado.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("el cambio de configuracion dice quien lo hizo y desde que valores")
    void elCambioDeConfiguracionQuedaAuditado(PublishedEvents eventos) {
        CondominiumView creado = condominios.register(lasPalmas(), "emma");

        condominios.changeConfig(creado.id(), 5, 20, "ana");

        // La fila cerrada ya dice cuando cambio; sin este evento no quedaria quien ni desde que
        // valores, y es el cambio que mueve los vencimientos y la mora (analisis 12.2).
        assertThat(eventos.ofType(CondominiumConfigChanged.class))
                .singleElement()
                .satisfies(evento -> {
                    assertThat(evento.condominiumId()).isEqualTo(creado.id());
                    assertThat(evento.previousIssueDay()).isEqualTo(1);
                    assertThat(evento.previousDueDay()).isEqualTo(15);
                    assertThat(evento.issueDay()).isEqualTo(5);
                    assertThat(evento.dueDay()).isEqualTo(20);
                    assertThat(evento.actor()).isEqualTo("ana");
                    assertThat(evento.occurredAt()).isEqualTo(AHORA);
                });
    }

    @Test
    @DisplayName("dos condominios no pueden compartir NIT: serian el mismo ante la ley")
    void elNitEsUnico() {
        condominios.register(lasPalmas(), "emma");

        assertThatThrownBy(() -> condominios.register(lasPalmas(), "emma"))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("1234567890");
    }

    @Test
    @DisplayName("un condominio que no existe da no encontrado, no un nulo")
    void elQueNoExisteDaNoEncontrado() {
        assertThatThrownBy(() -> condominios.find(UUID.randomUUID())).isInstanceOf(RecursoNoEncontrado.class);
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private long configuracionesDe(UUID condominiumId) {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM condominium_config WHERE condominium_id = ?", Long.class, condominiumId);
        return total == null ? 0 : total;
    }

    private long vigentesDe(UUID condominiumId) {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM condominium_config WHERE condominium_id = ? AND valid_to IS NULL",
                Long.class,
                condominiumId);
        return total == null ? 0 : total;
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
