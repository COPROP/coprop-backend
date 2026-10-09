package bo.coprop.condominium.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Configuracion de facturacion de un condominio, <strong>con vigencia</strong>.
 *
 * <p>Nunca se actualiza en sitio: cambiarla cierra la fila vigente poniendole {@code validTo} y
 * abre otra. Es el mismo patron que el analisis 5.2 usa para {@code LateFeePolicy} y
 * {@code WaterTariff}, y es lo que hace cierto el segundo criterio del issue #9: cambiar la
 * configuracion no puede alterar obligaciones ya emitidas, porque la configuracion con la que se
 * emitieron sigue ahi.
 *
 * <p>Lo que falta para cerrarlo del todo llega con M2: que la obligacion apunte a la version
 * vigente cuando se emitio. Hoy no hay obligaciones.
 *
 * <p><strong>Solo contiene lo que no tiene entidad propia.</strong> RF-01 habla de la
 * configuracion del condominio incluyendo mora, agua y reservas, pero el analisis 5.2 modela esas
 * tres como entidades con su propia vigencia: {@code LateFeePolicy} y {@code WaterTariff} llegan
 * en M2, {@code CommonArea} en M3. Meterlas aqui seria modelarlas dos veces.
 */
@Entity
@Table(name = "condominium_config")
class CondominiumConfig {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false)
    private UUID condominiumId;

    /**
     * Dia del mes en que se emiten las obligaciones.
     *
     * <p>Limitado a 28 en la base y en la validacion: un condominio que emitiera el 30 no emitiria
     * en febrero, y esa clase de agujero solo se descubre en produccion y en febrero.
     */
    @Column(name = "issue_day", nullable = false)
    private int issueDay;

    /** Dia del mes en que vencen. Mismo limite de 28 y por la misma razon. */
    @Column(name = "due_day", nullable = false)
    private int dueDay;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    /** Nulo mientras sea la vigente. Solo puede haber una por condominio con este campo nulo. */
    @Column(name = "valid_to")
    private @Nullable Instant validTo;

    protected CondominiumConfig() {}

    CondominiumConfig(UUID condominiumId, int issueDay, int dueDay, Instant validFrom) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.issueDay = issueDay;
        this.dueDay = dueDay;
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

    int getIssueDay() {
        return issueDay;
    }

    int getDueDay() {
        return dueDay;
    }

    Instant getValidFrom() {
        return validFrom;
    }

    @Nullable
    Instant getValidTo() {
        return validTo;
    }
}
