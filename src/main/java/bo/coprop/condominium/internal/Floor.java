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
 *
 * <p>Lleva {@code condominiumId} aunque se pueda deducir subiendo al bloque, porque la seccion 6
 * del analisis lo exige en toda tabla de negocio y porque una politica de Row-Level Security
 * (analisis 12.2) necesita la columna en la propia fila: no puede salir a buscarla con un join.
 * La clave foranea de la base es compuesta, {@code (block_id, condominium_id)}, de modo que un
 * piso no puede declararse de un condominio distinto del de su bloque.
 */
@Entity
@Table(name = "floor")
class Floor {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false)
    private UUID condominiumId;

    @Column(name = "block_id", nullable = false)
    private UUID blockId;

    @Column(nullable = false)
    private int number;

    protected Floor() {}

    Floor(UUID condominiumId, UUID blockId, int number) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.blockId = blockId;
        this.number = number;
    }

    UUID getId() {
        return id;
    }

    UUID getCondominiumId() {
        return condominiumId;
    }

    UUID getBlockId() {
        return blockId;
    }

    int getNumber() {
        return number;
    }
}
