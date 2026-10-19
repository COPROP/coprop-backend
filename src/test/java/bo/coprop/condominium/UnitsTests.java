package bo.coprop.condominium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.shared.BusinessRuleViolated;
import bo.coprop.shared.ResourceNotFound;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.transaction.annotation.Transactional;

/** Los dos criterios de aceptacion del issue #10, la jerarquia opcional y el aislamiento. */
@Import(TestcontainersConfiguration.class)
@ApplicationModuleTest
@Transactional
class UnitsTests {

    @Autowired
    private Units units;

    @Autowired
    private Condominiums condominiums;

    private UUID lasPalmas;

    @BeforeEach
    void registerACondominium() {
        lasPalmas = condominiums
                .register(
                        new NewCondominium(
                                "Las Palmas", "1234567890", CondominiumType.BUILDING, "America/La_Paz", 1, 15),
                        "emma")
                .id();
    }

    private UUID villaSol() {
        return condominiums
                .register(
                        new NewCondominium("Villa Sol", "9999999999", CondominiumType.HOUSES, "America/La_Paz", 1, 15),
                        "emma")
                .id();
    }

    private static NewUnit unit(String code) {
        return new NewUnit(code, UnitType.APARTMENT, new BigDecimal("1.250000"), new BigDecimal("82.50"), null, null);
    }

    @Test
    @DisplayName("una unidad se da de alta activa y conserva su alicuota sin redondear")
    void itIsRegisteredActive() {
        UnitView created = units.register(lasPalmas, unit("302"), "emma");

        assertThat(created.id()).isNotNull();
        assertThat(created.condominiumId()).isEqualTo(lasPalmas);
        assertThat(created.code()).isEqualTo("302");
        assertThat(created.status()).isEqualTo(UnitStatus.ACTIVE);
        assertThat(created.aliquot()).isEqualByComparingTo("1.250000");
        assertThat(units.find(lasPalmas, created.id())).isEqualTo(created);
    }

    @Test
    @DisplayName("el codigo es unico dentro del condominio")
    void theCodeIsUniqueWithinTheCondominium() {
        units.register(lasPalmas, unit("302"), "emma");

        assertThatThrownBy(() -> units.register(lasPalmas, unit("302"), "emma"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("302");
    }

    @Test
    @DisplayName("pero el mismo codigo si puede repetirse en otro condominio")
    void theSameCodeIsValidInAnotherCondominium() {
        units.register(lasPalmas, unit("302"), "emma");
        UUID otroCondominio = villaSol();

        UnitView otra = units.register(otroCondominio, unit("302"), "emma");

        assertThat(otra.code()).isEqualTo("302");
        assertThat(otra.condominiumId()).isEqualTo(otroCondominio);
    }

    @Test
    @DisplayName("una unidad no puede existir sin condominio")
    void itCannotExistWithoutACondominium() {
        assertThatThrownBy(() -> units.register(UUID.randomUUID(), unit("302"), "emma"))
                .isInstanceOf(ResourceNotFound.class);
    }

    @Test
    @DisplayName("una unidad de otro condominio no se encuentra, aunque se sepa su identificador")
    void aUnitFromAnotherCondominiumIsNotFound() {
        UnitView ajena = units.register(villaSol(), unit("302"), "emma");

        // 404 y no 403: distinguirlos diria que esa unidad existe en algun sitio (Seguridad 7.1).
        assertThatThrownBy(() -> units.find(lasPalmas, ajena.id())).isInstanceOf(ResourceNotFound.class);
    }

    @Test
    @DisplayName("tampoco se le puede colgar un piso a una torre de otro condominio")
    void aFloorCannotBeAddedToAnotherCondominiumsTower() {
        BlockView torreAjena = units.addBlock(villaSol(), "Torre A", "emma");

        assertThatThrownBy(() -> units.addFloor(lasPalmas, torreAjena.id(), 3, "emma"))
                .isInstanceOf(ResourceNotFound.class);
    }

    @Test
    @DisplayName("el alta masiva crea todas de una vez")
    void theBatchRegistrationCreatesThemAll() {
        List<UnitView> created = units.registerAll(lasPalmas, List.of(unit("301"), unit("302"), unit("303")), "emma");

        assertThat(created).hasSize(3).extracting(UnitView::code).containsExactly("301", "302", "303");
    }

    @Test
    @DisplayName("el alta masiva es todo o nada: si una falla no entra ninguna")
    void theBatchRegistrationIsAllOrNothing() {
        units.register(lasPalmas, unit("301"), "emma");

        assertThatThrownBy(() -> units.registerAll(lasPalmas, List.of(unit("302"), unit("301")), "emma"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("301");

        // La 302 del lote fallido no quedo creada: si hubiera entrado, esto chocaria con su codigo.
        assertThatCode(() -> units.registerAll(lasPalmas, List.of(unit("302")), "emma"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("el lote avisa de sus propios codigos repetidos, diciendo cual")
    void theBatchCannotRepeatCodes() {
        assertThatThrownBy(() -> units.registerAll(lasPalmas, List.of(unit("301"), unit("301")), "emma"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("301");
    }

    @Test
    @DisplayName("el alta masiva publica un solo evento, con el actor y las unidades creadas")
    void theBatchRegistrationIsAudited(PublishedEvents eventos) {
        List<UnitView> created = units.registerAll(lasPalmas, List.of(unit("301"), unit("302")), "emma");

        assertThat(eventos.ofType(UnitsRegistered.class)).singleElement().satisfies(evento -> {
            assertThat(evento.condominiumId()).isEqualTo(lasPalmas);
            assertThat(evento.actor()).isEqualTo("emma");
            assertThat(evento.unitIds())
                    .containsExactlyElementsOf(
                            created.stream().map(UnitView::id).toList());
        });
    }

    @Test
    @DisplayName("la jerarquia es opcional: una unidad puede no tener bloque ni piso")
    void theHierarchyIsOptional() {
        UnitView suelta = units.register(lasPalmas, unit("Casa 7"), "emma");

        assertThat(suelta.blockId()).isNull();
        assertThat(suelta.floorId()).isNull();
    }

    @Test
    @DisplayName("una unidad puede colgar de un bloque y un piso de ese bloque")
    void theUnitHangsFromItsBlockAndFloor() {
        BlockView torre = units.addBlock(lasPalmas, "Torre A", "emma");
        FloorView third = units.addFloor(lasPalmas, torre.id(), 3, "emma");

        UnitView created = units.register(
                lasPalmas,
                new NewUnit("A-302", UnitType.APARTMENT, new BigDecimal("1.250000"), null, torre.id(), third.id()),
                "emma");

        assertThat(created.blockId()).isEqualTo(torre.id());
        assertThat(created.floorId()).isEqualTo(third.id());
    }

    @Test
    @DisplayName("una unidad no puede colgar de un bloque de otro condominio")
    void itCannotHangFromAnotherCondominiumsBlock() {
        BlockView torreAjena = units.addBlock(villaSol(), "Torre A", "emma");

        assertThatThrownBy(() -> units.register(
                        lasPalmas,
                        new NewUnit("302", UnitType.APARTMENT, new BigDecimal("1.0"), null, torreAjena.id(), null),
                        "emma"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("no es de este condominio");
    }

    @Test
    @DisplayName("indicar piso sin bloque se rechaza: un piso siempre cuelga de un bloque")
    void theFloorNeedsABlock() {
        BlockView torre = units.addBlock(lasPalmas, "Torre A", "emma");
        FloorView third = units.addFloor(lasPalmas, torre.id(), 3, "emma");

        assertThatThrownBy(() -> units.register(
                        lasPalmas,
                        new NewUnit("302", UnitType.APARTMENT, new BigDecimal("1.0"), null, null, third.id()),
                        "emma"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("bloque");
    }

    @Test
    @DisplayName("un bloque no puede repetir nombre dentro del condominio")
    void theBlockNameIsUnique() {
        units.addBlock(lasPalmas, "Torre A", "emma");

        assertThatThrownBy(() -> units.addBlock(lasPalmas, "Torre A", "emma")).isInstanceOf(BusinessRuleViolated.class);
    }
}
