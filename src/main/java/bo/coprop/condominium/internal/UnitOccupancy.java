package bo.coprop.condominium.internal;

import bo.coprop.condominium.OccupancyRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Quien vive en una unidad, y en calidad de que. Con vigencia.
 *
 * <p>Separada de la titularidad porque son cosas distintas: el propietario que alquila no ocupa, y
 * el inquilino ocupa sin ser propietario.
 */
@Entity
@Table(name = "unit_occupancy")
class UnitOccupancy {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false)
    private UUID condominiumId;

    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OccupancyRole role;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private @Nullable Instant validTo;

    protected UnitOccupancy() {}

    UnitOccupancy(UUID condominiumId, UUID unitId, UUID personId, OccupancyRole role, Instant validFrom) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.unitId = unitId;
        this.personId = personId;
        this.role = role;
        this.validFrom = validFrom;
    }

    /** Cierra la ocupacion: se mudo. La usa la mudanza y la venta de la unidad (#19). */
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

    OccupancyRole getRole() {
        return role;
    }

    Instant getValidFrom() {
        return validFrom;
    }

    @Nullable
    Instant getValidTo() {
        return validTo;
    }
}
