package bo.coprop.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.condominium.CondominiumType;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
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
 * El periodo de facturacion: los criterios del issue #101 vistos desde el dominio.
 *
 * <p>Arranca con {@link BootstrapMode#DIRECT_DEPENDENCIES} por lo mismo que el catalogo de
 * conceptos: la apertura comprueba que el condominio existe preguntandoselo a {@code condominium},
 * asi que sin ese modulo el contexto no tendria el bean {@code Condominiums}.
 *
 * <p><strong>Un criterio del ticket no esta aqui, y no por descuido</strong>: que un periodo con
 * obligaciones en {@code DRAFT} no se pueda cerrar necesita {@code PaymentObligation}, que nace con
 * el #23. Su caso entra con ese issue.
 */
@Import(TestcontainersConfiguration.class)
@ApplicationModuleTest(BootstrapMode.DIRECT_DEPENDENCIES)
@Transactional
class BillingPeriodsTests {

    @Autowired
    private BillingPeriods periods;

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

    @Test
    @DisplayName("el periodo nace en DRAFT, con su año y su mes")
    void itIsBornInDraft() {
        BillingPeriodView opened = periods.open(lasPalmas, new NewBillingPeriod(2026, 10), "emma");

        assertThat(opened.id()).isNotNull();
        assertThat(opened.condominiumId()).isEqualTo(lasPalmas);
        assertThat(opened.year()).isEqualTo(2026);
        assertThat(opened.month()).isEqualTo(10);
        // El estado no se elige al abrir: es la decision del 19-oct-2026, y lo que hace que DRAFT no
        // sea un valor que nadie escribe.
        assertThat(opened.status()).isEqualTo(BillingPeriodStatus.DRAFT);
        assertThat(periods.list(lasPalmas)).containsExactly(opened);
    }

    @Test
    @DisplayName("un solo periodo por condominio, año y mes")
    void thereIsOnlyOnePeriodPerMonth() {
        periods.open(lasPalmas, new NewBillingPeriod(2026, 10), "emma");

        assertThatThrownBy(() -> periods.open(lasPalmas, new NewBillingPeriod(2026, 10), "emma"))
                .isInstanceOf(StateConflict.class)
                .hasMessageContaining("2026-10");

        // El mismo mes del año anterior si se admite: lo unico es el par completo.
        assertThatCode(() -> periods.open(lasPalmas, new NewBillingPeriod(2025, 10), "emma"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("pero el mismo mes si puede abrirse en otro condominio")
    void theSameMonthIsValidInAnotherCondominium() {
        periods.open(lasPalmas, new NewBillingPeriod(2026, 10), "emma");
        UUID villaSol = condominio("Villa Sol", "9999999999");

        BillingPeriodView otro = periods.open(villaSol, new NewBillingPeriod(2026, 10), "emma");

        assertThat(otro.month()).isEqualTo(10);
        assertThat(otro.condominiumId()).isEqualTo(villaSol);
    }

    @Test
    @DisplayName("el listado trae solo los periodos del condominio, del mas reciente al mas antiguo")
    void theListingIsScopedToTheCondominiumAndOrdered() {
        periods.open(lasPalmas, new NewBillingPeriod(2026, 9), "emma");
        periods.open(lasPalmas, new NewBillingPeriod(2026, 10), "emma");
        periods.open(lasPalmas, new NewBillingPeriod(2025, 12), "emma");
        UUID villaSol = condominio("Villa Sol", "9999999999");
        periods.open(villaSol, new NewBillingPeriod(2026, 10), "emma");

        assertThat(periods.list(lasPalmas))
                .extracting(BillingPeriodView::year, BillingPeriodView::month)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2026, 10),
                        org.assertj.core.groups.Tuple.tuple(2026, 9),
                        org.assertj.core.groups.Tuple.tuple(2025, 12));
        assertThat(periods.list(villaSol)).hasSize(1);
    }

    @Test
    @DisplayName("un periodo no puede existir sin condominio, y el listado de uno que no existe tampoco")
    void itCannotExistWithoutACondominium() {
        assertThatThrownBy(() -> periods.open(UUID.randomUUID(), new NewBillingPeriod(2026, 10), "emma"))
                .isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(() -> periods.list(UUID.randomUUID())).isInstanceOf(ResourceNotFound.class);
    }

    @Test
    @DisplayName("la maquina avanza DRAFT, ISSUED, CLOSED")
    void theMachineGoesForward() {
        UUID period =
                periods.open(lasPalmas, new NewBillingPeriod(2026, 10), "emma").id();

        assertThat(periods.issue(lasPalmas, period, "emma").status()).isEqualTo(BillingPeriodStatus.ISSUED);
        assertThat(periods.close(lasPalmas, period, "emma").status()).isEqualTo(BillingPeriodStatus.CLOSED);
    }

    @Test
    @DisplayName("de CLOSED no vuelve a ISSUED, y la base se queda como estaba")
    void aClosedPeriodDoesNotGoBack() {
        UUID period =
                periods.open(lasPalmas, new NewBillingPeriod(2026, 10), "emma").id();
        periods.issue(lasPalmas, period, "emma");
        periods.close(lasPalmas, period, "emma");

        assertThatThrownBy(() -> periods.issue(lasPalmas, period, "emma"))
                .isInstanceOf(StateConflict.class)
                .hasMessageContaining("CLOSED");

        // La segunda mitad del criterio: "y no toca la base". La transicion la rechaza la entidad
        // antes de cambiar nada, asi que el periodo sigue cerrado.
        assertThat(periods.list(lasPalmas))
                .extracting(BillingPeriodView::status)
                .containsExactly(BillingPeriodStatus.CLOSED);
    }

    @Test
    @DisplayName("emitir dos veces es un conflicto, no una operacion que no hace nada")
    void issuingTwiceIsAConflict() {
        UUID period =
                periods.open(lasPalmas, new NewBillingPeriod(2026, 10), "emma").id();
        periods.issue(lasPalmas, period, "emma");

        // Lo que el #28 necesita idempotente es abrir el periodo, no emitirlo: un segundo emitir
        // viene de un worker que ya corrio, y callarselo dejaria creer que acaba de emitir unas
        // obligaciones que ya estaban.
        assertThatThrownBy(() -> periods.issue(lasPalmas, period, "emma"))
                .isInstanceOf(StateConflict.class)
                .hasMessageContaining("ISSUED");
    }

    @Test
    @DisplayName("un periodo en DRAFT no se cierra: un mes que nunca emitio no es un cierre contable")
    void aDraftPeriodIsNotClosed() {
        UUID period =
                periods.open(lasPalmas, new NewBillingPeriod(2026, 10), "emma").id();

        assertThatThrownBy(() -> periods.close(lasPalmas, period, "emma"))
                .isInstanceOf(StateConflict.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    @DisplayName("el periodo de otro condominio no existe: 404 y no una negativa")
    void aPeriodOfAnotherCondominiumDoesNotExist() {
        UUID period =
                periods.open(lasPalmas, new NewBillingPeriod(2026, 10), "emma").id();
        UUID villaSol = condominio("Villa Sol", "9999999999");

        assertThatThrownBy(() -> periods.issue(villaSol, period, "emma")).isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(() -> periods.close(villaSol, period, "emma")).isInstanceOf(ResourceNotFound.class);
    }

    @Test
    @DisplayName("la apertura publica el evento auditable con el mes y el estado de nacimiento")
    void theOpeningPublishesTheAuditableEvent(PublishedEvents eventos) {
        BillingPeriodView opened = periods.open(lasPalmas, new NewBillingPeriod(2026, 10), "emma");

        var publicados = eventos.ofType(BillingPeriodOpened.class)
                .matching(evento -> evento.periodId().equals(opened.id()));
        assertThat(publicados).hasSize(1);

        BillingPeriodOpened evento = publicados.iterator().next();
        assertThat(evento.action()).isEqualTo("BILLING_PERIOD_OPENED");
        assertThat(evento.entity()).isEqualTo("periodo-de-facturacion");
        assertThat(evento.entityId()).isEqualTo(opened.id());
        assertThat(evento.condominiumId()).isEqualTo(lasPalmas);
        assertThat(evento.actor()).isEqualTo("emma");
        assertThat(evento.oldValues()).isNull();
        assertThat(evento.newValues())
                .containsExactlyInAnyOrderEntriesOf(Map.of("year", "2026", "month", "10", "status", "DRAFT"));
    }

    @Test
    @DisplayName("cada transicion publica su evento, y el nombre de la accion sale del estado nuevo")
    void eachTransitionPublishesItsEvent(PublishedEvents eventos) {
        UUID period =
                periods.open(lasPalmas, new NewBillingPeriod(2026, 10), "emma").id();
        periods.issue(lasPalmas, period, "emma");
        periods.close(lasPalmas, period, "contadora");

        var publicados = eventos.ofType(BillingPeriodStatusChanged.class)
                .matching(evento -> evento.periodId().equals(period));
        assertThat(publicados).hasSize(2);

        assertThat(publicados)
                .extracting(BillingPeriodStatusChanged::action)
                .containsExactly("BILLING_PERIOD_ISSUED", "BILLING_PERIOD_CLOSED");

        // Por el iterador y no por un stream: TypedPublishedEvents es un Iterable y el orden ya
        // lo fija la asercion de arriba.
        var iterador = publicados.iterator();
        iterador.next();
        BillingPeriodStatusChanged cierre = iterador.next();
        assertThat(cierre.actor()).isEqualTo("contadora");
        assertThat(cierre.oldValues()).containsEntry("status", "ISSUED");
        assertThat(cierre.newValues()).containsEntry("status", "CLOSED").containsEntry("month", "10");
    }
}
