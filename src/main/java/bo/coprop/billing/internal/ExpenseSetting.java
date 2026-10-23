package bo.coprop.billing.internal;

import bo.coprop.billing.ExpenseMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Como se calculan las expensas de un concepto, <strong>con vigencia</strong>.
 *
 * <p>Nunca se actualiza en sitio: cambiarla cierra la fila vigente poniendole {@code validTo} y abre
 * otra. Es el mismo patron que {@code CondominiumConfig} y el que el analisis 5.2 reserva para
 * {@code WaterTariff} y {@code LateFeePolicy}, y es lo que hace que lo ya emitido no cambie cuando
 * cambia el monto: la fila con la que se emitio sigue ahi, y cada obligacion la nombra en su
 * {@code sourceId}.
 *
 * <p><strong>Sin maquina de estados</strong>, al contrario que las otras tres entidades del modulo:
 * una configuracion no transita, se reemplaza.
 */
@Entity
@Table(name = "expense_setting")
class ExpenseSetting {

    /** El valor de {@code sourceType} con el que la obligacion de expensas apunta aqui. */
    static final String SOURCE_TYPE = "EXPENSE_SETTING";

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false)
    private UUID condominiumId;

    @Column(name = "concept_id", nullable = false)
    private UUID conceptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExpenseMethod method;

    /**
     * El monto, y lo que significa lo dice {@link #method}.
     *
     * <p>Con {@code FIXED_PER_UNIT} es lo que paga cada unidad; con {@code BY_ALIQUOT} es el total a
     * repartir. Una sola columna y no dos alternas: el porque esta en la V14.
     */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** La moneda del condominio, copiada al configurar. El analisis 12.2 la exige en cada monto. */
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    /** Nulo mientras sea la vigente. Solo puede haber una por condominio y concepto con este nulo. */
    @Column(name = "valid_to")
    private @Nullable Instant validTo;

    protected ExpenseSetting() {}

    ExpenseSetting(
            UUID condominiumId,
            UUID conceptId,
            ExpenseMethod method,
            BigDecimal amount,
            String currency,
            Instant validFrom) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.conceptId = conceptId;
        this.method = method;
        this.amount = amount;
        this.currency = currency;
        this.validFrom = validFrom;
    }

    /** Cierra esta version. A partir de aqui la fila es historia y no se vuelve a tocar. */
    void close(Instant at) {
        this.validTo = at;
    }

    UUID getId() {
        return id;
    }

    UUID getCondominiumId() {
        return condominiumId;
    }

    UUID getConceptId() {
        return conceptId;
    }

    ExpenseMethod getMethod() {
        return method;
    }

    BigDecimal getAmount() {
        return amount;
    }

    String getCurrency() {
        return currency;
    }

    Instant getValidFrom() {
        return validFrom;
    }

    @Nullable
    Instant getValidTo() {
        return validTo;
    }
}
