package bo.coprop.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.billing.BillingPeriods;
import bo.coprop.billing.ChargeConceptType;
import bo.coprop.billing.ChargeConcepts;
import bo.coprop.billing.NewBillingPeriod;
import bo.coprop.billing.NewChargeConcept;
import bo.coprop.billing.NewPaymentObligation;
import bo.coprop.billing.PaymentGroup;
import bo.coprop.billing.PaymentObligations;
import bo.coprop.condominium.CondominiumType;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import bo.coprop.condominium.NewUnit;
import bo.coprop.condominium.UnitRelations;
import bo.coprop.condominium.UnitType;
import bo.coprop.condominium.Units;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.InvitationDoubles;
import bo.coprop.identity.Invitations;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.Memberships;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.identity.PersonView;
import bo.coprop.shared.PagedResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * La bitacora del issue #21: que las acciones lleguen, que no se puedan tocar y que se puedan
 * buscar.
 *
 * <p><strong>No es {@code @Transactional}</strong>, y no puede serlo: el oyente corre
 * <em>despues</em> del commit. En un test transaccional ese commit no llega nunca, asi que no se
 * escribiria ni una fila y la suite entera pasaria sin comprobar nada.
 *
 * <p>Lo que si se puede, porque el oyente es sincrono, es <strong>leer la fila justo despues de la
 * accion</strong>, sin sondear ni dormir. Esa es la mitad practica de la decision que hay escrita en
 * el oyente: con un oyente asincrono, cada caso de aqui tendria que esperar a que apareciera.
 */
@Import({TestcontainersConfiguration.class, InvitationDoubles.class})
@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class AuditTrailTests {

    @Autowired
    private AuditTrail auditTrail;

    @Autowired
    private Condominiums condominiums;

    @Autowired
    private People people;

    @Autowired
    private Memberships memberships;

    @Autowired
    private bo.coprop.identity.Identities identities;

    @Autowired
    private Invitations invitations;

    @Autowired
    private Units units;

    @Autowired
    private UnitRelations relations;

    @Autowired
    private ChargeConcepts chargeConcepts;

    @Autowired
    private BillingPeriods billingPeriods;

    @Autowired
    private PaymentObligations obligations;

    @Autowired
    private bo.coprop.billing.Expenses expenses;

    @Autowired
    private DataSource fuenteDeDatos;

    @Autowired
    private InvitationDoubles.RelojDePrueba clock;

    @Autowired
    private InvitationDoubles.CorreoDePrueba buzon;

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    private UUID condominium;
    private UUID administradora;

    @BeforeEach
    void prepareACondominium() {
        condominium = condominiums
                .register(
                        new NewCondominium(
                                "Auditoria " + UUID.randomUUID(),
                                nitNuevo(),
                                CondominiumType.BUILDING,
                                "America/La_Paz",
                                5,
                                10),
                        "admin")
                .id();
        administradora = cuentaNueva().id();
    }

    private static String nitNuevo() {
        return String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_0000L));
    }

    private AccountView cuentaNueva() {
        PersonView person = people.register(new NewPerson("Emma", "Tola", null), "admin");
        return people.openAccount(person.id(), "emma-" + UUID.randomUUID() + "@coprop.bo", null, "admin");
    }

    private List<AuditEntryView> delCondominio() {
        return auditTrail
                .ofCondominium(condominium, null, null, null, null, 0, 100)
                .content();
    }

    /**
     * La bitacora de plataforma, la del issue #20.
     *
     * <p>Se piden muchas filas y se busca dentro en vez de comparar la lista: estos tests comparten
     * base, y la plataforma tiene una sola bitacora para todos --no hay condominio que los separe--
     * asi que lo que hay ahi lo escribieron tambien los demas casos.
     */
    private List<AuditEntryView> deLaPlataforma() {
        return auditTrail.ofPlatform(null, null, null, null, 0, 200).content();
    }

    /**
     * La fila de esa accion en la bitacora del condominio.
     *
     * <p>Sin esperas: el oyente es sincrono, asi que para cuando la accion termino la fila ya esta.
     * Si no esta, el mensaje dice que si llego, que es lo que hace falta para entender por que.
     */
    private AuditEntryView laFilaDe(String action) {
        List<AuditEntryView> todas = delCondominio();
        return todas.stream()
                .filter(fila -> fila.action().equals(action))
                .findFirst()
                .orElseThrow(() -> new AssertionError("La bitacora no registro %s. Lo que si tiene: %s"
                        .formatted(
                                action,
                                todas.stream().map(AuditEntryView::action).toList())));
    }

    // --- Que las acciones lleguen -----------------------------------------------------------

    @Test
    @DisplayName("el alta de un condominio queda en la bitacora, con su nombre")
    void theCondominiumRegistrationIsRecorded() {
        // Es el primer criterio del issue #9 --"un condominio se puede crear con su configuracion
        // minima y queda auditado"-- y estuvo sin cumplirse hasta el 16-oct-2026. El evento se
        // publicaba desde el principio, pero no implementaba AuditableEvent: nadie lo escuchaba,
        // asi que el alta no se registraba. No fallaba nada, simplemente no estaba, que es la
        // forma del riesgo que tiene esa interfaz de nacimiento. De ahi este caso.
        AuditEntryView fila = laFilaDe("CONDOMINIUM_REGISTERED");

        assertThat(fila.actor()).isEqualTo("admin");
        assertThat(fila.entity()).isEqualTo("condominio");
        // La unica accion cuyo condominio y cuya entidad son la misma cosa.
        assertThat(fila.condominium()).isEqualTo(condominium);
        assertThat(fila.entityId()).isEqualTo(condominium);
        // Nulo y no vacio: no habia nada antes del alta.
        assertThat(fila.oldValues()).isNull();
        assertThat(fila.newValues())
                .hasEntrySatisfying("name", name -> assertThat(name).startsWith("Auditoria "));
    }

    @Test
    @DisplayName("conceder una membresia queda en la bitacora, con sus roles")
    void theMembershipIsRecorded() {
        UUID invited = cuentaNueva().id();

        memberships.grant(
                condominium,
                invited,
                Set.of(MembershipRole.OWNER, MembershipRole.BOARD),
                null,
                administradora.toString());

        AuditEntryView fila = laFilaDe("MEMBERSHIP_GRANTED");
        assertThat(fila.actor()).isEqualTo(administradora.toString());
        assertThat(fila.entity()).isEqualTo("membresia");
        assertThat(fila.condominium()).isEqualTo(condominium);
        // Antes es nulo porque no habia nada antes: la membresia nace aqui. Vacio querria decir
        // "existia y no tenia valores", que no es lo mismo.
        assertThat(fila.oldValues()).isNull();
        assertThat(fila.newValues()).containsEntry("accountId", invited.toString());
        // Ordenados al escribirlos, para que dos filas con los mismos roles se vean iguales.
        assertThat(fila.newValues()).containsEntry("roles", "BOARD,OWNER");
    }

    @Test
    @DisplayName("el alta de un concepto de cobro queda en la bitacora, con su tipo y su grupo")
    void theChargeConceptRegistrationIsRecorded() {
        // Por la misma razon que el caso del condominio de arriba: que un evento implemente
        // AuditableEvent es toda la declaracion que hay, y olvidarlo no rompe nada --simplemente la
        // accion no se registra--. Este caso es lo que lo comprueba para el issue #100.
        chargeConcepts.register(
                condominium,
                new NewChargeConcept("AUD-EXP", "Expensas ordinarias", ChargeConceptType.DUES, PaymentGroup.DUES),
                administradora.toString());

        AuditEntryView fila = laFilaDe("CHARGE_CONCEPT_REGISTERED");
        assertThat(fila.actor()).isEqualTo(administradora.toString());
        assertThat(fila.entity()).isEqualTo("concepto-de-cobro");
        assertThat(fila.condominium()).isEqualTo(condominium);
        assertThat(fila.oldValues()).isNull();
        assertThat(fila.newValues())
                .containsEntry("code", "AUD-EXP")
                .containsEntry("type", "DUES")
                // El grupo de pago se guarda aunque hoy coincida con el tipo: es el que decide con
                // que se cobra, y de el dependera que un QR acepte o rechace una obligacion.
                .containsEntry("paymentGroup", "DUES");
    }

    @Test
    @DisplayName("abrir y emitir un periodo dejan dos filas, y la segunda lleva las dos mitades")
    void theBillingPeriodLifecycleIsRecorded() {
        var period = billingPeriods.open(condominium, new NewBillingPeriod(2026, 10), administradora.toString());
        billingPeriods.issue(condominium, period.id(), administradora.toString());

        AuditEntryView apertura = laFilaDe("BILLING_PERIOD_OPENED");
        assertThat(apertura.entity()).isEqualTo("periodo-de-facturacion");
        assertThat(apertura.condominium()).isEqualTo(condominium);
        assertThat(apertura.oldValues()).isNull();
        assertThat(apertura.newValues()).containsEntry("month", "10").containsEntry("status", "DRAFT");

        // El nombre de la accion sale del estado al que paso, no del nombre del record: un solo
        // evento sirve a las dos transiciones, igual que en la invitacion emitida o reenviada.
        AuditEntryView emision = laFilaDe("BILLING_PERIOD_ISSUED");
        assertThat(emision.entityId()).isEqualTo(period.id());
        assertThat(emision.oldValues()).containsEntry("status", "DRAFT");
        assertThat(emision.newValues()).containsEntry("status", "ISSUED");
    }

    @Test
    @DisplayName("emitir y anular una obligacion dejan fila; crearla en borrador no")
    void thePaymentObligationLifecycleIsRecorded() {
        UUID unit = units.register(
                        condominium,
                        new NewUnit("AUD-1", UnitType.APARTMENT, new BigDecimal("1.000000"), null, null, null),
                        administradora.toString())
                .id();
        UUID concept = chargeConcepts
                .register(
                        condominium,
                        new NewChargeConcept("AUD-OBL", "Expensas", ChargeConceptType.DUES, PaymentGroup.DUES),
                        administradora.toString())
                .id();
        UUID period = billingPeriods
                .open(condominium, new NewBillingPeriod(2026, 11), administradora.toString())
                .id();
        UUID obligation = obligations
                .register(
                        condominium,
                        new NewPaymentObligation(
                                unit,
                                people.register(new NewPerson("Aud", "Quispe", null), "admin")
                                        .id(),
                                concept,
                                period,
                                new BigDecimal("350.00"),
                                LocalDate.of(2099, 12, 31),
                                null,
                                null,
                                null),
                        administradora.toString())
                .id();

        // El borrador no deja fila, y es una decision del #23: un calculo que el propietario no ve
        // no es una accion que auditar. Si algun dia se audita, este caso es el que lo dira.
        assertThat(delCondominio())
                .extracting(AuditEntryView::action)
                .doesNotContain("PAYMENT_OBLIGATION_ISSUED", "PAYMENT_OBLIGATION_CANCELLED");

        obligations.issue(condominium, obligation, administradora.toString());
        AuditEntryView emision = laFilaDe("PAYMENT_OBLIGATION_ISSUED");
        assertThat(emision.entity()).isEqualTo("obligacion-de-pago");
        assertThat(emision.entityId()).isEqualTo(obligation);
        assertThat(emision.newValues())
                .containsEntry("status", "ISSUED")
                .containsEntry("amount", "350.00")
                .containsEntry("currency", "BOB");

        obligations.cancel(condominium, obligation, "se emitio a la unidad equivocada", administradora.toString());
        AuditEntryView anulacion = laFilaDe("PAYMENT_OBLIGATION_CANCELLED");
        // El motivo vive solo en la bitacora, igual que en la baja de una membresia.
        assertThat(anulacion.newValues()).containsEntry("reason", "se emitio a la unidad equivocada");
        assertThat(anulacion.oldValues()).containsEntry("status", "ISSUED");
    }

    @Test
    @DisplayName("configurar expensas deja fila, y la segunda vez con los valores de antes")
    void theExpenseSettingIsRecorded() {
        UUID concept = chargeConcepts
                .register(
                        condominium,
                        new NewChargeConcept("AUD-EXP", "Expensas", ChargeConceptType.DUES, PaymentGroup.DUES),
                        administradora.toString())
                .id();

        expenses.configure(
                condominium,
                new bo.coprop.billing.NewExpenseSetting(
                        concept, bo.coprop.billing.ExpenseMethod.FIXED_PER_UNIT, new BigDecimal("300.00")),
                administradora.toString());

        AuditEntryView primera = laFilaDe("EXPENSE_SETTING_CHANGED");
        assertThat(primera.entity()).isEqualTo("configuracion-de-expensas");
        assertThat(primera.newValues()).containsEntry("amount", "300.00").containsEntry("currency", "BOB");
        // La primera no tiene "antes": no habia nada que cambiar.
        assertThat(primera.oldValues()).isNull();

        expenses.configure(
                condominium,
                new bo.coprop.billing.NewExpenseSetting(
                        concept, bo.coprop.billing.ExpenseMethod.BY_ALIQUOT, new BigDecimal("14700.00")),
                administradora.toString());

        // Las dos filas tienen la misma accion, asi que se busca la que si tiene mitad de antes.
        AuditEntryView segunda = delCondominio().stream()
                .filter(fila -> "EXPENSE_SETTING_CHANGED".equals(fila.action()) && fila.oldValues() != null)
                .findFirst()
                .orElseThrow();
        assertThat(segunda.oldValues()).containsEntry("amount", "300.00");
        assertThat(segunda.newValues()).containsEntry("method", "BY_ALIQUOT");
    }

    @Test
    @DisplayName("emitir las expensas deja el resumen del mes, ademas del aviso de cada obligacion")
    void theExpenseRunIsRecorded() {
        UUID unit = units.register(
                        condominium,
                        new NewUnit("AUD-EXP-1", UnitType.APARTMENT, new BigDecimal("1.000000"), null, null, null),
                        administradora.toString())
                .id();
        UUID concept = chargeConcepts
                .register(
                        condominium,
                        new NewChargeConcept("AUD-RUN", "Expensas", ChargeConceptType.DUES, PaymentGroup.DUES),
                        administradora.toString())
                .id();
        relations.assignBilling(
                condominium,
                unit,
                people.register(new NewPerson("Aud", "Mamani", null), "admin").id(),
                null,
                administradora.toString());
        expenses.configure(
                condominium,
                new bo.coprop.billing.NewExpenseSetting(
                        concept, bo.coprop.billing.ExpenseMethod.FIXED_PER_UNIT, new BigDecimal("350.00")),
                administradora.toString());

        expenses.calculate(condominium, concept, 2026, 12, administradora.toString());

        // El calculo no deja fila: es un borrador, igual que el alta de una obligacion sola.
        assertThat(delCondominio()).extracting(AuditEntryView::action).doesNotContain("EXPENSES_ISSUED");

        expenses.issue(condominium, concept, 2026, 12, administradora.toString());

        AuditEntryView emision = laFilaDe("EXPENSES_ISSUED");
        // El periodo es la entidad, no el concepto: es lo que una persona nombra, "la emision de
        // diciembre", y lo que hace que la historia del mes salga junta.
        assertThat(emision.entity()).isEqualTo("periodo-de-facturacion");
        assertThat(emision.newValues())
                .containsEntry("period", "2026-12")
                .containsEntry("obligations", "1")
                .containsEntry("total", "350.00");

        // Y el aviso de la obligacion sigue estando: los dos hacen falta y dicen cosas distintas.
        assertThat(delCondominio()).extracting(AuditEntryView::action).contains("PAYMENT_OBLIGATION_ISSUED");
    }

    @Test
    @DisplayName("cambiar la configuracion queda con los valores de oldValues y de newValues")
    void theConfigIsStoredWithBothHalves() {
        condominiums.changeConfig(condominium, 7, 20, administradora.toString());

        AuditEntryView fila = laFilaDe("CONFIGURATION_CHANGED");
        assertThat(fila.entity()).isEqualTo("configuracion");
        // La entidad es el CONDOMINIO y no la fila de configuracion: la configuracion lleva
        // vigencia, asi que su identificador cambia en cada cambio y no serviria para seguir la
        // historia.
        assertThat(fila.entityId()).isEqualTo(condominium);
        assertThat(fila.oldValues()).containsEntry("issueDay", "5").containsEntry("dueDay", "10");
        assertThat(fila.newValues()).containsEntry("issueDay", "7").containsEntry("dueDay", "20");
    }

    @Test
    @DisplayName("toda invitacion, aceptacion y revocacion queda en la bitacora")
    void theInvitationsAreRecorded() {
        // Es el tercer criterio de aceptacion del issue #18, que quedo esperando a este modulo.
        memberships.grant(condominium, administradora, Set.of(MembershipRole.CONDO_ADMIN), null, "admin");
        AccountView anInvitee = cuentaNueva();
        AccountView anotherInvitee = cuentaNueva();

        UUID aRevocar = invitations
                .issue(condominium, anInvitee.id(), Set.of(MembershipRole.OWNER), administradora.toString())
                .id();
        invitations.revoke(condominium, aRevocar, administradora.toString());

        invitations.issue(condominium, anotherInvitee.id(), Set.of(MembershipRole.TENANT), administradora.toString());
        invitations.accept(tokenDe(anotherInvitee), anotherInvitee.id());

        assertThat(laFilaDe("INVITATION_ISSUED").entity()).isEqualTo("invitacion");
        assertThat(laFilaDe("INVITATION_REVOKED").oldValues())
                .containsEntry("accountId", anInvitee.id().toString());
        assertThat(laFilaDe("INVITATION_ACCEPTED").newValues()).containsKey("membershipId");
    }

    @Test
    @DisplayName("un reenvio es una action distinta de una emision")
    void theResendIsAnotherAction() {
        // Quien lee la bitacora busca "a quien se invito" o "a quien hubo que insistirle". Con una
        // bandera dentro del JSON eso no se filtra.
        memberships.grant(condominium, administradora, Set.of(MembershipRole.CONDO_ADMIN), null, "admin");
        UUID first = invitations
                .issue(condominium, cuentaNueva().id(), Set.of(MembershipRole.OWNER), administradora.toString())
                .id();

        invitations.resend(condominium, first, administradora.toString());

        laFilaDe("INVITATION_RESENT");
        laFilaDe("INVITATION_REVOKED_BY_RESEND");
        assertThat(delCondominio().stream().map(AuditEntryView::action)).doesNotContain("INVITATION_REVOKED");
    }

    private String tokenDe(AccountView account) {
        return buzon.ultimoTokenPara(account.email())
                .orElseThrow(() -> new AssertionError("No llego la invitacion a " + account.email()));
    }

    @Test
    @DisplayName("la baja, la suspension y la reactivacion de una membresia quedan, con su motivo")
    void terminationsAndSuspensionsAreRecorded() {
        // Seguridad 8 pide los cambios de rol en la bitacora, y una baja es el mayor de todos: le
        // quita todos los que tenia. Es tambien la primera pregunta de quien mira la bitacora
        // despues de una queja: por que esta persona dejo de ver el edificio.
        UUID queSeVa = cuentaNueva().id();
        UUID queSeSuspende = cuentaNueva().id();
        UUID deLaQueSeVa = memberships
                .grant(condominium, queSeVa, Set.of(MembershipRole.OWNER), null, administradora.toString())
                .id();
        UUID deLaQueSeSuspende = memberships
                .grant(condominium, queSeSuspende, Set.of(MembershipRole.TENANT), null, administradora.toString())
                .id();

        memberships.revoke(condominium, deLaQueSeVa, "vendio su departamento", administradora.toString());
        memberships.suspend(condominium, deLaQueSeSuspende, "cuota impaga", administradora.toString());
        memberships.reinstate(condominium, deLaQueSeSuspende, "pago al dia", administradora.toString());

        AuditEntryView baja = laFilaDe("MEMBERSHIP_ENDED");
        assertThat(baja.entity()).isEqualTo("membresia");
        assertThat(baja.entityId()).isEqualTo(deLaQueSeVa);
        assertThat(baja.oldValues())
                .containsEntry("accountId", queSeVa.toString())
                .containsEntry("roles", "OWNER");
        // El motivo vive AQUI y solo aqui: la fila cerrada dice cuando, y la bitacora por que.
        assertThat(baja.newValues()).containsEntry("motivo", "vendio su departamento");

        assertThat(laFilaDe("MEMBERSHIP_SUSPENDED").newValues()).containsEntry("motivo", "cuota impaga");
        // Reactivar es una accion distinta y no la misma con una bandera: quien lee busca "a quien
        // se suspendio" o "a quien se le devolvio el acceso", y una bandera dentro del JSON no se
        // filtra. Mismo criterio que el reenvio de una invitacion.
        assertThat(laFilaDe("MEMBERSHIP_REACTIVATED").newValues()).containsEntry("motivo", "pago al dia");
    }

    @Test
    @DisplayName("la venta de una unidad queda como un solo hecho, con sus dos lados")
    void theSaleIsRecordedAsASingleFact() {
        UUID unit = units.register(
                        condominium,
                        new NewUnit("AUD-101", UnitType.APARTMENT, new BigDecimal("1.000000"), null, null, null),
                        administradora.toString())
                .id();
        UUID vendedor =
                people.register(new NewPerson("Vende", "Perez", null), "admin").id();
        UUID comprador =
                people.register(new NewPerson("Compra", "Perez", null), "admin").id();
        relations.grantOwnership(condominium, unit, vendedor, new BigDecimal("100.000000"), administradora.toString());

        relations.transferOwnership(condominium, unit, vendedor, comprador, administradora.toString());

        // Un evento propio y no dos --una cerrada y otra abierta-- porque lo que hay que poder
        // reconstruir dentro de un año es la venta, con sus dos lados.
        AuditEntryView venta = laFilaDe("OWNERSHIP_TRANSFERRED");
        assertThat(venta.entity()).isEqualTo("titularidad");
        // La entidad es la UNIDAD: la titularidad lleva vigencia, asi que su identificador cambia en
        // cada venta y no serviria para seguir la historia.
        assertThat(venta.entityId()).isEqualTo(unit);
        assertThat(venta.oldValues()).containsEntry("personId", vendedor.toString());
        assertThat(venta.newValues())
                .containsEntry("personId", comprador.toString())
                .containsEntry("relacionesCerradas", "1");

        // Y el alta que la precedio tambien esta: una bitacora que registra el fin de una relacion
        // y no su principio hace creer que la relacion nacio sola.
        assertThat(laFilaDe("OWNERSHIP_GRANTED").newValues()).containsEntry("personId", vendedor.toString());
    }

    @Test
    @DisplayName("el cambio de roles queda con las dos mitades, el motivo y la concentracion")
    void theRoleChangeIsRecorded() {
        // Seguridad 8 pide los cambios de rol en la bitacora, y este es el caso de todos los dias:
        // la baja del #19 era el extremo --quitarlos todos--. Importa mas que las demas filas porque
        // la tabla solo guarda los roles de HOY: el cambio edita membership_role en el sitio, asi
        // que la historia de quien tuvo cual esta aqui y en ningun otro sitio.
        UUID aQuienSeLeCambian = cuentaNueva().id();
        UUID suya = memberships
                .grant(
                        condominium,
                        aQuienSeLeCambian,
                        Set.of(MembershipRole.CONDO_ADMIN),
                        null,
                        administradora.toString())
                .id();

        memberships.changeRoles(
                condominium,
                suya,
                Set.of(MembershipRole.CONDO_ADMIN, MembershipRole.FINANCE_ADMIN),
                "en este edificio administra una sola persona",
                administradora.toString());

        AuditEntryView fila = laFilaDe("MEMBERSHIP_ROLES_CHANGED");
        assertThat(fila.entity()).isEqualTo("membresia");
        assertThat(fila.entityId()).isEqualTo(suya);
        assertThat(fila.actor()).isEqualTo(administradora.toString());
        assertThat(fila.oldValues())
                .containsEntry("accountId", aQuienSeLeCambian.toString())
                .containsEntry("roles", "CONDO_ADMIN");
        assertThat(fila.newValues())
                .containsEntry("roles", "CONDO_ADMIN,FINANCE_ADMIN")
                .containsEntry("motivo", "en este edificio administra una sola persona")
                // El aviso del alcance del issue #20. Seguridad 10.1 permite la concentracion a
                // condicion de que quede registrada, y esta es la forma de registrarla.
                .containsEntry("concentraFunciones", "true");
    }

    @Test
    @DisplayName("las acciones de plataforma no salen en la bitacora de un condominio, y si en la suya")
    void platformMattersBelongToNoCondominium() {
        // El rol de superadministrador no cuelga de ningun condominio, asi que su fila va sin
        // condominio y la consulta de un condominio no la lista. Sin esto, el administrador de un
        // edificio veria acciones de la plataforma entera.
        AccountView body = cuentaNueva();
        AccountView quienConcede = cuentaNueva();
        new JdbcTemplate(fuenteDeDatos)
                .update("insert into platform_admin (user_id, granted_at) values (?, now())", quienConcede.id());
        identities.grantPlatformAdmin(body.id(), quienConcede.id().toString());

        memberships.grant(condominium, body.id(), Set.of(MembershipRole.OWNER), null, administradora.toString());
        laFilaDe("MEMBERSHIP_GRANTED");

        assertThat(delCondominio().stream().map(AuditEntryView::action)).doesNotContain("PLATFORM_GRANTED");

        // Y la otra mitad, que es lo que trae el issue #20: en su propia consulta si sale, con el
        // actor de verdad --quien lo concedio-- y no el "arranque" de la unica fila que habia antes.
        assertThat(deLaPlataforma()).anySatisfy(fila -> assertThat(fila)
                .returns("PLATFORM_GRANTED", AuditEntryView::action)
                .returns(body.id(), AuditEntryView::entityId)
                .returns(quienConcede.id().toString(), AuditEntryView::actor)
                .returns(null, AuditEntryView::condominium));

        // Y las del condominio no se cuelan en la de plataforma: el filtro es la columna, no el
        // orden en que se escribieron.
        assertThat(deLaPlataforma().stream().map(AuditEntryView::action)).doesNotContain("MEMBERSHIP_GRANTED");
    }

    @Test
    @DisplayName("la revocacion del rol de plataforma queda, con su motivo: la fila de platform_admin se borra")
    void thePlatformRevocationIsRecorded() {
        AccountView quienConcede = cuentaNueva();
        AccountView quienLoPierde = cuentaNueva();
        new JdbcTemplate(fuenteDeDatos)
                .update("insert into platform_admin (user_id, granted_at) values (?, now())", quienConcede.id());
        identities.grantPlatformAdmin(quienLoPierde.id(), quienConcede.id().toString());

        identities.revokePlatformAdmin(
                quienLoPierde.id(), "dejo la empresa", quienConcede.id().toString());

        // Esta fila importa mas que la mayoria: revocar BORRA la fila de platform_admin, asi que si
        // esto no quedara, haber tenido el rol seria indistinguible de no haberlo tenido nunca.
        assertThat(deLaPlataforma()).anySatisfy(fila -> assertThat(fila)
                .returns("PLATFORM_REVOKED", AuditEntryView::action)
                .returns(quienLoPierde.id(), AuditEntryView::entityId));
        assertThat(identities.esSuperadministrador(quienLoPierde.id())).isFalse();
    }

    @Test
    @DisplayName("cerrar todas las sesiones queda en la bitacora de plataforma, con cuantas se cerraron")
    void closingAllSessionsIsRecorded() {
        AccountView principal = cuentaNueva();

        identities.signOutEverywhere(principal.id(), principal.id().toString());

        // Va sin condominio, como el rol de plataforma y por lo mismo: una sesion no es de un
        // edificio. Y se audita, al contrario que un cierre de sesion normal, porque su motivo
        // tipico es el que Seguridad 5.3 pone en la tabla --perdida o robo del telefono-- y quien
        // investigue despues necesita la hora a la que la persona se dio cuenta.
        assertThat(deLaPlataforma()).anySatisfy(fila -> assertThat(fila)
                .returns("SESSIONS_CLOSED", AuditEntryView::action)
                .returns(principal.id(), AuditEntryView::entityId)
                .returns(principal.id().toString(), AuditEntryView::actor)
                .returns(null, AuditEntryView::condominium));

        assertThat(delCondominio().stream().map(AuditEntryView::action)).doesNotContain("SESSIONS_CLOSED");
    }

    // --- Que no se pueda tocar: el primer criterio -------------------------------------------

    @Test
    @DisplayName("un intento de modificar la bitacora falla en la base de datos")
    void theAuditTrailIsNotModified() {
        // El primer criterio del issue, y se comprueba por SQL a proposito: lo que tiene que
        // proteger la fila es la base, no el codigo Java que hay delante. Un disparador y no un
        // REVOKE porque la aplicacion se conecta como superusuario y los privilegios no le aplican,
        // que es lo que se descubrio evaluando RLS para el #17.
        memberships.grant(
                condominium, cuentaNueva().id(), Set.of(MembershipRole.OWNER), null, administradora.toString());
        UUID fila = laFilaDe("MEMBERSHIP_GRANTED").id();
        var sql = new JdbcTemplate(fuenteDeDatos);

        assertThatThrownBy(() -> sql.update("update audit_log set actor = 'otro' where id = ?", fila))
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> sql.update("delete from audit_log where id = ?", fila))
                .hasMessageContaining("append-only");

        // Y sigue ahi, con su actor original.
        assertThat(sql.queryForObject("select actor from audit_log where id = ?", String.class, fila))
                .isEqualTo(administradora.toString());
    }

    // --- Que se pueda buscar: el segundo criterio --------------------------------------------

    @Test
    @DisplayName("la consulta filtra por actor, por entity y por rango de fechas")
    void theQueryFilters() {
        UUID otraActora = cuentaNueva().id();
        memberships.grant(
                condominium, cuentaNueva().id(), Set.of(MembershipRole.OWNER), null, administradora.toString());
        laFilaDe("MEMBERSHIP_GRANTED");

        // El reloj se adelanta entre las dos acciones para que caigan en instantes distintos y el
        // filtro de fechas tenga algo que separar. Con el reloj real, dos acciones seguidas pueden
        // compartir milisegundo.
        Instant corte = clock.instant().plusSeconds(1);
        clock.adelantar(Duration.ofMinutes(5));
        condominiums.changeConfig(condominium, 8, 21, otraActora.toString());
        laFilaDe("CONFIGURATION_CHANGED");

        // Por actor.
        assertThat(filtrando(otraActora.toString(), null, null, null))
                .extracting(AuditEntryView::action)
                .containsExactly("CONFIGURATION_CHANGED");

        // Por entidad.
        assertThat(filtrando(null, "membresia", null, null))
                .extracting(AuditEntryView::action)
                .containsExactly("MEMBERSHIP_GRANTED");

        // Por rango: desde el corte solo esta la segunda.
        assertThat(filtrando(null, null, corte, null))
                .extracting(AuditEntryView::action)
                .containsExactly("CONFIGURATION_CHANGED");

        // Y hasta el corte, la primera y el alta del condominio, que paso antes que todo. Hasta es
        // exclusivo, asi que dos rangos contiguos no devuelven la misma fila dos veces.
        //
        // Sin orden a proposito: el reloj de prueba esta parado, asi que el alta del @BeforeEach y
        // esta membresia comparten el mismo instante y cual sale primero no lo decide nada que
        // este test quiera afirmar. Donde el orden SI importa --la paginacion-- el reloj se
        // adelanta entre accion y accion para que haya un orden de verdad que comprobar.
        assertThat(filtrando(null, null, null, corte))
                .extracting(AuditEntryView::action)
                .containsExactlyInAnyOrder("MEMBERSHIP_GRANTED", "CONDOMINIUM_REGISTERED");

        // Los filtros se combinan: este actor no toco ninguna membresia.
        assertThat(filtrando(otraActora.toString(), "membresia", null, null)).isEmpty();
    }

    private List<AuditEntryView> filtrando(
            @org.jspecify.annotations.Nullable String actor,
            @org.jspecify.annotations.Nullable String entity,
            @org.jspecify.annotations.Nullable Instant from,
            @org.jspecify.annotations.Nullable Instant until) {
        return auditTrail
                .ofCondominium(condominium, actor, entity, from, until, 0, 100)
                .content();
    }

    @Test
    @DisplayName("la consulta pagina y devuelve lo mas reciente primero")
    void theQueryPagesAndSorts() {
        for (int i = 0; i < 3; i++) {
            clock.adelantar(Duration.ofMinutes(1));
            condominiums.changeConfig(condominium, 5 + i, 15 + i, administradora.toString());
        }
        // Cuatro y no tres: el alta del condominio tambien esta, escrita por el @BeforeEach.
        assertThat(delCondominio()).hasSize(4);

        PagedResponse<AuditEntryView> first = auditTrail.ofCondominium(condominium, null, null, null, null, 0, 2);
        assertThat(first.content()).hasSize(2);
        assertThat(first.total()).isEqualTo(4);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.page()).isZero();

        // Lo mas reciente primero: una bitacora se lee empezando por lo ultimo.
        assertThat(first.content().get(0).newValues()).containsEntry("issueDay", "7");
        assertThat(first.content().get(1).newValues()).containsEntry("issueDay", "6");

        PagedResponse<AuditEntryView> segunda = auditTrail.ofCondominium(condominium, null, null, null, null, 1, 2);
        assertThat(segunda.content()).hasSize(2);
        assertThat(segunda.content().get(0).newValues()).containsEntry("issueDay", "5");
        // Y al final de todo, el alta: es lo primero que le paso a este condominio.
        assertThat(segunda.content().get(1).action()).isEqualTo("CONDOMINIUM_REGISTERED");
    }

    // --- La retencion del año ----------------------------------------------------------------

    @Test
    @DisplayName("la purga borra lo viejo y deja lo del año")
    void thePurgeRespectsTheRetention() {
        memberships.grant(
                condominium, cuentaNueva().id(), Set.of(MembershipRole.OWNER), null, administradora.toString());
        UUID recent = laFilaDe("MEMBERSHIP_GRANTED").id();

        // Una fila vieja, puesta por SQL: para tenerla de hace trece meses habria que mover el reloj
        // antes de que naciera, y lo que se prueba aqui es la purga, no como llego la fila.
        //
        // La fecha se calcula con el reloj DE PRUEBA y no con el now() de la base. Con now(), el dia
        // que el reloj fijo del test quede a mas de un año del presente, la fila "reciente" tambien
        // caeria en la purga y el test fallaria por el calendario y no por el codigo.
        var sql = new JdbcTemplate(fuenteDeDatos);
        UUID vieja = UUID.randomUUID();
        sql.update(
                "insert into audit_log (id, actor, action, entity, entity_id, condominium_id, occurred_at)"
                        + " values (?, 'viejo', 'MEMBERSHIP_GRANTED', 'membresia', ?, ?, ?)",
                vieja,
                UUID.randomUUID(),
                condominium,
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(400))));

        long deleted = auditTrail.purge(clock.instant().minus(Duration.ofDays(365)));

        assertThat(deleted).isPositive();
        assertThat(sql.queryForObject("select count(*) from audit_log where id = ?", Long.class, vieja))
                .isZero();
        // Y la reciente sigue, que es la otra mitad: una purga que se llevara todo tambien pasaria
        // la primera comprobacion.
        assertThat(sql.queryForObject("select count(*) from audit_log where id = ?", Long.class, recent))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("la purga deja el disparador puesto")
    void thePurgeDoesNotDisarmTheProtection() {
        // La funcion de purga apaga el disparador para poder borrar. Si lo dejara apagado, la
        // bitacora quedaria modificable desde la primera purga y nadie lo notaria.
        memberships.grant(
                condominium, cuentaNueva().id(), Set.of(MembershipRole.OWNER), null, administradora.toString());
        UUID fila = laFilaDe("MEMBERSHIP_GRANTED").id();

        auditTrail.purge(clock.instant().minus(Duration.ofDays(365)));

        var sql = new JdbcTemplate(fuenteDeDatos);
        assertThatThrownBy(() -> sql.update("delete from audit_log where id = ?", fila))
                .hasMessageContaining("append-only");
    }

    // --- La IP y la traza, que no vienen del dominio -----------------------------------------

    @Test
    @DisplayName("la IP y la traza de la peticion acaban en la fila")
    void theRequestIpAndTraceAreRecorded() throws Exception {
        // 5.2 pide la IP en AuditLog, y ningun evento de dominio puede llevarla: un evento no sabe
        // de HTTP. Las pone el filtro de trazas en el contexto de log, y llegan al oyente --que
        // corre en OTRO HILO-- porque PropagacionDelMdc copia el MDC. Sin eso, las dos salen nulas y
        // el criterio no se cumple.
        memberships.grant(condominium, administradora, Set.of(MembershipRole.FINANCE_ADMIN), null, "admin");
        laFilaDe("MEMBERSHIP_GRANTED");

        mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        "/api/v1/condominiums/" + condominium + "/configuration")
                                .with(org.springframework.security.test.web.servlet.request
                                        .SecurityMockMvcRequestPostProcessors.csrf())
                                .with(org.springframework.security.test.web.servlet.request
                                        .SecurityMockMvcRequestPostProcessors.user(administradora.toString())
                                        .roles("FINANCE_ADMIN"))
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("{\"issueDay\":9,\"dueDay\":22}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk());

        AuditEntryView fila = laFilaDe("CONFIGURATION_CHANGED");
        assertThat(fila.ip()).isNotBlank();
        assertThat(fila.traceId()).isNotBlank();
    }
}
