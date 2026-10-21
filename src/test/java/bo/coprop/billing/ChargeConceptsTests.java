package bo.coprop.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.condominium.CondominiumType;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import bo.coprop.identity.InvitationDoubles;
import bo.coprop.shared.ResourceNotFound;
import bo.coprop.shared.StateConflict;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * El catalogo de conceptos de cobro: los criterios del issue #100 vistos desde el dominio.
 *
 * <p>Arranca con {@link BootstrapMode#DIRECT_DEPENDENCIES} y no con el modulo solo: el alta
 * comprueba que el condominio existe preguntandoselo a {@code condominium}, asi que sin ese modulo
 * el contexto no tendria el bean {@code Condominiums} que el servicio pide.
 *
 * <p>Lo que NO se prueba aqui es que la clave foranea compuesta impida que una responsabilidad de
 * pago apunte al concepto de otro condominio: eso no lo decide este modulo sino el esquema, y esta
 * en {@code IdentitySchemaTests} con las demas garantias de la base.
 */
@Import({TestcontainersConfiguration.class, InvitationDoubles.class})
@ApplicationModuleTest(BootstrapMode.DIRECT_DEPENDENCIES)
@Transactional
class ChargeConceptsTests {

    @Autowired
    private ChargeConcepts concepts;

    @Autowired
    private Condominiums condominiums;

    private UUID lasPalmas;

    @BeforeEach
    void registerACondominium() {
        lasPalmas = condominio("Las Palmas", "1234567890");
    }

    private UUID condominio(String name, String nit) {
        return condominiums
                .register(new NewCondominium(name, nit, CondominiumType.BUILDING, "America/La_Paz", 1, 15), "emma")
                .id();
    }

    private static NewChargeConcept dues(String code) {
        return new NewChargeConcept(code, "Expensas ordinarias", ChargeConceptType.DUES, PaymentGroup.DUES);
    }

    @Test
    @DisplayName("el alta devuelve el concepto con su tipo y su grupo de pago")
    void itIsRegisteredWithItsTypeAndPaymentGroup() {
        ChargeConceptView created = concepts.register(lasPalmas, dues("EXP"), "emma");

        assertThat(created.id()).isNotNull();
        assertThat(created.condominiumId()).isEqualTo(lasPalmas);
        assertThat(created.code()).isEqualTo("EXP");
        assertThat(created.name()).isEqualTo("Expensas ordinarias");
        assertThat(created.type()).isEqualTo(ChargeConceptType.DUES);
        assertThat(created.paymentGroup()).isEqualTo(PaymentGroup.DUES);
        assertThat(concepts.list(lasPalmas)).containsExactly(created);
    }

    @Test
    @DisplayName("el codigo es unico dentro del condominio")
    void theCodeIsUniqueWithinTheCondominium() {
        concepts.register(lasPalmas, dues("EXP"), "emma");

        // StateConflict y no BusinessRuleViolated: el ticket pide 409, que es lo que responde el
        // primero. Dar de alta algo que ya existe choca con el estado, no con una regla del negocio.
        assertThatThrownBy(() -> concepts.register(lasPalmas, dues("EXP"), "emma"))
                .isInstanceOf(StateConflict.class)
                .hasMessageContaining("EXP");
    }

    @Test
    @DisplayName("pero el mismo codigo si puede repetirse en otro condominio")
    void theSameCodeIsValidInAnotherCondominium() {
        concepts.register(lasPalmas, dues("EXP"), "emma");
        UUID villaSol = condominio("Villa Sol", "9999999999");

        ChargeConceptView otro = concepts.register(villaSol, dues("EXP"), "emma");

        assertThat(otro.code()).isEqualTo("EXP");
        assertThat(otro.condominiumId()).isEqualTo(villaSol);
    }

    @Test
    @DisplayName("el catalogo trae solo los conceptos del condominio, ordenados por codigo")
    void theCatalogIsScopedToTheCondominium() {
        concepts.register(lasPalmas, dues("EXP"), "emma");
        concepts.register(lasPalmas, agua("AGUA"), "emma");
        UUID villaSol = condominio("Villa Sol", "9999999999");
        concepts.register(villaSol, dues("EXP-VS"), "emma");

        assertThat(concepts.list(lasPalmas)).extracting(ChargeConceptView::code).containsExactly("AGUA", "EXP");
        assertThat(concepts.list(villaSol)).extracting(ChargeConceptView::code).containsExactly("EXP-VS");
    }

    @Test
    @DisplayName("un concepto no puede existir sin condominio, y el catalogo de uno que no existe tampoco")
    void itCannotExistWithoutACondominium() {
        assertThatThrownBy(() -> concepts.register(UUID.randomUUID(), dues("EXP"), "emma"))
                .isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(() -> concepts.list(UUID.randomUUID())).isInstanceOf(ResourceNotFound.class);
    }

    @Test
    @DisplayName("la mora de expensas y la del agua son dos conceptos del mismo tipo y distinto grupo")
    void theLateFeeKeepsTheGroupOfWhatItCharges() {
        // Es el caso que obliga a que el grupo de pago sea un dato y no se derive del tipo: el
        // analisis exige que la mora del agua se pague con el agua, asi que un condominio tiene un
        // LATE_FEE por grupo en el que cobre mora. Si el grupo se dedujera del tipo, estas dos
        // filas serian imposibles.
        concepts.register(lasPalmas, mora("MORA-EXP", "Mora de expensas", PaymentGroup.DUES), "emma");
        concepts.register(lasPalmas, mora("MORA-AGUA", "Mora del agua", PaymentGroup.WATER), "emma");

        assertThat(concepts.list(lasPalmas))
                .extracting(ChargeConceptView::type, ChargeConceptView::paymentGroup)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ChargeConceptType.LATE_FEE, PaymentGroup.WATER),
                        org.assertj.core.groups.Tuple.tuple(ChargeConceptType.LATE_FEE, PaymentGroup.DUES));
    }

    @Test
    @DisplayName("el alta publica el evento auditable con los cuatro datos del concepto")
    void theRegistrationPublishesTheAuditableEvent(PublishedEvents eventos) {
        ChargeConceptView created = concepts.register(lasPalmas, dues("EXP"), "emma");

        var publicados = eventos.ofType(ChargeConceptRegistered.class)
                .matching(evento -> evento.conceptId().equals(created.id()));
        assertThat(publicados).hasSize(1);

        ChargeConceptRegistered evento = publicados.iterator().next();
        assertThat(evento.action()).isEqualTo("CHARGE_CONCEPT_REGISTERED");
        assertThat(evento.entity()).isEqualTo("concepto-de-cobro");
        assertThat(evento.entityId()).isEqualTo(created.id());
        assertThat(evento.condominiumId()).isEqualTo(lasPalmas);
        assertThat(evento.actor()).isEqualTo("emma");
        // Antes no habia nada: es un alta, y por eso oldValues se queda nulo y no en un mapa vacio.
        assertThat(evento.oldValues()).isNull();
        assertThat(evento.newValues())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "code", "EXP",
                        "name", "Expensas ordinarias",
                        "type", "DUES",
                        "paymentGroup", "DUES"));
    }

    @Test
    @DisplayName("dos conceptos distintos del mismo grupo conviven: es lo que permite un solo QR")
    void twoConceptsOfTheSameGroupCoexist() {
        concepts.register(lasPalmas, dues("EXP"), "emma");

        assertThatCode(() -> concepts.register(lasPalmas, extraordinario(), "emma"))
                .doesNotThrowAnyException();

        assertThat(concepts.list(lasPalmas)).hasSize(2);
    }

    private static NewChargeConcept agua(String code) {
        return new NewChargeConcept(code, "Agua", ChargeConceptType.WATER, PaymentGroup.WATER);
    }

    private static NewChargeConcept mora(String code, String name, PaymentGroup group) {
        return new NewChargeConcept(code, name, ChargeConceptType.LATE_FEE, group);
    }

    private static NewChargeConcept extraordinario() {
        return new NewChargeConcept("EXTRA", "Aporte extraordinario", ChargeConceptType.OTHER, PaymentGroup.DUES);
    }
}
