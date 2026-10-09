package bo.coprop.condominium.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Piso. Cuelga de un bloque, no del condominio, tal como lo modela el analisis 5.2.
 *
 * <p>Consecuencia a tener presente: para usar pisos hay que crear al menos un bloque, aunque el
 * edificio sea uno solo. No se desvia del analisis por evitar esa incomodidad.
 */
@Entity
@Table(name = "floor")
class Floor {

    @Id
    private UUID id;

    @Column(name = "block_id", nullable = false)
    private UUID blockId;

    @Column(nullable = false)
    private int number;

    protected Floor() {}

    Floor(UUID blockId, int number) {
        this.id = UUID.randomUUID();
        this.blockId = blockId;
        this.number = number;
    }

    UUID getId() {
        return id;
    }

    UUID getBlockId() {
        return blockId;
    }

    int getNumber() {
        return number;
    }
}
