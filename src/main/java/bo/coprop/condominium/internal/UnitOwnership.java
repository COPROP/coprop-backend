package bo.coprop.condominium.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Quien es propietario de que unidad, y en que porcentaje. Con vigencia.
 *
 * <p>La unidad se referencia por identificador y no con {@code @ManyToOne}: este modulo no depende
 * de {@code condominium} (regla 2 de ARCHITECTURE.md). En la base la clave foranea es compuesta,
 * {@code (unit_id, condominium_id)}, de modo que una titularidad no puede declararse de un
 * condominio distinto del de su unidad.
 *
 * <p>Varias filas vigentes por unidad: la copropiedad existe. La misma persona dos veces, no.
 */
@Entity
@Table(name = "unit_ownership")
class UnitOwnership {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false)
    private UUID condominiumId;

    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    /**
     * Porcentaje de copropiedad. Seis decimales y {@code BigDecimal}, nunca coma flotante
     * (analisis 12.2), por lo mismo que la alicuota de la unidad.
     */
    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal share;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private @Nullable Instant validTo;

    protected UnitOwnership() {}

    UnitOwnership(UUID condominiumId, UUID unitId, UUID personId, BigDecimal share, Instant validFrom) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.unitId = unitId;
        this.personId = personId;
        this.share = share;
        this.validFrom = validFrom;
    }

    /** Cierra la titularidad: la unidad cambio de dueño. La usa la venta de la unidad (#19). */
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

    BigDecimal getShare() {
        return share;
    }

    Instant getValidFrom() {
        return validFrom;
    }

    @Nullable
    Instant getValidTo() {
        return validTo;
    }
}
