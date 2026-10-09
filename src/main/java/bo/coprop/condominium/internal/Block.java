package bo.coprop.condominium.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Torre o bloque. Opcional (analisis 5.2): un condominio pequeño puede no tener ninguno. */
@Entity
@Table(name = "block")
class Block {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false)
    private UUID condominiumId;

    @Column(nullable = false, length = 80)
    private String name;

    protected Block() {}

    Block(UUID condominiumId, String name) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.name = name;
    }

    UUID getId() {
        return id;
    }

    UUID getCondominiumId() {
        return condominiumId;
    }

    String getName() {
        return name;
    }
}
