package bo.coprop.condominium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.shared.BusinessRuleViolated;
import bo.coprop.shared.ResourceNotFound;
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
        return new NewCondominium("Las Palmas", "1234567890", CondominiumType.BUILDING, "America/La_Paz", 1, 15);
    }

    @Test
    @DisplayName("un condominio se crea con su configuracion minima y en estado activo")
    void seCreaConSuConfiguracionMinima() {
        CondominiumView creado = condominios.register(lasPalmas(), "emma");

        assertThat(creado.id()).isNotNull();
        assertThat(creado.name()).isEqualTo("Las Palmas");
        assertThat(creado.status()).isEqualTo(CondominiumStatus.ACTIVE);
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
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("1234567890");
    }

    @Test
    @DisplayName("un condominio que no existe da no encontrado, no un nulo")
    void elQueNoExisteDaNoEncontrado() {
        assertThatThrownBy(() -> condominios.find(UUID.randomUUID())).isInstanceOf(ResourceNotFound.class);
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    @DisplayName("editar corrige nombre, NIT y direccion, y la direccion nace nula")
    void editarCorrigeLosTresDatos() {
        CondominiumView creado = condominios.register(lasPalmas(), "emma");
        assertThat(creado.address()).isNull();

        CondominiumView editado =
                condominios.edit(creado.id(), "Las Palmas II", "9876543210", "Av. Ballivian 123", "emma");

        assertThat(editado.name()).isEqualTo("Las Palmas II");
        assertThat(editado.nit()).isEqualTo("9876543210");
        assertThat(editado.address()).isEqualTo("Av. Ballivian 123");
        // Lo que no se toca: el tipo, el estado, la zona horaria, la moneda y la configuracion
        // siguen como estaban. Es la mitad del valor del caso.
        assertThat(editado.type()).isEqualTo(creado.type());
        assertThat(editado.status()).isEqualTo(creado.status());
        assertThat(editado.timeZone()).isEqualTo(creado.timeZone());
        assertThat(editado.issueDay()).isEqualTo(creado.issueDay());
        assertThat(condominios.find(creado.id())).isEqualTo(editado);
    }

    @Test
    @DisplayName("la edicion publica el evento con los tres campos de oldValues y los tres de newValues")
    void laEdicionQuedaAuditada(PublishedEvents eventos) {
        CondominiumView creado = condominios.register(lasPalmas(), "emma");

        condominios.edit(creado.id(), "Las Palmas II", "9876543210", "Av. Ballivian 123", "ana");

        assertThat(eventos.ofType(CondominiumEdited.class)).singleElement().satisfies(evento -> {
            assertThat(evento.condominiumId()).isEqualTo(creado.id());
            assertThat(evento.actor()).isEqualTo("ana");
            assertThat(evento.occurredAt()).isEqualTo(AHORA);
            // Antes lleva los tres campos aunque solo interese el NIT: la pregunta que se hace un
            // año despues no es que cambio, sino como estaba.
            assertThat(evento.oldValues())
                    .containsEntry("name", "Las Palmas")
                    .containsEntry("nit", "1234567890")
                    .containsEntry("address", "");
            assertThat(evento.newValues())
                    .containsEntry("name", "Las Palmas II")
                    .containsEntry("nit", "9876543210")
                    .containsEntry("address", "Av. Ballivian 123");
        });
    }

    @Test
    @DisplayName("corregir solo el nombre, con su propio NIT, no choca consigo mismo")
    void mandarElMismoNitNoEsUnChoque() {
        CondominiumView creado = condominios.register(lasPalmas(), "emma");

        CondominiumView editado = condominios.edit(creado.id(), "Las Palmas II", creado.nit(), null, "emma");

        assertThat(editado.name()).isEqualTo("Las Palmas II");
        assertThat(editado.nit()).isEqualTo(creado.nit());
    }

    @Test
    @DisplayName("el NIT de otro condominio se rechaza: dos con el mismo NIT serian el mismo")
    void elNitDeOtroSeRechaza() {
        CondominiumView primero = condominios.register(lasPalmas(), "emma");
        CondominiumView segundo = condominios.register(
                new NewCondominium("Bolivar", "5555555555", CondominiumType.HOUSES, "America/La_Paz", 3, 18), "emma");

        assertThatThrownBy(() -> condominios.edit(segundo.id(), "Bolivar", primero.nit(), null, "emma"))
                .isInstanceOf(BusinessRuleViolated.class);

        assertThat(condominios.find(segundo.id()).nit()).isEqualTo("5555555555");
    }

    @Test
    @DisplayName("editar uno que no existe no crea nada: da ResourceNotFound")
    void editarUnoQueNoExisteFalla() {
        assertThatThrownBy(() -> condominios.edit(UUID.randomUUID(), "Fantasma", "1111111111", null, "emma"))
                .isInstanceOf(ResourceNotFound.class);
    }

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
