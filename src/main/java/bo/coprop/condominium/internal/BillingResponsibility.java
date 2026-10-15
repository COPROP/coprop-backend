package bo.coprop.condominium.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Quien responde por los cobros de una unidad. Con vigencia.
 *
 * <p>Es la tercera relacion y la razon de que no haya una sola tabla con columna de tipo: en un
 * alquiler corriente el inquilino ocupa y el propietario sigue pagando. Seguridad 2 la lee asi:
 * "un inquilino, solo los servicios de los que figura como responsable en BillingResponsibility
 * vigente".
 */
@Entity
@Table(name = "billing_responsibility")
class BillingResponsibility {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false)
    private UUID condominiumId;

    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    /**
     * Nulo = responde por todo lo que se cobre a la unidad. Con valor, solo por ese concepto.
     *
     * <p>Sin clave foranea todavia: {@code charge_concept} nace en M2 (analisis 5.2) y la
     * restriccion entra con el.
     */
    @Column(name = "concept_id")
    private @Nullable UUID conceptId;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private @Nullable Instant validTo;

    protected BillingResponsibility() {}

    BillingResponsibility(UUID condominiumId, UUID unitId, UUID personId, @Nullable UUID conceptId, Instant validFrom) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.unitId = unitId;
        this.personId = personId;
        this.conceptId = conceptId;
        this.validFrom = validFrom;
    }

    /** Cierra la responsabilidad: ya no le toca pagar. La usa la venta de la unidad y el fin de una responsabilidad (#19). */
    void close(Instant at) {
        this.validTo = at;
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

    UUID getPersonId() {
        return personId;
    }

    @Nullable
    UUID getConceptId() {
        return conceptId;
    }

    Instant getValidFrom() {
        return validFrom;
    }

    @Nullable
    Instant getValidTo() {
        return validTo;
    }
}
