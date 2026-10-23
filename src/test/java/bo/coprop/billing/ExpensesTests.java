package bo.coprop.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.condominium.CondominiumType;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import bo.coprop.condominium.NewUnit;
import bo.coprop.condominium.UnitRelations;
import bo.coprop.condominium.UnitType;
import bo.coprop.condominium.Units;
import bo.coprop.identity.InvitationDoubles;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.shared.BusinessRuleViolated;
import bo.coprop.shared.ResourceNotFound;
import bo.coprop.shared.StateConflict;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las expensas: los criterios del issue #24 vistos desde el dominio.
 *
 * <p>Arranca con {@link BootstrapMode#DIRECT_DEPENDENCIES} porque la emision pregunta por el
 * condominio, por sus unidades activas y por quien responde de sus cobros, que son de
 * {@code condominium}, y porque el alta de una persona es de {@code identity}. Importa
 * {@code InvitationDoubles} por lo mismo que la suite de la obligacion de pago: arrastra los dobles
 * de acceso y de correo que ese arranque necesita.
 *
 * <p>Lo que NO se prueba aqui es el esquema --el indice de "una sola configuracion vigente" y las
 * restricciones de la V14--: eso no lo decide este modulo, y esta en {@code IdentitySchemaTests}.
 */
@Import({TestcontainersConfiguration.class, InvitationDoubles.class})
@ApplicationModuleTest(BootstrapMode.DIRECT_DEPENDENCIES)
@Transactional
class ExpensesTests {

    /** El dia de vencimiento del condominio de prueba, para poder comprobar el mes siguiente. */
    private static final int DIA_DE_VENCIMIENTO = 15;

    @Autowired
    private Expenses expenses;

    @Autowired
    private ChargeConcepts concepts;

    @Autowired
    private BillingPeriods periods;

    @Autowired
    private PaymentObligations obligations;

    @Autowired
    private Condominiums condominiums;

    @Autowired
    private Units units;

    @Autowired
    private UnitRelations relations;

    @Autowired
    private People people;

    @Autowired
    private DataSource dataSource;

    /**
     * Solo para forzar el volcado antes de tocar la base a mano.
     *
     * <p>El unico caso que lo necesita es el de la unidad inactiva: sin volcar, el {@code UPDATE} de
     * JDBC corre antes del {@code INSERT} que JPA tiene pendiente y no encuentra la fila.
     */
    @Autowired
    private EntityManager entityManager;

    private UUID condominium;
    private UUID concept;
    private UUID person;

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
                                DIA_DE_VENCIMIENTO),
                        "emma")
                .id();
        concept = concepts.register(
                        condominium,
                        new NewChargeConcept("EXP", "Expensas ordinarias", ChargeConceptType.DUES, PaymentGroup.DUES),
                        "emma")
                .id();
        person = people.register(new NewPerson("Ana", "Quispe", null), "emma").id();
    }

    private static String nitNuevo() {
        return String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_0000L));
    }

    /** Una unidad activa con su alicuota y con un responsable de pago general. */
    private UUID unidad(String code, String aliquot) {
        UUID unit = units.register(
                        condominium,
                        new NewUnit(code, UnitType.APARTMENT, new BigDecimal(aliquot), null, null, null),
                        "emma")
                .id();
        relations.assignBilling(condominium, unit, person, null, "emma");
        return unit;
    }

    /** Una unidad sin responsable de pago, para los casos que tienen que fallar. */
    private UUID unidadHuerfana(String code, String aliquot) {
        return units.register(
                        condominium,
                        new NewUnit(code, UnitType.APARTMENT, new BigDecimal(aliquot), null, null, null),
                        "emma")
                .id();
    }

    private ExpenseSettingView configurar(ExpenseMethod method, String amount) {
        return expenses.configure(condominium, new NewExpenseSetting(concept, method, new BigDecimal(amount)), "emma");
    }

    // --- La configuracion y su vigencia -----------------------------------------------------

    @Test
    @DisplayName("la configuracion nace vigente, con la moneda del condominio")
    void theSettingIsBornCurrent() {
        ExpenseSettingView created = configurar(ExpenseMethod.BY_ALIQUOT, "14700.00");

        assertThat(created.validTo()).isNull();
        assertThat(created.method()).isEqualTo(ExpenseMethod.BY_ALIQUOT);
        assertThat(created.amount()).isEqualByComparingTo("14700.00");
        // La moneda no se pide en el cuerpo, se copia del condominio (analisis 12.2).
        assertThat(created.currency()).isEqualTo("BOB");
        assertThat(created.conceptId()).isEqualTo(concept);
    }

    @Test
    @DisplayName("configurar otra vez cierra la anterior: la vigente es una y el historial las dos")
    void configuringAgainClosesThePreviousOne() {
        ExpenseSettingView primera = configurar(ExpenseMethod.FIXED_PER_UNIT, "300.00");
        ExpenseSettingView segunda = configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");

        // El tercer criterio del ticket: la vigente se consulta, y el historial de vigencias tambien.
        assertThat(expenses.current(condominium))
                .extracting(ExpenseSettingView::id)
                .containsExactly(segunda.id());

        List<ExpenseSettingView> historial = expenses.history(condominium);
        assertThat(historial).extracting(ExpenseSettingView::id).containsExactly(segunda.id(), primera.id());
        // La anterior queda cerrada y con su monto intacto: es lo que permite decir con que se emitio.
        assertThat(historial.get(1).validTo()).isNotNull();
        assertThat(historial.get(1).amount()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("la configuracion de expensas es solo para conceptos de tipo DUES")
    void onlyDuesConceptsCanBeConfigured() {
        UUID agua = concepts.register(
                        condominium,
                        new NewChargeConcept("AGUA", "Agua", ChargeConceptType.WATER, PaymentGroup.WATER),
                        "emma")
                .id();

        assertThatThrownBy(() -> expenses.configure(
                        condominium,
                        new NewExpenseSetting(agua, ExpenseMethod.FIXED_PER_UNIT, new BigDecimal("50.00")),
                        "emma"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("WATER")
                .hasMessageContaining("DUES");
    }

    @Test
    @DisplayName("un concepto de otro condominio no existe")
    void aConceptOfAnotherCondominiumDoesNotExist() {
        assertThatThrownBy(() -> expenses.configure(
                        condominium,
                        new NewExpenseSetting(UUID.randomUUID(), ExpenseMethod.FIXED_PER_UNIT, new BigDecimal("50.00")),
                        "emma"))
                .isInstanceOf(ResourceNotFound.class);
    }

    // --- El calculo ---------------------------------------------------------------------------

    @Test
    @DisplayName("a monto fijo, cada unidad activa recibe el mismo monto y el total es la suma")
    void aFixedAmountGoesToEveryActiveUnit() {
        unidad("A-301", "40.000000");
        unidad("A-302", "60.000000");
        configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");

        ExpenseRunView run = expenses.calculate(condominium, concept, 2026, 9, "emma");

        assertThat(run.status()).isEqualTo(PaymentObligationStatus.DRAFT);
        assertThat(run.lines())
                .extracting(ExpenseLineView::amount)
                .containsExactly(new BigDecimal("350.00"), new BigDecimal("350.00"));
        assertThat(run.total()).isEqualByComparingTo("700.00");
        assertThat(run.currency()).isEqualTo("BOB");
        // El numero legible se compone del concepto, el mes y el codigo de la unidad (analisis 5.2).
        assertThat(run.lines())
                .extracting(ExpenseLineView::number)
                .containsExactly("EXP-2026-09-A-301", "EXP-2026-09-A-302");
    }

    @Test
    @DisplayName("por alicuota, el total emitido cuadra exacto aunque el reparto no sea redondo")
    void theTotalMatchesTheSumEvenWhenItDoesNotDivideEvenly() {
        // Tres unidades que se reparten un tercio cada una: el reparto exacto es 333,333... y el
        // criterio del ticket --que el total cuadre con la suma-- solo se cumple si el centavo que
        // sobra se le da a alguien. Se lo lleva la de resto mayor, que es la primera alicuota.
        unidad("A-301", "33.333334");
        unidad("A-302", "33.333333");
        unidad("A-303", "33.333333");
        configurar(ExpenseMethod.BY_ALIQUOT, "1000.00");

        ExpenseRunView run = expenses.calculate(condominium, concept, 2026, 9, "emma");

        assertThat(run.lines())
                .extracting(ExpenseLineView::amount)
                .containsExactly(new BigDecimal("333.34"), new BigDecimal("333.33"), new BigDecimal("333.33"));
        assertThat(run.total()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("por alicuota, cada unidad recibe su proporcion")
    void eachUnitGetsItsShare() {
        unidad("A-301", "25.000000");
        unidad("A-302", "75.000000");
        configurar(ExpenseMethod.BY_ALIQUOT, "14700.00");

        ExpenseRunView run = expenses.calculate(condominium, concept, 2026, 9, "emma");

        assertThat(run.lines())
                .extracting(ExpenseLineView::amount)
                .containsExactly(new BigDecimal("3675.00"), new BigDecimal("11025.00"));
        assertThat(run.total()).isEqualByComparingTo("14700.00");
    }

    @Test
    @DisplayName("si las alicuotas no suman 100 no se reparte nada, y el mensaje dice cuanto suman")
    void aliquotsThatDoNotAddUpStopTheRun() {
        unidad("A-301", "40.000000");
        unidad("A-302", "50.000000");
        configurar(ExpenseMethod.BY_ALIQUOT, "1000.00");

        // Es la validacion que UnitRelations dejo escrita para M2: no se puede comprobar al dar de
        // alta una unidad, y es aqui donde una suma que no cuadra se convierte en dinero mal cobrado.
        assertThatThrownBy(() -> expenses.calculate(condominium, concept, 2026, 9, "emma"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("90")
                .hasMessageContaining("100");
    }

    @Test
    @DisplayName("a monto fijo no se exige que las alicuotas sumen 100")
    void aFixedAmountDoesNotCareAboutAliquots() {
        unidad("A-301", "40.000000");
        unidad("A-302", "50.000000");
        configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");

        assertThat(expenses.calculate(condominium, concept, 2026, 9, "emma").total())
                .isEqualByComparingTo("700.00");
    }

    @Test
    @DisplayName("una unidad de alicuota cero no recibe obligacion, y no hace falta que tenga responsable")
    void aUnitWithNoAliquotGetsNothing() {
        unidad("A-301", "100.000000");
        // Un parqueo con alicuota cero, y a proposito sin responsable de pago: no se le cobra nada,
        // asi que exigirselo seria pedir un dato para no usarlo.
        unidadHuerfana("P-01", "0.000000");
        configurar(ExpenseMethod.BY_ALIQUOT, "1000.00");

        ExpenseRunView run = expenses.calculate(condominium, concept, 2026, 9, "emma");

        // Un cargo de cero no es una deuda: lo que corresponde es no crear la obligacion (#23).
        assertThat(run.lines()).extracting(ExpenseLineView::unitCode).containsExactly("A-301");
        assertThat(run.total()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("una unidad inactiva no recibe obligacion")
    void anInactiveUnitGetsNothing() {
        unidad("A-301", "50.000000");
        UUID baja = unidad("A-302", "50.000000");
        // No hay operacion de baja de unidad todavia --el puerto solo da de alta y consulta-- asi
        // que el estado se cambia en la base. El dia que exista, este caso se escribe con ella.
        entityManager.flush();
        int filas = new JdbcTemplate(dataSource).update("UPDATE unit SET status = ? WHERE id = ?", "INACTIVE", baja);
        assertThat(filas).isEqualTo(1);
        configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");

        ExpenseRunView run = expenses.calculate(condominium, concept, 2026, 9, "emma");

        assertThat(run.lines()).extracting(ExpenseLineView::unitCode).containsExactly("A-301");
    }

    @Test
    @DisplayName("el vencimiento cae el mes siguiente, el dia que dice la configuracion del condominio")
    void theDueDateFallsTheMonthAfter() {
        unidad("A-301", "100.000000");
        configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");

        ExpenseRunView run = expenses.calculate(condominium, concept, 2026, 9, "emma");

        // El analisis 7.1 lo escribe asi: las expensas de 2026-09 vencen el 10/10. Un condominio
        // cobra el mes ya pasado.
        assertThat(run.lines().get(0).dueDate()).isEqualTo(LocalDate.of(2026, 10, DIA_DE_VENCIMIENTO));
    }

    @Test
    @DisplayName("la obligacion queda apuntando a la configuracion con la que se calculo")
    void theObligationPointsAtItsSetting() {
        UUID unit = unidad("A-301", "100.000000");
        ExpenseSettingView setting = configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");

        expenses.calculate(condominium, concept, 2026, 9, "emma");

        // El origen por referencia del analisis 5.1, que el #23 dejo vacio y este ticket llena.
        PaymentObligationView obligation = obligations.ofUnit(condominium, unit).get(0);
        assertThat(obligation.sourceType()).isEqualTo("EXPENSE_SETTING");
        assertThat(obligation.sourceId()).isEqualTo(setting.id());
    }

    @Test
    @DisplayName("recalcular reemplaza los borradores y no les cambia el numero")
    void recalculatingReplacesTheDrafts() {
        unidad("A-301", "100.000000");
        configurar(ExpenseMethod.FIXED_PER_UNIT, "300.00");
        ExpenseRunView primero = expenses.calculate(condominium, concept, 2026, 9, "emma");

        configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");
        ExpenseRunView segundo = expenses.calculate(condominium, concept, 2026, 9, "emma");

        assertThat(segundo.total()).isEqualByComparingTo("350.00");
        // El borrador viejo se borra, no se anula: si se anulara, su numero seguiria ocupado y el
        // calculo nuevo saldria con correlativo.
        assertThat(segundo.lines().get(0).number())
                .isEqualTo(primero.lines().get(0).number());
        assertThat(obligations.ofPeriod(condominium, segundo.periodId())).hasSize(1);
    }

    @Test
    @DisplayName("calcular abre el periodo si no estaba abierto")
    void calculatingOpensThePeriod() {
        unidad("A-301", "100.000000");
        configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");

        // Es lo que el #101 dejo escrito que necesitaba este ticket: el worker del #28 no puede
        // fallar en un mes que nadie preparo.
        assertThat(periods.list(condominium)).isEmpty();

        ExpenseRunView run = expenses.calculate(condominium, concept, 2026, 9, "emma");

        assertThat(periods.list(condominium))
                .extracting(BillingPeriodView::id, BillingPeriodView::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(run.periodId(), BillingPeriodStatus.DRAFT));
    }

    @Test
    @DisplayName("sin configuracion vigente no se calcula nada")
    void nothingIsCalculatedWithoutASetting() {
        unidad("A-301", "100.000000");

        assertThatThrownBy(() -> expenses.calculate(condominium, concept, 2026, 9, "emma"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("EXP");
    }

    @Test
    @DisplayName("sin unidades activas no hay a quien emitirle")
    void nothingIsCalculatedWithoutUnits() {
        configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");

        assertThatThrownBy(() -> expenses.calculate(condominium, concept, 2026, 9, "emma"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("unidades activas");
    }

    @Test
    @DisplayName("una unidad sin responsable de pago detiene todo el lote, y el mensaje la nombra")
    void aUnitWithoutAResponsibleStopsTheRun() {
        unidad("A-301", "50.000000");
        unidadHuerfana("A-302", "50.000000");
        configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");

        // La emision es transaccional por lote (analisis 7.1): si falla una unidad no se emite
        // ninguna, y el mensaje lista las que hay que arreglar.
        assertThatThrownBy(() -> expenses.calculate(condominium, concept, 2026, 9, "emma"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("A-302")
                .hasMessageContaining("sin responsable de pago");
    }

    @Test
    @DisplayName("una unidad con dos responsables del mismo concepto tampoco se emite: no se adivina")
    void twoResponsiblesForTheSameConceptStopTheRun() {
        UUID unit = unidad("A-301", "100.000000");
        UUID otra =
                people.register(new NewPerson("Luis", "Mamani", null), "emma").id();
        relations.assignBilling(condominium, unit, otra, null, "emma");
        configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");

        // RF-08 admite que respondan "ambos", pero la obligacion tiene un responsable: elegir uno
        // seria decidir a quien se le cobra sin que nadie lo haya dicho.
        assertThatThrownBy(() -> expenses.calculate(condominium, concept, 2026, 9, "emma"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("A-301")
                .hasMessageContaining("mas de un responsable");
    }

    @Test
    @DisplayName("la responsabilidad de ese concepto manda sobre la general")
    void theConceptResponsibilityWinsOverTheGeneralOne() {
        UUID unit = unidad("A-301", "100.000000");
        UUID delConcepto =
                people.register(new NewPerson("Luis", "Mamani", null), "emma").id();
        relations.assignBilling(condominium, unit, delConcepto, concept, "emma");
        configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");

        ExpenseRunView run = expenses.calculate(condominium, concept, 2026, 9, "emma");

        // Es la precedencia que define assignBilling: la del concepto es la mas especifica, y es como
        // se reparte "el inquilino paga el agua y el propietario las expensas".
        assertThat(run.lines().get(0).responsiblePersonId()).isEqualTo(delConcepto);
    }

    // --- La emision ---------------------------------------------------------------------------

    @Test
    @DisplayName("emitir saca a cobro todas las obligaciones y pasa el periodo a ISSUED")
    void issuingPutsEverythingOutForCollection() {
        unidad("A-301", "50.000000");
        unidad("A-302", "50.000000");
        configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");
        expenses.calculate(condominium, concept, 2026, 9, "emma");

        ExpenseRunView run = expenses.issue(condominium, concept, 2026, 9, "emma");

        assertThat(run.status()).isEqualTo(PaymentObligationStatus.ISSUED);
        assertThat(run.total()).isEqualByComparingTo("700.00");
        assertThat(obligations.ofPeriod(condominium, run.periodId())).allSatisfy(obligation -> {
            assertThat(obligation.status()).isEqualTo(PaymentObligationStatus.ISSUED);
            assertThat(obligation.issuedAt()).isNotNull();
        });
        assertThat(periods.list(condominium))
                .extracting(BillingPeriodView::status)
                .containsExactly(BillingPeriodStatus.ISSUED);
    }

    @Test
    @DisplayName("emitir un mes ya emitido no crea nada y lo informa")
    void issuingTwiceCreatesNothing() {
        unidad("A-301", "100.000000");
        configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");
        expenses.calculate(condominium, concept, 2026, 9, "emma");
        ExpenseRunView primera = expenses.issue(condominium, concept, 2026, 9, "emma");

        // El primer criterio del ticket. Es un conflicto y no una respuesta vacia porque emitir dos
        // veces duplicaria la deuda de todo el edificio: eso no puede pasar en silencio.
        assertThatThrownBy(() -> expenses.issue(condominium, concept, 2026, 9, "emma"))
                .isInstanceOf(StateConflict.class)
                .hasMessageContaining("2026-09")
                .hasMessageContaining("1");

        assertThat(obligations.ofPeriod(condominium, primera.periodId())).hasSize(1);
    }

    @Test
    @DisplayName("recalcular un mes ya emitido tampoco se admite")
    void recalculatingAnIssuedMonthIsRejected() {
        unidad("A-301", "100.000000");
        configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");
        expenses.calculate(condominium, concept, 2026, 9, "emma");
        expenses.issue(condominium, concept, 2026, 9, "emma");

        assertThatThrownBy(() -> expenses.calculate(condominium, concept, 2026, 9, "emma"))
                .isInstanceOf(StateConflict.class)
                .hasMessageContaining("ya se emitieron");
    }

    @Test
    @DisplayName("emitir sin haber calculado no emite nada")
    void issuingWithoutCalculatingDoesNothing() {
        unidad("A-301", "100.000000");
        configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");

        assertThatThrownBy(() -> expenses.issue(condominium, concept, 2026, 9, "emma"))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("calculalas");
    }

    @Test
    @DisplayName("un segundo concepto se emite en el mismo mes, aunque el periodo ya este emitido")
    void aSecondConceptCanBeIssuedInTheSameMonth() {
        UUID unit = unidad("A-301", "100.000000");
        configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");
        expenses.calculate(condominium, concept, 2026, 9, "emma");
        expenses.issue(condominium, concept, 2026, 9, "emma");

        // Un condominio que cobra expensas y fondo de reserva por separado emite dos conceptos el
        // mismo mes, y el segundo encuentra el periodo ya en ISSUED. Eso es correcto.
        UUID fondo = concepts.register(
                        condominium,
                        new NewChargeConcept("FDR", "Fondo de reserva", ChargeConceptType.DUES, PaymentGroup.DUES),
                        "emma")
                .id();
        relations.assignBilling(condominium, unit, person, fondo, "emma");
        expenses.configure(
                condominium,
                new NewExpenseSetting(fondo, ExpenseMethod.FIXED_PER_UNIT, new BigDecimal("50.00")),
                "emma");

        expenses.calculate(condominium, fondo, 2026, 9, "emma");
        ExpenseRunView run = expenses.issue(condominium, fondo, 2026, 9, "emma");

        assertThat(run.total()).isEqualByComparingTo("50.00");
        assertThat(obligations.ofPeriod(condominium, run.periodId())).hasSize(2);
    }

    @Test
    @DisplayName("un mes cerrado no admite ni calculo ni emision")
    void aClosedMonthTakesNothing() {
        unidad("A-301", "100.000000");
        configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");
        UUID period =
                periods.open(condominium, new NewBillingPeriod(2026, 9), "emma").id();
        periods.issue(condominium, period, "emma");
        periods.close(condominium, period, "emma");

        assertThatThrownBy(() -> expenses.calculate(condominium, concept, 2026, 9, "emma"))
                .isInstanceOf(StateConflict.class)
                .hasMessageContaining("cerrado");
        assertThatThrownBy(() -> expenses.issue(condominium, concept, 2026, 9, "emma"))
                .isInstanceOf(StateConflict.class)
                .hasMessageContaining("cerrado");
    }

    @Test
    @DisplayName("emitir publica el resumen del mes y un aviso por obligacion")
    void issuingPublishesTheSummaryAndOnePerObligation(PublishedEvents events) {
        unidad("A-301", "50.000000");
        unidad("A-302", "50.000000");
        configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");
        expenses.calculate(condominium, concept, 2026, 9, "emma");

        expenses.issue(condominium, concept, 2026, 9, "emma");

        // Los dos hacen falta: el de cada obligacion es el que escuchan las notificaciones del #50
        // para avisar a su propietario, y el resumen es el que dice quien emitio el mes.
        assertThat(events.ofType(PaymentObligationIssued.class)
                        .matching(e -> e.condominiumId().equals(condominium)))
                .hasSize(2);
        assertThat(events.ofType(ExpensesIssued.class)).singleElement().satisfies(issued -> {
            assertThat(issued.obligations()).isEqualTo(2);
            assertThat(issued.total()).isEqualByComparingTo("700.00");
            assertThat(issued.year()).isEqualTo(2026);
            assertThat(issued.month()).isEqualTo(9);
        });
    }

    @Test
    @DisplayName("lo emitido no cambia cuando cambia la configuracion")
    void whatIsIssuedDoesNotChangeWithTheSetting() {
        UUID unit = unidad("A-301", "100.000000");
        ExpenseSettingView conLaQueSeEmitio = configurar(ExpenseMethod.FIXED_PER_UNIT, "350.00");
        expenses.calculate(condominium, concept, 2026, 9, "emma");
        expenses.issue(condominium, concept, 2026, 9, "emma");

        configurar(ExpenseMethod.FIXED_PER_UNIT, "500.00");

        // El motivo de que la configuracion lleve vigencia: la fila con la que se emitio sigue ahi y
        // la obligacion la nombra, asi que subir el monto no reescribe lo que ya esta a cobro.
        PaymentObligationView obligation = obligations.ofUnit(condominium, unit).get(0);
        assertThat(obligation.amount()).isEqualByComparingTo("350.00");
        assertThat(obligation.sourceId()).isEqualTo(conLaQueSeEmitio.id());
    }
}
