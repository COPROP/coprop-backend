package bo.coprop.condominium.internal;

import bo.coprop.condominium.UnitStatus;
import bo.coprop.condominium.UnitType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Una unidad del condominio: el departamento, la casa, el parqueo (analisis 5.2).
 *
 * <p>Es la unidad de cobro: las obligaciones se emiten contra ella, no contra la persona, porque
 * la deuda sigue a la unidad cuando cambia de dueño.
 */
@Entity
@Table(name = "unit")
class Unit {

    @Id
    private UUID id;

    /** Nunca nulo: una unidad sin condominio no existe. Lo garantiza ademas la clave foranea. */
    @Column(name = "condominium_id", nullable = false)
    private UUID condominiumId;

    /** El identificador visible, el que usa la gente: "302", "Casa 7". Unico en su condominio. */
    @Column(nullable = false, length = 32)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UnitType type;

    /**
     * Porcentaje de copropiedad con el que se reparten las expensas.
     *
     * <p>{@code NUMERIC(9,6)} y {@code BigDecimal}, nunca {@code double} (analisis 12.2). Seis
     * decimales porque en un edificio de 200 unidades redondear a dos deja varios bolivianos sin
     * repartir cada mes.
     */
    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal aliquot;

    /** Opcional: de un parqueo o una baulera no siempre se conoce. */
    @Column(name = "area_m2", precision = 10, scale = 2)
    private @Nullable BigDecimal areaM2;

    @Column(name = "block_id")
    private @Nullable UUID blockId;

    @Column(name = "floor_id")
    private @Nullable UUID floorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UnitStatus status;

    protected Unit() {}

    Unit(
            UUID condominiumId,
            String code,
            UnitType type,
            BigDecimal aliquot,
            @Nullable BigDecimal areaM2,
            @Nullable UUID blockId,
            @Nullable UUID floorId) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.code = code;
        this.type = type;
        this.aliquot = aliquot;
        this.areaM2 = areaM2;
        this.blockId = blockId;
        this.floorId = floorId;
        this.status = UnitStatus.ACTIVA;
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

    UnitType getType() {
        return type;
    }

    BigDecimal getAliquot() {
        return aliquot;
    }

    @Nullable
    BigDecimal getAreaM2() {
        return areaM2;
    }

    @Nullable
    UUID getBlockId() {
        return blockId;
    }

    @Nullable
    UUID getFloorId() {
        return floorId;
    }

    UnitStatus getStatus() {
        return status;
    }
}
