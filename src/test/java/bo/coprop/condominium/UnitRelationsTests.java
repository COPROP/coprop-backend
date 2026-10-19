package bo.coprop.condominium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.shared.BusinessRuleViolated;
import bo.coprop.shared.ResourceNotFound;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * El primer criterio de aceptacion del issue #11 y la razon de que las relaciones sean tres.
 *
 * <p>Las personas se insertan con SQL: {@code person} es de {@code identity} y este modulo no
 * depende de el. La unidad si se crea por su API, porque es de aqui.
 */
@Import({TestcontainersConfiguration.class, UnitRelationsTests.RelojFijo.class})
@ApplicationModuleTest
@Transactional
class UnitRelationsTests {

    private static final Instant AHORA = Instant.parse("2026-10-12T12:00:00Z");
    private static final BigDecimal MITAD = new BigDecimal("50.000000");
    private static final BigDecimal ALL = new BigDecimal("100.000000");

    private final JdbcTemplate jdbc;
    private final Condominiums condominiums;
    private final Units units;
    private final UnitRelations relaciones;

    UnitRelationsTests(
            @Autowired DataSource dataSource,
            @Autowired Condominiums condominiums,
            @Autowired Units units,
            @Autowired UnitRelations relaciones) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.condominiums = condominiums;
        this.units = units;
        this.relaciones = relaciones;
    }

    @Test
    @DisplayName("RF-03: la misma persona es propietaria en un condominio e inquilina en otro")
    void ownerInOneAndTenantInAnother() {
        UUID lasPalmas = condominium("Las Palmas", "3000000001");
        UUID villaSol = condominium("Villa Sol", "3000000002");
        UUID enLasPalmas = unit(lasPalmas, "302");
        UUID enVillaSol = unit(villaSol, "A-1");
        UUID person = person("6000001");

        relaciones.grantOwnership(lasPalmas, enLasPalmas, person, MITAD, "admin");
        relaciones.grantOccupancy(villaSol, enVillaSol, person, OccupancyRole.TENANT, "admin");

        assertThat(relaciones.unitsOwnedBy(lasPalmas, person)).containsExactly(enLasPalmas);
        assertThat(relaciones.unitsOccupiedBy(villaSol, person)).containsExactly(enVillaSol);
        // Y cada condominio solo ve lo suyo.
        assertThat(relaciones.unitsOwnedBy(villaSol, person)).isEmpty();
        assertThat(relaciones.unitsOccupiedBy(lasPalmas, person)).isEmpty();
    }

    @Test
    @DisplayName("el inquilino ocupa y el propietario sigue pagando: lo que una sola tabla no diria")
    void theTenantOccupiesAndTheOwnerPays() {
        UUID condominium = condominium("Torre Azul", "3000000003");
        UUID unit = unit(condominium, "501");
        UUID propietaria = person("6000002");
        UUID inquilino = person("6000003");

        relaciones.grantOwnership(condominium, unit, propietaria, new BigDecimal("100.000000"), "admin");
        relaciones.grantOccupancy(condominium, unit, inquilino, OccupancyRole.TENANT, "admin");
        relaciones.assignBilling(condominium, unit, propietaria, null, "admin");

        assertThat(relaciones.unitsOccupiedBy(condominium, inquilino)).containsExactly(unit);
        assertThat(relaciones.unitsBilledTo(condominium, propietaria)).containsExactly(unit);
        // El inquilino ocupa y no paga; la propietaria paga y no ocupa.
        assertThat(relaciones.unitsBilledTo(condominium, inquilino)).isEmpty();
        assertThat(relaciones.unitsOccupiedBy(condominium, propietaria)).isEmpty();
    }

    @Test
    @DisplayName("una unidad admite varios copropietarios, cada uno con su porcentaje")
    void severalCoOwners() {
        UUID condominium = condominium("Los Pinos", "3000000004");
        UUID unit = unit(condominium, "101");
        UUID una = person("6000004");
        UUID otra = person("6000005");

        relaciones.grantOwnership(condominium, unit, una, MITAD, "admin");
        relaciones.grantOwnership(condominium, unit, otra, MITAD, "admin");

        assertThat(relaciones.ownersOf(condominium, unit))
                .extracting(OwnershipView::personId)
                .containsExactlyInAnyOrder(una, otra);
    }

    @Test
    @DisplayName("la misma persona no figura dos veces como propietaria de la misma unidad")
    void theSamePersonNotTwice() {
        UUID condominium = condominium("Mirador", "3000000005");
        UUID unit = unit(condominium, "201");
        UUID person = person("6000006");
        relaciones.grantOwnership(condominium, unit, person, MITAD, "admin");

        assertThatThrownBy(() -> relaciones.grantOwnership(condominium, unit, person, MITAD, "admin"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("ya es propietaria vigente");
    }

    @Test
    @DisplayName("la unidad de otro condominio no se encuentra: 404 y no 403")
    void aUnitFromAnotherCondominiumIsNotFound() {
        UUID propio = condominium("Propio", "3000000006");
        UUID ajeno = condominium("Ajeno", "3000000007");
        UUID unidadAjena = unit(ajeno, "77");
        UUID person = person("6000007");

        // Es la razon de que estas relaciones vivan en este modulo: desde identity no se podria
        // comprobar, y la ruta de un condominio serviria para colgar titularidades de otro.
        assertThatThrownBy(() -> relaciones.grantOwnership(propio, unidadAjena, person, MITAD, "admin"))
                .isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(
                        () -> relaciones.grantOccupancy(propio, unidadAjena, person, OccupancyRole.RESIDENT, "admin"))
                .isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(() -> relaciones.assignBilling(propio, unidadAjena, person, null, "admin"))
                .isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(() -> relaciones.ownersOf(propio, unidadAjena)).isInstanceOf(ResourceNotFound.class);
    }

    @Test
    @DisplayName("el responsable general de una unidad no se asigna dos veces")
    void theGeneralBillingResponsibleIsNotDuplicated() {
        UUID condominium = condominium("Responsable", "3000000008");
        UUID unit = unit(condominium, "801");
        UUID person = person("6000008");
        relaciones.assignBilling(condominium, unit, person, null, "admin");

        assertThatThrownBy(() -> relaciones.assignBilling(condominium, unit, person, null, "admin"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("ya responde");
    }

    @Test
    @DisplayName("la misma persona responde por conceptos distintos de la misma unidad")
    void responsibleForDifferentConcepts() {
        UUID condominium = condominium("Conceptos", "3000000009");
        UUID unit = unit(condominium, "802");
        UUID person = person("6000009");

        relaciones.assignBilling(condominium, unit, person, UUID.randomUUID(), "admin");
        relaciones.assignBilling(condominium, unit, person, UUID.randomUUID(), "admin");

        // La unidad sale una sola vez aunque haya dos responsabilidades.
        assertThat(relaciones.unitsBilledTo(condominium, person)).containsExactly(unit);
    }

    @Test
    @DisplayName("la misma persona no ocupa dos veces la misma unidad")
    void itDoesNotOccupyTwice() {
        UUID condominium = condominium("Ocupacion", "3000000010");
        UUID unit = unit(condominium, "901");
        UUID person = person("6000010");
        relaciones.grantOccupancy(condominium, unit, person, OccupancyRole.RESIDENT, "admin");

        assertThatThrownBy(() -> relaciones.grantOccupancy(condominium, unit, person, OccupancyRole.TENANT, "admin"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("ya ocupa");
    }

    @Test
    @DisplayName("registrar una titularidad publica su evento con el porcentaje")
    void laTitularidadPublicaSuEvento(PublishedEvents eventos) {
        UUID condominium = condominium("Evento", "3000000011");
        UUID unit = unit(condominium, "1001");
        UUID person = person("6000011");

        relaciones.grantOwnership(condominium, unit, person, MITAD, "emma");

        assertThat(eventos.ofType(OwnershipGranted.class))
                .containsExactly(new OwnershipGranted(condominium, unit, person, MITAD, "emma", AHORA));
    }

    // --- La venta de la unidad y el cierre de relaciones: issue #19 ---

    @Test
    @DisplayName("la venta pasa la titularidad y el vendedor pierde el acceso a la unidad")
    void theSaleTransfersTheOwnership() {
        UUID condominium = condominium("Venta", "3000000012");
        UUID unit = unit(condominium, "1101");
        UUID vende = person("6000012");
        UUID compra = person("6000013");
        relaciones.grantOwnership(condominium, unit, vende, ALL, "admin");

        OwnershipView delComprador = relaciones.transferOwnership(condominium, unit, vende, compra, "admin");

        // El primer criterio del issue, sobre la unidad: el propietario anterior deja de verla.
        assertThat(relaciones.isRelatedTo(condominium, unit, vende)).isFalse();
        assertThat(relaciones.isRelatedTo(condominium, unit, compra)).isTrue();
        // El comprador recibe el porcentaje del vendedor, no uno nuevo.
        assertThat(delComprador.share()).isEqualByComparingTo(ALL);
        assertThat(delComprador.validTo()).isNull();
        assertThat(relaciones.ownersOf(condominium, unit))
                .extracting(OwnershipView::personId)
                .containsExactly(compra);
    }

    @Test
    @DisplayName("la venta cierra tambien la ocupacion y los cobros del vendedor")
    void theSaleClosesTheSellersThreeRelations() {
        UUID condominium = condominium("Venta completa", "3000000013");
        UUID unit = unit(condominium, "1102");
        UUID vende = person("6000014");
        UUID compra = person("6000015");
        relaciones.grantOwnership(condominium, unit, vende, ALL, "admin");
        relaciones.grantOccupancy(condominium, unit, vende, OccupancyRole.RESIDENT, "admin");
        relaciones.assignBilling(condominium, unit, vende, null, "admin");

        relaciones.transferOwnership(condominium, unit, vende, compra, "admin");

        // Si quedara una sola abierta, el vendedor seguiria viendo lo que ya no es suyo: las tres
        // cuentan por igual en isRelatedTo.
        assertThat(relaciones.unitsOwnedBy(condominium, vende)).isEmpty();
        assertThat(relaciones.unitsOccupiedBy(condominium, vende)).isEmpty();
        assertThat(relaciones.unitsBilledTo(condominium, vende)).isEmpty();
        assertThat(relaciones.relatedTo(condominium, vende)).isEmpty();
    }

    @Test
    @DisplayName("la venta no toca al inquilino: la unidad cambia de dueño y el que vive sigue viviendo")
    void theSaleDoesNotTouchTheTenant() {
        UUID condominium = condominium("Con inquilino", "3000000014");
        UUID unit = unit(condominium, "1103");
        UUID vende = person("6000016");
        UUID compra = person("6000017");
        UUID inquilino = person("6000018");
        relaciones.grantOwnership(condominium, unit, vende, ALL, "admin");
        relaciones.grantOccupancy(condominium, unit, inquilino, OccupancyRole.TENANT, "admin");

        relaciones.transferOwnership(condominium, unit, vende, compra, "admin");

        assertThat(relaciones.unitsOccupiedBy(condominium, inquilino)).containsExactly(unit);
    }

    @Test
    @DisplayName("el historico de titularidades conserva al vendedor con sus fechas")
    void theHistoryKeepsTheSeller() {
        UUID condominium = condominium("Historico", "3000000015");
        UUID unit = unit(condominium, "1104");
        UUID vende = person("6000019");
        UUID compra = person("6000020");
        relaciones.grantOwnership(condominium, unit, vende, ALL, "admin");

        relaciones.transferOwnership(condominium, unit, vende, compra, "admin");

        // El segundo criterio del issue: lo historico se conserva, y con fechas, que es lo que hace
        // falta para dar el comprobante de su epoca que pide Seguridad 6.2.
        assertThat(relaciones.ownershipHistoryOf(condominium, unit))
                .extracting(OwnershipView::personId)
                .containsExactlyInAnyOrder(vende, compra);
        assertThat(relaciones.ownershipHistoryOf(condominium, unit))
                .filteredOn(ownership -> ownership.personId().equals(vende))
                .singleElement()
                .satisfies(delVendedor -> {
                    assertThat(delVendedor.validFrom()).isEqualTo(AHORA);
                    assertThat(delVendedor.validTo()).isEqualTo(AHORA);
                });
        // Y el listado de hoy sigue trayendo solo al dueño actual.
        assertThat(relaciones.ownersOf(condominium, unit)).hasSize(1);
    }

    @Test
    @DisplayName("no se vende lo que no se tiene, ni a quien ya es copropietario, ni a si mismo")
    void theImpossibleSales() {
        UUID condominium = condominium("Imposibles", "3000000016");
        UUID unit = unit(condominium, "1105");
        UUID dueno = person("6000021");
        UUID copropietaria = person("6000022");
        UUID ajena = person("6000023");
        relaciones.grantOwnership(condominium, unit, dueno, MITAD, "admin");
        relaciones.grantOwnership(condominium, unit, copropietaria, MITAD, "admin");

        assertThatThrownBy(() -> relaciones.transferOwnership(condominium, unit, ajena, dueno, "admin"))
                .isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(() -> relaciones.transferOwnership(condominium, unit, dueno, copropietaria, "admin"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("titularidad por titularidad");
        assertThatThrownBy(() -> relaciones.transferOwnership(condominium, unit, dueno, dueno, "admin"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("su propio dueño");
    }

    @Test
    @DisplayName("la mudanza cierra la ocupacion y no la borra")
    void movingOutClosesTheOccupancy() {
        UUID condominium = condominium("Mudanza", "3000000017");
        UUID unit = unit(condominium, "1106");
        UUID inquilino = person("6000024");
        relaciones.grantOccupancy(condominium, unit, inquilino, OccupancyRole.TENANT, "admin");

        relaciones.endOccupancy(condominium, unit, inquilino, "admin");

        assertThat(relaciones.isRelatedTo(condominium, unit, inquilino)).isFalse();
        // Y se puede volver a registrar: la fila cerrada no bloquea una nueva.
        relaciones.grantOccupancy(condominium, unit, inquilino, OccupancyRole.RESIDENT, "admin");
        assertThat(relaciones.isRelatedTo(condominium, unit, inquilino)).isTrue();
    }

    @Test
    @DisplayName("cerrar la responsabilidad general no cierra la de un concepto")
    void closingTheGeneralOneDoesNotCloseTheConceptOne() {
        UUID condominium = condominium("Responsabilidades", "3000000018");
        UUID unit = unit(condominium, "1107");
        UUID person = person("6000025");
        UUID agua = UUID.randomUUID();
        relaciones.assignBilling(condominium, unit, person, null, "admin");
        relaciones.assignBilling(condominium, unit, person, agua, "admin");

        relaciones.endBilling(condominium, unit, person, null, "admin");

        // Sigue respondiendo por el agua: son filas distintas y responden por cosas distintas.
        assertThat(relaciones.unitsBilledTo(condominium, person)).containsExactly(unit);

        relaciones.endBilling(condominium, unit, person, agua, "admin");
        assertThat(relaciones.unitsBilledTo(condominium, person)).isEmpty();
    }

    @Test
    @DisplayName("no se cierra una relacion que no existe ni una de otro condominio")
    void theImpossibleClosures() {
        UUID propio = condominium("Cierre propio", "3000000019");
        UUID ajeno = condominium("Cierre ajeno", "3000000020");
        UUID unit = unit(propio, "1108");
        UUID unidadAjena = unit(ajeno, "1109");
        UUID person = person("6000026");

        assertThatThrownBy(() -> relaciones.endOccupancy(propio, unit, person, "admin"))
                .isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(() -> relaciones.endBilling(propio, unit, person, null, "admin"))
                .isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(() -> relaciones.endOccupancy(propio, unidadAjena, person, "admin"))
                .isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(() -> relaciones.ownershipHistoryOf(propio, unidadAjena))
                .isInstanceOf(ResourceNotFound.class);
    }

    @Test
    @DisplayName("la venta publica un evento propio y los cierres de lo que arrastro")
    void laVentaPublicaSusEventos(PublishedEvents eventos) {
        UUID condominium = condominium("Eventos de venta", "3000000021");
        UUID unit = unit(condominium, "1110");
        UUID vende = person("6000027");
        UUID compra = person("6000028");
        relaciones.grantOwnership(condominium, unit, vende, ALL, "admin");
        relaciones.grantOccupancy(condominium, unit, vende, OccupancyRole.RESIDENT, "admin");

        relaciones.transferOwnership(condominium, unit, vende, compra, "emma");

        // Dos relaciones cerradas: la titularidad y la ocupacion. Va en el evento porque es lo que
        // explica que el vendedor perdiera el acceso.
        assertThat(eventos.ofType(OwnershipTransferred.class))
                .containsExactly(new OwnershipTransferred(condominium, unit, vende, compra, ALL, 2, "emma", AHORA));
        // Y la ocupacion cerrada publica lo mismo que si la hubiera cerrado el administrador a mano.
        assertThat(eventos.ofType(OccupancyEnded.class))
                .containsExactly(new OccupancyEnded(condominium, unit, vende, OccupancyRole.RESIDENT, "emma", AHORA));
    }

    private UUID condominium(String name, String nit) {
        return condominiums
                .register(new NewCondominium(name, nit, CondominiumType.BUILDING, "America/La_Paz", 1, 15), "admin")
                .id();
    }

    private UUID unit(UUID condominiumId, String code) {
        return units.register(
                        condominiumId,
                        new NewUnit(code, UnitType.APARTMENT, new BigDecimal("1.000000"), null, null, null),
                        "admin")
                .id();
    }

    /** La persona por SQL: es de {@code identity} y este modulo no depende de el. */
    private UUID person(String carnet) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO person (id, first_names, last_names, national_id, created_at)
                VALUES (?, 'Emma', 'Tola', ?, ?)
                """,
                id,
                carnet,
                OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC));
        return id;
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
