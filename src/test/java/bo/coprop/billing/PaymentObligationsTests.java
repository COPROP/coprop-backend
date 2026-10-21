package bo.coprop.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.condominium.CondominiumType;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import bo.coprop.condominium.NewUnit;
import bo.coprop.condominium.UnitType;
import bo.coprop.condominium.Units;
import bo.coprop.identity.InvitationDoubles;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.shared.BusinessRuleViolated;
import bo.coprop.shared.ResourceNotFound;
import bo.coprop.shared.StateConflict;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * La obligacion de pago: los criterios del issue #23 vistos desde el dominio.
 *
 * <p>Arranca con {@link BootstrapMode#DIRECT_DEPENDENCIES} porque el alta pregunta por el
 * condominio, la unidad y la persona, que son de otros dos modulos.
 *
 * <p>Lo que NO se prueba aqui es la clave foranea compuesta que impide mezclar condominios: eso no
 * lo decide este modulo sino el esquema, y esta en {@code BillingSchemaTests}.
 */
@Import({TestcontainersConfiguration.class, InvitationDoubles.class})
@ApplicationModuleTest(BootstrapMode.DIRECT_DEPENDENCIES)
@Transactional
class PaymentObligationsTests {

    /**
     * Un vencimiento lejano.
     *
     * <p>Esta suite no importa el reloj de prueba --no le hace falta para nada mas-- asi que "hoy"
     * es el dia en que corre. Una fecha de este año haria que el caso de "todavia no vencio"
     * empezara a fallar al pasar ese dia, que es la clase de test que se rompe sin que nadie toque
     * el codigo.
     */
    private static final LocalDate VENCE = LocalDate.of(2099, 12, 31);

    /** Un vencimiento que ya paso, para el caso de la vencida. */
    private static final LocalDate VENCIO = LocalDate.of(2020, 1, 15);

    @Autowired
    private PaymentObligations obligations;

    @Autowired
    private ChargeConcepts concepts;

    @Autowired
    private BillingPeriods periods;

    @Autowired
    private Condominiums condominiums;

    @Autowired
    private Units units;

    @Autowired
    private People people;

    private UUID condominium;
    private UUID unit;
    private UUID person;
    private UUID concept;
    private UUID period;

    @BeforeEach
    void prepareACondominium() {
        condominium = condominiums
                .register(
                        new NewCondominium(
                                "Las Palmas " + UUID.randomUUID(),
                                nitNuevo(),
                                CondominiumType.BUILDING,
                                "America/La_Paz",
                                1,
                                15),
                        "emma")
                .id();
        unit = units.register(
                        condominium,
                        new NewUnit("A-302", UnitType.APARTMENT, new BigDecimal("1.250000"), null, null, null),
                        "emma")
                .id();
        person = people.register(new NewPerson("Ana", "Quispe", null), "emma").id();
        concept = concepts.register(
                        condominium,
                        new NewChargeConcept("EXP", "Expensas ordinarias", ChargeConceptType.DUES, PaymentGroup.DUES),
                        "emma")
                .id();
        period =
                periods.open(condominium, new NewBillingPeriod(2026, 9), "emma").id();
    }

    private static String nitNuevo() {
        return String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_0000L));
    }

    private NewPaymentObligation expensas(BigDecimal amount) {
        return new NewPaymentObligation(unit, person, concept, period, amount, VENCE, null, null, null);
    }

    private PaymentObligationView nueva() {
        return obligations.register(condominium, expensas(new BigDecimal("350.00")), "emma");
    }

    @Test
    @DisplayName("la obligacion nace en DRAFT, sin fecha de emision y con la moneda del condominio")
    void itIsBornAsADraft() {
        PaymentObligationView created = nueva();

        assertThat(created.status()).isEqualTo(PaymentObligationStatus.DRAFT);
        // Sin fecha de emision: es lo que distingue un calculo de una deuda que el propietario ve.
        assertThat(created.issuedAt()).isNull();
        // La moneda no se pide en el cuerpo, se copia del condominio (analisis 12.2).
        assertThat(created.currency()).isEqualTo("BOB");
        assertThat(created.amount()).isEqualByComparingTo("350.00");
        assertThat(created.overdue()).isFalse();
        assertThat(created.unitId()).isEqualTo(unit);
        assertThat(created.responsiblePersonId()).isEqualTo(person);
    }

    @Test
    @DisplayName("el numero legible se compone del concepto, el mes y el codigo de la unidad")
    void theNumberIsComposed() {
        // El ejemplo que escribe el analisis 5.2: EXP-2026-09-302, aqui con una unidad "A-302".
        assertThat(nueva().number()).isEqualTo("EXP-2026-09-A-302");
    }

    @Test
    @DisplayName("si ese numero ya existe, el siguiente lleva un correlativo")
    void theNumberGetsASuffixWhenItRepeats() {
        // El caso real son dos multas del mismo concepto sobre la misma unidad en el mismo mes.
        nueva();

        assertThat(nueva().number()).isEqualTo("EXP-2026-09-A-302-2");
        assertThat(nueva().number()).isEqualTo("EXP-2026-09-A-302-3");
    }

    @Test
    @DisplayName("sin periodo el mes lo pone el vencimiento: una multa no pertenece a ningun mes")
    void withoutAPeriodTheMonthComesFromTheDueDate() {
        PaymentObligationView multa = obligations.register(
                condominium,
                new NewPaymentObligation(
                        unit, person, concept, null, new BigDecimal("120.00"), VENCE, null, null, null),
                "emma");

        assertThat(multa.periodId()).isNull();
        // VENCE es 2099-12-31, asi que el mes es el 12 de ese año y no el 9 del periodo.
        assertThat(multa.number()).isEqualTo("EXP-2099-12-A-302");
    }

    @Test
    @DisplayName("la maquina avanza DRAFT, ISSUED, PAID")
    void theMachineGoesForward() {
        UUID obligation = nueva().id();

        PaymentObligationView emitida = obligations.issue(condominium, obligation, "emma");
        assertThat(emitida.status()).isEqualTo(PaymentObligationStatus.ISSUED);
        // La fecha de emision entra al emitir, no al crear.
        assertThat(emitida.issuedAt()).isNotNull();

        assertThat(obligations.pay(condominium, obligation, "emma").status()).isEqualTo(PaymentObligationStatus.PAID);
    }

    @Test
    @DisplayName("una transicion invalida lanza error de dominio y no toca la base")
    void anInvalidTransitionDoesNotTouchTheDatabase() {
        UUID obligation = nueva().id();

        // Pagar un borrador: no se puede cobrar lo que el propietario no ha visto.
        assertThatThrownBy(() -> obligations.pay(condominium, obligation, "emma"))
                .isInstanceOf(StateConflict.class)
                .hasMessageContaining("DRAFT");

        // La otra mitad del criterio: "y no toca la base".
        assertThat(obligations.ofUnit(condominium, unit))
                .extracting(PaymentObligationView::status)
                .containsExactly(PaymentObligationStatus.DRAFT);
    }

    @Test
    @DisplayName("una obligacion pagada no se anula: se revierte el pago")
    void aPaidObligationIsNotCancelled() {
        UUID obligation = nueva().id();
        obligations.issue(condominium, obligation, "emma");
        obligations.pay(condominium, obligation, "emma");

        // Es un criterio del ticket, y el mensaje dice que hacer en vez de invitar a reintentar.
        assertThatThrownBy(() -> obligations.cancel(condominium, obligation, "se cobro de mas", "emma"))
                .isInstanceOf(StateConflict.class)
                .hasMessageContaining("se revierte el pago");

        assertThat(obligations.ofUnit(condominium, unit))
                .extracting(PaymentObligationView::status)
                .containsExactly(PaymentObligationStatus.PAID);
    }

    @Test
    @DisplayName("se anula un borrador descartado y tambien una emitida que no debio emitirse")
    void bothADraftAndAnIssuedObligationCanBeCancelled() {
        UUID borrador = nueva().id();
        assertThat(obligations
                        .cancel(condominium, borrador, "la previsualizacion estaba mal", "emma")
                        .status())
                .isEqualTo(PaymentObligationStatus.CANCELLED);

        UUID emitida = nueva().id();
        obligations.issue(condominium, emitida, "emma");
        assertThat(obligations
                        .cancel(condominium, emitida, "se emitio a la unidad equivocada", "emma")
                        .status())
                .isEqualTo(PaymentObligationStatus.CANCELLED);
    }

    @Test
    @DisplayName("un convenio solo se arma sobre una emitida, y desde el convenio se paga")
    void anAgreementStartsFromAnIssuedObligation() {
        UUID borrador = nueva().id();
        assertThatThrownBy(() -> obligations.putUnderAgreement(condominium, borrador, "emma"))
                .isInstanceOf(StateConflict.class);

        obligations.issue(condominium, borrador, "emma");
        assertThat(obligations.putUnderAgreement(condominium, borrador, "emma").status())
                .isEqualTo(PaymentObligationStatus.IN_AGREEMENT);

        // Una deuda en convenio se acaba de pagar igual: el convenio es una forma de cobrarla.
        assertThat(obligations.pay(condominium, borrador, "emma").status()).isEqualTo(PaymentObligationStatus.PAID);
    }

    @Test
    @DisplayName("vencida es un hecho de la fecha, no un estado: sigue ISSUED y sigue cobrable")
    void overdueIsDerivedFromTheDate() {
        UUID vencida = obligations
                .register(condominium, conVencimiento(VENCIO), "emma")
                .id();
        obligations.issue(condominium, vencida, "emma");

        PaymentObligationView view = unica();
        assertThat(view.overdue()).isTrue();
        // Y sigue emitida: vencer no es un estado excluyente (analisis 5.3), asi que sigue cobrable
        // y el #27 podra recargarla.
        assertThat(view.status()).isEqualTo(PaymentObligationStatus.ISSUED);
    }

    @Test
    @DisplayName("la que todavia no vence no esta vencida, y un borrador vencido tampoco")
    void whatIsNotDueYetIsNotOverdue() {
        UUID obligation = nueva().id();
        obligations.issue(condominium, obligation, "emma");
        assertThat(unica().overdue()).isFalse();
    }

    @Test
    @DisplayName("un borrador con fecha pasada no esta vencido: solo vence lo que salio a cobro")
    void aDraftIsNeverOverdue() {
        obligations.register(condominium, conVencimiento(VENCIO), "emma");

        // Nadie puede deber algo que no se le ha cobrado, por vieja que sea la fecha.
        assertThat(unica().overdue()).isFalse();
        assertThat(unica().status()).isEqualTo(PaymentObligationStatus.DRAFT);
    }

    @Test
    @DisplayName("una vencida que se paga deja de estar vencida")
    void aPaidObligationIsNotOverdue() {
        UUID vencida = obligations
                .register(condominium, conVencimiento(VENCIO), "emma")
                .id();
        obligations.issue(condominium, vencida, "emma");

        assertThat(obligations.pay(condominium, vencida, "emma").overdue()).isFalse();
    }

    @Test
    @DisplayName("la unidad, el concepto, la persona y el periodo tienen que existir y ser del condominio")
    void everythingItPointsToHasToExist() {
        assertThatThrownBy(() -> obligations.register(
                        condominium,
                        new NewPaymentObligation(
                                UUID.randomUUID(),
                                person,
                                concept,
                                period,
                                new BigDecimal("1.00"),
                                VENCE,
                                null,
                                null,
                                null),
                        "emma"))
                .isInstanceOf(ResourceNotFound.class);

        assertThatThrownBy(() -> obligations.register(
                        condominium,
                        new NewPaymentObligation(
                                unit,
                                person,
                                UUID.randomUUID(),
                                period,
                                new BigDecimal("1.00"),
                                VENCE,
                                null,
                                null,
                                null),
                        "emma"))
                .isInstanceOf(ResourceNotFound.class)
                .hasMessageContaining("concepto");

        assertThatThrownBy(() -> obligations.register(
                        condominium,
                        new NewPaymentObligation(
                                unit,
                                UUID.randomUUID(),
                                concept,
                                period,
                                new BigDecimal("1.00"),
                                VENCE,
                                null,
                                null,
                                null),
                        "emma"))
                .isInstanceOf(ResourceNotFound.class);
    }

    @Test
    @DisplayName("el origen va entero o no va: un identificador sin tipo se rechaza")
    void theSourceGoesWholeOrNotAtAll() {
        assertThatThrownBy(() -> obligations.register(
                        condominium,
                        new NewPaymentObligation(
                                unit,
                                person,
                                concept,
                                period,
                                new BigDecimal("1.00"),
                                VENCE,
                                null,
                                null,
                                UUID.randomUUID()),
                        "emma"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("origen");
    }

    @Test
    @DisplayName("la mora apunta a su obligacion padre, y el padre tiene que ser del condominio")
    void theLateFeePointsToItsParent() {
        UUID expensa = nueva().id();

        PaymentObligationView mora = obligations.register(
                condominium,
                new NewPaymentObligation(
                        unit, person, concept, period, new BigDecimal("35.00"), VENCE, expensa, null, null),
                "emma");

        assertThat(mora.parentObligationId()).isEqualTo(expensa);
        assertThatThrownBy(() -> obligations.register(
                        condominium,
                        new NewPaymentObligation(
                                unit,
                                person,
                                concept,
                                period,
                                new BigDecimal("35.00"),
                                VENCE,
                                UUID.randomUUID(),
                                null,
                                null),
                        "emma"))
                .isInstanceOf(ResourceNotFound.class);
    }

    @Test
    @DisplayName("la obligacion de otro condominio no existe: 404 y no una negativa")
    void anObligationOfAnotherCondominiumDoesNotExist() {
        UUID obligation = nueva().id();
        UUID villaSol = condominiums
                .register(
                        new NewCondominium(
                                "Villa Sol " + UUID.randomUUID(),
                                nitNuevo(),
                                CondominiumType.BUILDING,
                                "America/La_Paz",
                                1,
                                15),
                        "emma")
                .id();

        assertThatThrownBy(() -> obligations.issue(villaSol, obligation, "emma"))
                .isInstanceOf(ResourceNotFound.class);
        assertThatThrownBy(() -> obligations.pay(villaSol, obligation, "emma")).isInstanceOf(ResourceNotFound.class);
    }

    @Test
    @DisplayName("cada transicion publica su evento; el alta no publica ninguno")
    void eachTransitionPublishesItsEvent(PublishedEvents eventos) {
        UUID obligation = nueva().id();

        // El alta no se audita: un borrador no es deuda de nadie. Esta escrito en el servicio.
        assertThat(eventos.ofType(PaymentObligationIssued.class)).isEmpty();

        obligations.issue(condominium, obligation, "emma");
        var emitidos = eventos.ofType(PaymentObligationIssued.class)
                .matching(evento -> evento.obligationId().equals(obligation));
        assertThat(emitidos).hasSize(1);
        PaymentObligationIssued emision = emitidos.iterator().next();
        assertThat(emision.action()).isEqualTo("PAYMENT_OBLIGATION_ISSUED");
        assertThat(emision.entity()).isEqualTo("obligacion-de-pago");
        assertThat(emision.number()).isEqualTo("EXP-2026-09-A-302");
        assertThat(emision.newValues()).containsEntry("amount", "350.00").containsEntry("currency", "BOB");

        obligations.pay(condominium, obligation, "emma");
        assertThat(eventos.ofType(PaymentObligationPaid.class)
                        .matching(evento -> evento.obligationId().equals(obligation)))
                .hasSize(1);
    }

    @Test
    @DisplayName("la anulacion guarda el motivo y el estado del que venia")
    void theCancellationKeepsTheReasonAndThePreviousStatus(PublishedEvents eventos) {
        UUID obligation = nueva().id();
        obligations.issue(condominium, obligation, "emma");
        obligations.cancel(condominium, obligation, "se emitio a la unidad equivocada", "emma");

        var anulados = eventos.ofType(PaymentObligationCancelled.class)
                .matching(evento -> evento.obligationId().equals(obligation));
        assertThat(anulados).hasSize(1);

        PaymentObligationCancelled anulacion = anulados.iterator().next();
        // El motivo vive SOLO en la bitacora, igual que en la baja de una membresia (#19).
        assertThat(anulacion.reason()).isEqualTo("se emitio a la unidad equivocada");
        assertThat(anulacion.previousStatus()).isEqualTo(PaymentObligationStatus.ISSUED);
        assertThat(anulacion.oldValues()).containsEntry("status", "ISSUED");
        assertThat(anulacion.newValues()).containsEntry("status", "CANCELLED");
    }

    /** La unica obligacion de la unidad, para los casos que solo crean una. */
    private PaymentObligationView unica() {
        var todas = obligations.ofUnit(condominium, unit);
        assertThat(todas).hasSize(1);
        return todas.get(0);
    }

    private NewPaymentObligation conVencimiento(LocalDate dueDate) {
        return new NewPaymentObligation(
                unit, person, concept, period, new BigDecimal("100.00"), dueDate, null, null, null);
    }
}
