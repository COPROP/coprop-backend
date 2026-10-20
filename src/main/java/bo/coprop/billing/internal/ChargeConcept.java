package bo.coprop.billing.internal;

import bo.coprop.billing.ChargeConceptType;
import bo.coprop.billing.PaymentGroup;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Un concepto de cobro del condominio: las expensas, el agua, la mora (analisis 5.2).
 *
 * <p>Es un catalogo y no una enumeracion porque el nombre y el codigo los pone cada condominio. Lo
 * que si es una enumeracion es su {@link ChargeConceptType}, que es lo que dice como se calcula.
 *
 * <p>No tiene estado ni fecha de baja: hoy no se retira un concepto, y el porque esta en
 * {@code ChargeConcepts}.
 */
@Entity
@Table(name = "charge_concept")
class ChargeConcept {

    @Id
    private UUID id;

    /** Nunca nulo: un concepto sin condominio no existe. Lo garantiza ademas la clave foranea. */
    @Column(name = "condominium_id", nullable = false)
    private UUID condominiumId;

    /** El codigo con el que el administrador lo nombra. Unico en su condominio. */
    @Column(nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChargeConceptType type;

    /**
     * Con que otros conceptos comparte QR.
     *
     * <p>Columna propia y no derivada del tipo: el porque esta en {@link PaymentGroup}, y se resume
     * en que la mora del agua tiene que cobrarse con el agua.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_group", nullable = false, length = 20)
    private PaymentGroup paymentGroup;

    protected ChargeConcept() {}

    ChargeConcept(UUID condominiumId, String code, String name, ChargeConceptType type, PaymentGroup paymentGroup) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.code = code;
        this.name = name;
        this.type = type;
        this.paymentGroup = paymentGroup;
    }

    UUID getId() {
        return id;
    }

    UUID getCondominiumId() {
        return condominiumId;
    }

    String getCode() {
        return code;
    }

    String getName() {
        return name;
    }

    ChargeConceptType getType() {
        return type;
    }

    PaymentGroup getPaymentGroup() {
        return paymentGroup;
    }
}
