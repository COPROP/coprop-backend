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
    private final Condominiums condominios;
    private final Units unidades;
    private final UnitRelations relaciones;

    UnitRelationsTests(
            @Autowired DataSource dataSource,
            @Autowired Condominiums condominios,
            @Autowired Units unidades,
            @Autowired UnitRelations relaciones) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.condominios = condominios;
        this.unidades = unidades;
        this.relaciones = relaciones;
    }

    @Test
    @DisplayName("RF-03: la misma persona es propietaria en un condominio e inquilina en otro")
    void propietariaEnUnoEInquilinaEnOtro() {
        UUID lasPalmas = condominio("Las Palmas", "3000000001");
        UUID villaSol = condominio("Villa Sol", "3000000002");
        UUID enLasPalmas = unidad(lasPalmas, "302");
        UUID enVillaSol = unidad(villaSol, "A-1");
        UUID persona = persona("6000001");

        relaciones.grantOwnership(lasPalmas, enLasPalmas, persona, MITAD, "admin");
        relaciones.grantOccupancy(villaSol, enVillaSol, persona, OccupancyRole.TENANT, "admin");

        assertThat(relaciones.unitsOwnedBy(lasPalmas, persona)).containsExactly(enLasPalmas);
        assertThat(relaciones.unitsOccupiedBy(villaSol, persona)).containsExactly(enVillaSol);
        // Y cada condominio solo ve lo suyo.
        assertThat(relaciones.unitsOwnedBy(villaSol, persona)).isEmpty();
        assertThat(relaciones.unitsOccupiedBy(lasPalmas, persona)).isEmpty();
    }

    @Test
    @DisplayName("el inquilino ocupa y el propietario sigue pagando: lo que una sola tabla no diria")
    void elInquilinoOcupaYElPropietarioPaga() {
        UUID condominio = condominio("Torre Azul", "3000000003");
        UUID unidad = unidad(condominio, "501");
        UUID propietaria = persona("6000002");
        UUID inquilino = persona("6000003");

        relaciones.grantOwnership(condominio, unidad, propietaria, new BigDecimal("100.000000"), "admin");
        relaciones.grantOccupancy(condominio, unidad, inquilino, OccupancyRole.TENANT, "admin");
        relaciones.assignBilling(condominio, unidad, propietaria, null, "admin");

        assertThat(relaciones.unitsOccupiedBy(condominio, inquilino)).containsExactly(unidad);
        assertThat(relaciones.unitsBilledTo(condominio, propietaria)).containsExactly(unidad);
        // El inquilino ocupa y no paga; la propietaria paga y no ocupa.
        assertThat(relaciones.unitsBilledTo(condominio, inquilino)).isEmpty();
        assertThat(relaciones.unitsOccupiedBy(condominio, propietaria)).isEmpty();
    }

    @Test
    @DisplayName("una unidad admite varios copropietarios, cada uno con su porcentaje")
    void variosCopropietarios() {
        UUID condominio = condominio("Los Pinos", "3000000004");
        UUID unidad = unidad(condominio, "101");
        UUID una = persona("6000004");
        UUID otra = persona("6000005");

        relaciones.grantOwnership(condominio, unidad, una, MITAD, "admin");
        relaciones.grantOwnership(condominio, unidad, otra, MITAD, "admin");

        assertThat(relaciones.ownersOf(condominio, unidad))
                .extracting(OwnershipView::personId)
                .containsExactlyInAnyOrder(una, otra);
    }

    @Test
    @DisplayName("la misma persona no figura dos veces como propietaria de la misma unidad")
    void laMismaPersonaNoDosVeces() {
        UUID condominio = condominio("Mirador", "3000000005");
        UUID unidad = unidad(condominio, "201");
        UUID persona = persona("6000006");
        relaciones.grantOwnership(condominio, unidad, persona, MITAD, "admin");

        assertThatThrownBy(() -> relaciones.grantOwnership(condominio, unidad, persona, MITAD, "admin"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("ya es propietaria vigente");
    }

    @Test
    @DisplayName("la unidad de otro condominio no se encuentra: 404 y no 403")
    void laUnidadDeOtroCondominioNoSeEncuentra() {
        UUID propio = condominio("Propio", "3000000006");
        UUID ajeno = condominio("Ajeno", "3000000007");
        UUID unidadAjena = unidad(ajeno, "77");
        UUID persona = persona("6000007");

        // Es la razon de que estas relaciones vivan en este modulo: desde identity no se podria
        // comprobar, y la ruta de un condominio serviria para colgar titularidades de otro.
        assertThatThrownBy(() -> relaciones.grantOwnership(propio, unidadAjena, persona, MITAD, "admin"))
                .isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(
                        () -> relaciones.grantOccupancy(propio, unidadAjena, persona, OccupancyRole.RESIDENT, "admin"))
                .isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(() -> relaciones.assignBilling(propio, unidadAjena, persona, null, "admin"))
                .isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(() -> relaciones.ownersOf(propio, unidadAjena)).isInstanceOf(ResourceNotFound.class);
    }

    @Test
    @DisplayName("el responsable general de una unidad no se asigna dos veces")
    void elResponsableGeneralNoSeDuplica() {
        UUID condominio = condominio("Responsable", "3000000008");
        UUID unidad = unidad(condominio, "801");
        UUID persona = persona("6000008");
        relaciones.assignBilling(condominio, unidad, persona, null, "admin");

        assertThatThrownBy(() -> relaciones.assignBilling(condominio, unidad, persona, null, "admin"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("ya responde");
    }

    @Test
    @DisplayName("la misma persona responde por conceptos distintos de la misma unidad")
    void responsablePorConceptosDistintos() {
        UUID condominio = condominio("Conceptos", "3000000009");
        UUID unidad = unidad(condominio, "802");
        UUID persona = persona("6000009");

        relaciones.assignBilling(condominio, unidad, persona, UUID.randomUUID(), "admin");
        relaciones.assignBilling(condominio, unidad, persona, UUID.randomUUID(), "admin");

        // La unidad sale una sola vez aunque haya dos responsabilidades.
        assertThat(relaciones.unitsBilledTo(condominio, persona)).containsExactly(unidad);
    }

    @Test
    @DisplayName("la misma persona no ocupa dos veces la misma unidad")
    void noOcupaDosVeces() {
        UUID condominio = condominio("Ocupacion", "3000000010");
        UUID unidad = unidad(condominio, "901");
        UUID persona = persona("6000010");
        relaciones.grantOccupancy(condominio, unidad, persona, OccupancyRole.RESIDENT, "admin");

        assertThatThrownBy(() -> relaciones.grantOccupancy(condominio, unidad, persona, OccupancyRole.TENANT, "admin"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("ya ocupa");
    }

    @Test
    @DisplayName("registrar una titularidad publica su evento con el porcentaje")
    void laTitularidadPublicaSuEvento(PublishedEvents eventos) {
        UUID condominio = condominio("Evento", "3000000011");
        UUID unidad = unidad(condominio, "1001");
        UUID persona = persona("6000011");

        relaciones.grantOwnership(condominio, unidad, persona, MITAD, "emma");

        assertThat(eventos.ofType(OwnershipGranted.class))
                .containsExactly(new OwnershipGranted(condominio, unidad, persona, MITAD, "emma", AHORA));
    }

    // --- La venta de la unidad y el cierre de relaciones: issue #19 ---

    @Test
    @DisplayName("la venta pasa la titularidad y el vendedor pierde el acceso a la unidad")
    void laVentaPasaLaTitularidad() {
        UUID condominio = condominio("Venta", "3000000012");
        UUID unidad = unidad(condominio, "1101");
        UUID vende = persona("6000012");
        UUID compra = persona("6000013");
        relaciones.grantOwnership(condominio, unidad, vende, ALL, "admin");

        OwnershipView delComprador = relaciones.transferOwnership(condominio, unidad, vende, compra, "admin");

        // El primer criterio del issue, sobre la unidad: el propietario anterior deja de verla.
        assertThat(relaciones.isRelatedTo(condominio, unidad, vende)).isFalse();
        assertThat(relaciones.isRelatedTo(condominio, unidad, compra)).isTrue();
        // El comprador recibe el porcentaje del vendedor, no uno nuevo.
        assertThat(delComprador.share()).isEqualByComparingTo(ALL);
        assertThat(delComprador.validTo()).isNull();
        assertThat(relaciones.ownersOf(condominio, unidad))
                .extracting(OwnershipView::personId)
                .containsExactly(compra);
    }

    @Test
    @DisplayName("la venta cierra tambien la ocupacion y los cobros del vendedor")
    void laVentaCierraLasTresRelacionesDelVendedor() {
        UUID condominio = condominio("Venta completa", "3000000013");
        UUID unidad = unidad(condominio, "1102");
        UUID vende = persona("6000014");
        UUID compra = persona("6000015");
        relaciones.grantOwnership(condominio, unidad, vende, ALL, "admin");
        relaciones.grantOccupancy(condominio, unidad, vende, OccupancyRole.RESIDENT, "admin");
        relaciones.assignBilling(condominio, unidad, vende, null, "admin");

        relaciones.transferOwnership(condominio, unidad, vende, compra, "admin");

        // Si quedara una sola abierta, el vendedor seguiria viendo lo que ya no es suyo: las tres
        // cuentan por igual en isRelatedTo.
        assertThat(relaciones.unitsOwnedBy(condominio, vende)).isEmpty();
        assertThat(relaciones.unitsOccupiedBy(condominio, vende)).isEmpty();
        assertThat(relaciones.unitsBilledTo(condominio, vende)).isEmpty();
        assertThat(relaciones.relatedTo(condominio, vende)).isEmpty();
    }

    @Test
    @DisplayName("la venta no toca al inquilino: la unidad cambia de dueño y el que vive sigue viviendo")
    void laVentaNoTocaAlInquilino() {
        UUID condominio = condominio("Con inquilino", "3000000014");
        UUID unidad = unidad(condominio, "1103");
        UUID vende = persona("6000016");
        UUID compra = persona("6000017");
        UUID inquilino = persona("6000018");
        relaciones.grantOwnership(condominio, unidad, vende, ALL, "admin");
        relaciones.grantOccupancy(condominio, unidad, inquilino, OccupancyRole.TENANT, "admin");

        relaciones.transferOwnership(condominio, unidad, vende, compra, "admin");

        assertThat(relaciones.unitsOccupiedBy(condominio, inquilino)).containsExactly(unidad);
    }

    @Test
    @DisplayName("el historico de titularidades conserva al vendedor con sus fechas")
    void elHistoricoConservaAlVendedor() {
        UUID condominio = condominio("Historico", "3000000015");
        UUID unidad = unidad(condominio, "1104");
        UUID vende = persona("6000019");
        UUID compra = persona("6000020");
        relaciones.grantOwnership(condominio, unidad, vende, ALL, "admin");

        relaciones.transferOwnership(condominio, unidad, vende, compra, "admin");

        // El segundo criterio del issue: lo historico se conserva, y con fechas, que es lo que hace
        // falta para dar el comprobante de su epoca que pide Seguridad 6.2.
        assertThat(relaciones.ownershipHistoryOf(condominio, unidad))
                .extracting(OwnershipView::personId)
                .containsExactlyInAnyOrder(vende, compra);
        assertThat(relaciones.ownershipHistoryOf(condominio, unidad))
                .filteredOn(titularidad -> titularidad.personId().equals(vende))
                .singleElement()
                .satisfies(delVendedor -> {
                    assertThat(delVendedor.validFrom()).isEqualTo(AHORA);
                    assertThat(delVendedor.validTo()).isEqualTo(AHORA);
                });
        // Y el listado de hoy sigue trayendo solo al dueño actual.
        assertThat(relaciones.ownersOf(condominio, unidad)).hasSize(1);
    }

    @Test
    @DisplayName("no se vende lo que no se tiene, ni a quien ya es copropietario, ni a si mismo")
    void lasVentasImposibles() {
        UUID condominio = condominio("Imposibles", "3000000016");
        UUID unidad = unidad(condominio, "1105");
        UUID dueno = persona("6000021");
        UUID copropietaria = persona("6000022");
        UUID ajena = persona("6000023");
        relaciones.grantOwnership(condominio, unidad, dueno, MITAD, "admin");
        relaciones.grantOwnership(condominio, unidad, copropietaria, MITAD, "admin");

        assertThatThrownBy(() -> relaciones.transferOwnership(condominio, unidad, ajena, dueno, "admin"))
                .isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(() -> relaciones.transferOwnership(condominio, unidad, dueno, copropietaria, "admin"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("titularidad por titularidad");
        assertThatThrownBy(() -> relaciones.transferOwnership(condominio, unidad, dueno, dueno, "admin"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("su propio dueño");
    }

    @Test
    @DisplayName("la mudanza cierra la ocupacion y no la borra")
    void laMudanzaCierraLaOcupacion() {
        UUID condominio = condominio("Mudanza", "3000000017");
        UUID unidad = unidad(condominio, "1106");
        UUID inquilino = persona("6000024");
        relaciones.grantOccupancy(condominio, unidad, inquilino, OccupancyRole.TENANT, "admin");

        relaciones.endOccupancy(condominio, unidad, inquilino, "admin");

        assertThat(relaciones.isRelatedTo(condominio, unidad, inquilino)).isFalse();
        // Y se puede volver a registrar: la fila cerrada no bloquea una nueva.
        relaciones.grantOccupancy(condominio, unidad, inquilino, OccupancyRole.RESIDENT, "admin");
        assertThat(relaciones.isRelatedTo(condominio, unidad, inquilino)).isTrue();
    }

    @Test
    @DisplayName("cerrar la responsabilidad general no cierra la de un concepto")
    void cerrarLaGeneralNoCierraLaDeUnConcepto() {
        UUID condominio = condominio("Responsabilidades", "3000000018");
        UUID unidad = unidad(condominio, "1107");
        UUID persona = persona("6000025");
        UUID agua = UUID.randomUUID();
        relaciones.assignBilling(condominio, unidad, persona, null, "admin");
        relaciones.assignBilling(condominio, unidad, persona, agua, "admin");

        relaciones.endBilling(condominio, unidad, persona, null, "admin");

        // Sigue respondiendo por el agua: son filas distintas y responden por cosas distintas.
        assertThat(relaciones.unitsBilledTo(condominio, persona)).containsExactly(unidad);

        relaciones.endBilling(condominio, unidad, persona, agua, "admin");
        assertThat(relaciones.unitsBilledTo(condominio, persona)).isEmpty();
    }

    @Test
    @DisplayName("no se cierra una relacion que no existe ni una de otro condominio")
    void losCierresImposibles() {
        UUID propio = condominio("Cierre propio", "3000000019");
        UUID ajeno = condominio("Cierre ajeno", "3000000020");
        UUID unidad = unidad(propio, "1108");
        UUID unidadAjena = unidad(ajeno, "1109");
        UUID persona = persona("6000026");

        assertThatThrownBy(() -> relaciones.endOccupancy(propio, unidad, persona, "admin"))
                .isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(() -> relaciones.endBilling(propio, unidad, persona, null, "admin"))
                .isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(() -> relaciones.endOccupancy(propio, unidadAjena, persona, "admin"))
                .isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(() -> relaciones.ownershipHistoryOf(propio, unidadAjena))
                .isInstanceOf(ResourceNotFound.class);
    }

    @Test
    @DisplayName("la venta publica un evento propio y los cierres de lo que arrastro")
    void laVentaPublicaSusEventos(PublishedEvents eventos) {
        UUID condominio = condominio("Eventos de venta", "3000000021");
        UUID unidad = unidad(condominio, "1110");
        UUID vende = persona("6000027");
        UUID compra = persona("6000028");
        relaciones.grantOwnership(condominio, unidad, vende, ALL, "admin");
        relaciones.grantOccupancy(condominio, unidad, vende, OccupancyRole.RESIDENT, "admin");

        relaciones.transferOwnership(condominio, unidad, vende, compra, "emma");

        // Dos relaciones cerradas: la titularidad y la ocupacion. Va en el evento porque es lo que
        // explica que el vendedor perdiera el acceso.
        assertThat(eventos.ofType(OwnershipTransferred.class))
                .containsExactly(new OwnershipTransferred(condominio, unidad, vende, compra, ALL, 2, "emma", AHORA));
        // Y la ocupacion cerrada publica lo mismo que si la hubiera cerrado el administrador a mano.
        assertThat(eventos.ofType(OccupancyEnded.class))
                .containsExactly(new OccupancyEnded(condominio, unidad, vende, OccupancyRole.RESIDENT, "emma", AHORA));
    }

    private UUID condominio(String nombre, String nit) {
        return condominios
                .register(new NewCondominium(nombre, nit, CondominiumType.BUILDING, "America/La_Paz", 1, 15), "admin")
                .id();
    }

    private UUID unidad(UUID condominiumId, String code) {
        return unidades.register(
                        condominiumId,
                        new NewUnit(code, UnitType.APARTMENT, new BigDecimal("1.000000"), null, null, null),
                        "admin")
                .id();
    }

    /** La persona por SQL: es de {@code identity} y este modulo no depende de el. */
    private UUID persona(String carnet) {
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
