package bo.coprop.condominium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.shared.RecursoNoEncontrado;
import bo.coprop.shared.ReglaDeNegocioViolada;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.transaction.annotation.Transactional;

/** Los dos criterios de aceptacion del issue #10, y la jerarquia opcional. */
@Import(TestcontainersConfiguration.class)
@ApplicationModuleTest
@Transactional
class UnitsTests {

    @Autowired
    private Units unidades;

    @Autowired
    private Condominiums condominios;

    private UUID lasPalmas;

    @BeforeEach
    void darDeAltaUnCondominio() {
        lasPalmas = condominios
                .register(
                        new NewCondominium(
                                "Las Palmas", "1234567890", CondominiumType.EDIFICIO, "America/La_Paz", 1, 15),
                        "emma")
                .id();
    }

    private static NewUnit unidad(String code) {
        return new NewUnit(
                code, UnitType.DEPARTAMENTO, new BigDecimal("1.250000"), new BigDecimal("82.50"), null, null);
    }

    @Test
    @DisplayName("una unidad se da de alta activa y conserva su alicuota sin redondear")
    void seDaDeAltaActiva() {
        UnitView creada = unidades.register(lasPalmas, unidad("302"));

        assertThat(creada.id()).isNotNull();
        assertThat(creada.condominiumId()).isEqualTo(lasPalmas);
        assertThat(creada.code()).isEqualTo("302");
        assertThat(creada.status()).isEqualTo(UnitStatus.ACTIVA);
        assertThat(creada.aliquot()).isEqualByComparingTo("1.250000");
        assertThat(unidades.find(creada.id())).isEqualTo(creada);
    }

    @Test
    @DisplayName("el codigo es unico dentro del condominio")
    void elCodigoEsUnicoEnElCondominio() {
        unidades.register(lasPalmas, unidad("302"));

        assertThatThrownBy(() -> unidades.register(lasPalmas, unidad("302")))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("302");
    }

    @Test
    @DisplayName("pero el mismo codigo si puede repetirse en otro condominio")
    void elMismoCodigoValeEnOtroCondominio() {
        unidades.register(lasPalmas, unidad("302"));
        UUID villaSol = condominios
                .register(
                        new NewCondominium("Villa Sol", "9999999999", CondominiumType.CASAS, "America/La_Paz", 1, 15),
                        "emma")
                .id();

        UnitView otra = unidades.register(villaSol, unidad("302"));

        assertThat(otra.code()).isEqualTo("302");
        assertThat(otra.condominiumId()).isEqualTo(villaSol);
    }

    @Test
    @DisplayName("una unidad no puede existir sin condominio")
    void noPuedeExistirSinCondominio() {
        assertThatThrownBy(() -> unidades.register(UUID.randomUUID(), unidad("302")))
                .isInstanceOf(RecursoNoEncontrado.class);
    }

    @Test
    @DisplayName("el alta masiva crea todas de una vez")
    void elAltaMasivaCreaTodas() {
        List<UnitView> creadas = unidades.registerAll(lasPalmas, List.of(unidad("301"), unidad("302"), unidad("303")));

        assertThat(creadas).hasSize(3).extracting(UnitView::code).containsExactly("301", "302", "303");
    }

    @Test
    @DisplayName("el alta masiva es todo o nada: si una falla no entra ninguna")
    void elAltaMasivaEsTodoONada() {
        unidades.register(lasPalmas, unidad("301"));

        assertThatThrownBy(() -> unidades.registerAll(lasPalmas, List.of(unidad("302"), unidad("301"))))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("301");

        // La 302 del lote fallido no quedo creada: si hubiera entrado, esto chocaria con su codigo.
        assertThatCode(() -> unidades.registerAll(lasPalmas, List.of(unidad("302"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("el lote avisa de sus propios codigos repetidos, diciendo cual")
    void elLoteNoPuedeRepetirCodigos() {
        assertThatThrownBy(() -> unidades.registerAll(lasPalmas, List.of(unidad("301"), unidad("301"))))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("301");
    }

    @Test
    @DisplayName("la jerarquia es opcional: una unidad puede no tener bloque ni piso")
    void laJerarquiaEsOpcional() {
        UnitView suelta = unidades.register(lasPalmas, unidad("Casa 7"));

        assertThat(suelta.blockId()).isNull();
        assertThat(suelta.floorId()).isNull();
    }

    @Test
    @DisplayName("una unidad puede colgar de un bloque y un piso de ese bloque")
    void laUnidadCuelgaDeSuBloqueYPiso() {
        BlockView torre = unidades.addBlock(lasPalmas, "Torre A");
        FloorView tercero = unidades.addFloor(torre.id(), 3);

        UnitView creada = unidades.register(
                lasPalmas,
                new NewUnit(
                        "A-302", UnitType.DEPARTAMENTO, new BigDecimal("1.250000"), null, torre.id(), tercero.id()));

        assertThat(creada.blockId()).isEqualTo(torre.id());
        assertThat(creada.floorId()).isEqualTo(tercero.id());
    }

    @Test
    @DisplayName("una unidad no puede colgar de un bloque de otro condominio")
    void noPuedeColgarDeUnBloqueAjeno() {
        UUID villaSol = condominios
                .register(
                        new NewCondominium("Villa Sol", "9999999999", CondominiumType.CASAS, "America/La_Paz", 1, 15),
                        "emma")
                .id();
        BlockView torreAjena = unidades.addBlock(villaSol, "Torre A");

        assertThatThrownBy(() -> unidades.register(
                        lasPalmas,
                        new NewUnit("302", UnitType.DEPARTAMENTO, new BigDecimal("1.0"), null, torreAjena.id(), null)))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("no es de este condominio");
    }

    @Test
    @DisplayName("indicar piso sin bloque se rechaza: un piso siempre cuelga de un bloque")
    void elPisoNecesitaBloque() {
        BlockView torre = unidades.addBlock(lasPalmas, "Torre A");
        FloorView tercero = unidades.addFloor(torre.id(), 3);

        assertThatThrownBy(() -> unidades.register(
                        lasPalmas,
                        new NewUnit("302", UnitType.DEPARTAMENTO, new BigDecimal("1.0"), null, null, tercero.id())))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("bloque");
    }

    @Test
    @DisplayName("un bloque no puede repetir nombre dentro del condominio")
    void elNombreDeBloqueEsUnico() {
        unidades.addBlock(lasPalmas, "Torre A");

        assertThatThrownBy(() -> unidades.addBlock(lasPalmas, "Torre A")).isInstanceOf(ReglaDeNegocioViolada.class);
    }
}
