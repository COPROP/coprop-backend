package bo.coprop.billing.internal;

import bo.coprop.billing.PaymentObligationStatus;
import bo.coprop.shared.StateConflict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Lo que una unidad debe por un concepto: la entidad central del sistema (analisis 5.1 a 5.3).
 *
 * <p><strong>La maquina de estados vive aqui</strong>, igual que en el periodo de facturacion y por
 * la misma razon: no depende mas que del estado actual, asi que puesta en la entidad vale igual
 * llamada desde la emision del #24, desde el registro de un pago del #43 o desde un worker, y no hay
 * forma de cambiar el estado sin pasar por ella.
 *
 * <p><strong>No guarda saldo</strong>: en el MVP no hay pagos parciales, asi que el saldo seria
 * siempre el monto o cero. Tampoco guarda si esta vencida --{@link #isOverdue}-- porque vencer es un
 * hecho de la fecha y guardarlo obligaria a recorrer la tabla cada noche.
 */
@Entity
@Table(name = "payment_obligation")
class PaymentObligation {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false)
    private UUID condominiumId;

    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    /**
     * Quien responde por esta deuda, congelado al crearla.
     *
     * <p>No se relee de {@code billing_responsibility} al consultarla: si la unidad se vende, lo ya
     * emitido sigue siendo del vendedor. Es la misma regla que hace que nada se reescriba aqui.
     */
    @Column(name = "responsible_person_id", nullable = false)
    private UUID responsiblePersonId;

    @Column(name = "concept_id", nullable = false)
    private UUID conceptId;

    /** Nulo cuando la deuda no es de un mes: una multa o una reserva no tienen periodo. */
    @Column(name = "period_id")
    private @Nullable UUID periodId;

    /** El nombre legible del analisis 5.2. Lo compone el servicio; aqui llega hecho. */
    @Column(nullable = false, length = 80)
    private String number;

    /** El origen por referencia del analisis 5.1. Vacios hasta el #24; el porque esta en la V13. */
    @Column(name = "source_type", length = 30)
    private @Nullable String sourceType;

    @Column(name = "source_id")
    private @Nullable UUID sourceId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** La moneda del condominio, copiada al crear. El analisis 12.2 la exige en cada monto. */
    @Column(nullable = false, length = 3)
    private String currency;

    /** Nula mientras es un borrador. Es lo que distingue un calculo de una deuda cobrable. */
    @Column(name = "issued_at")
    private @Nullable Instant issuedAt;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentObligationStatus status;

    /** La obligacion que genero esta, cuando esto es mora (analisis 5.2). Nulo en las demas. */
    @Column(name = "parent_obligation_id")
    private @Nullable UUID parentObligationId;

    protected PaymentObligation() {}

    PaymentObligation(
            UUID condominiumId,
            UUID unitId,
            UUID responsiblePersonId,
            UUID conceptId,
            @Nullable UUID periodId,
            String number,
            BigDecimal amount,
            String currency,
            LocalDate dueDate,
            @Nullable UUID parentObligationId,
            @Nullable String sourceType,
            @Nullable UUID sourceId) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.unitId = unitId;
        this.responsiblePersonId = responsiblePersonId;
        this.conceptId = conceptId;
        this.periodId = periodId;
        this.number = number;
        this.amount = amount;
        this.currency = currency;
        this.dueDate = dueDate;
        this.parentObligationId = parentObligationId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        // El estado no es un parametro: toda obligacion nace borrador y sale a cobro al emitirse.
        this.status = PaymentObligationStatus.DRAFT;
    }

    /**
     * La saca a cobro y le pone la fecha de emision.
     *
     * @throws StateConflict si no estaba en {@code DRAFT}
     */
    void issue(Instant now) {
        exigir(PaymentObligationStatus.DRAFT, "se emite");
        this.status = PaymentObligationStatus.ISSUED;
        this.issuedAt = now;
    }

    /**
     * La marca pagada.
     *
     * <p>Desde {@code ISSUED} y desde {@code IN_AGREEMENT}: una deuda en convenio se acaba de pagar
     * igual, y el convenio es una forma de cobrarla, no otra deuda.
     *
     * @throws StateConflict si es un borrador, si ya estaba pagada o si estaba anulada
     */
    void pay() {
        if (status != PaymentObligationStatus.ISSUED && status != PaymentObligationStatus.IN_AGREEMENT) {
            throw new StateConflict(
                    "La obligacion %s esta en %s y solo se paga una emitida o en convenio.".formatted(number, status));
        }
        this.status = PaymentObligationStatus.PAID;
    }

    /**
     * La anula.
     *
     * <p>Desde {@code DRAFT} --el borrador descartado-- y desde {@code ISSUED} o
     * {@code IN_AGREEMENT}. <strong>Nunca desde {@code PAID}</strong>: eso es revertir un pago, que
     * mueve dinero y es del #43. Es el criterio del ticket, y el mensaje lo dice asi para que quien
     * lo reciba sepa que tiene que hacer en vez de reintentar.
     *
     * @throws StateConflict si ya estaba anulada, o si esta pagada
     */
    void cancel() {
        if (status == PaymentObligationStatus.PAID) {
            throw new StateConflict(
                    "La obligacion %s esta pagada y una pagada no se anula: se revierte el pago.".formatted(number));
        }
        if (status == PaymentObligationStatus.CANCELLED) {
            throw new StateConflict("La obligacion %s ya estaba anulada.".formatted(number));
        }
        this.status = PaymentObligationStatus.CANCELLED;
    }

    /**
     * La mete en un convenio de pago.
     *
     * <p>Solo desde {@code ISSUED}: un borrador no se negocia --no existe para el propietario-- y una
     * pagada no tiene nada que convenir.
     *
     * @throws StateConflict si no estaba emitida
     */
    void putUnderAgreement() {
        exigir(PaymentObligationStatus.ISSUED, "entra en convenio");
        this.status = PaymentObligationStatus.IN_AGREEMENT;
    }

    /**
     * Si a esa fecha ya habia vencido.
     *
     * <p><strong>Derivado, nunca guardado</strong> (analisis 5.3): una vencida sigue siendo
     * {@code ISSUED} y sigue cobrable. La fecha se recibe en vez de leerse del reloj para que el
     * calculo se haga en la zona del condominio, que es donde vence --el analisis 12.2 lo pide-- y
     * para que un test pueda preguntar por cualquier dia.
     *
     * <p>Solo una emitida vence. Una en convenio no se cuenta aqui: su cobro lo gobierna el convenio,
     * y si eso la hace candidata a mora o no lo decide el #27, no esta linea.
     */
    boolean isOverdue(LocalDate today) {
        return status == PaymentObligationStatus.ISSUED && dueDate.isBefore(today);
    }

    private void exigir(PaymentObligationStatus esperado, String accion) {
        if (status != esperado) {
            throw new StateConflict(
                    "La obligacion %s esta en %s y solo %s una en %s.".formatted(number, status, accion, esperado));
        }
    }

    UUID getId() {
        return id;
    }

    UUID getCondominiumId() {
        return condominiumId;
    }

    UUID getUnitId() {
        return unitId;
    }

    UUID getResponsiblePersonId() {
        return responsiblePersonId;
    }

    UUID getConceptId() {
        return conceptId;
    }

    @Nullable
    UUID getPeriodId() {
        return periodId;
    }

    String getNumber() {
        return number;
    }

    @Nullable
    String getSourceType() {
        return sourceType;
    }

    @Nullable
    UUID getSourceId() {
        return sourceId;
    }

    BigDecimal getAmount() {
        return amount;
    }

    String getCurrency() {
        return currency;
    }

    @Nullable
    Instant getIssuedAt() {
        return issuedAt;
    }

    LocalDate getDueDate() {
        return dueDate;
    }

    PaymentObligationStatus getStatus() {
        return status;
    }

    @Nullable
    UUID getParentObligationId() {
        return parentObligationId;
    }
}
