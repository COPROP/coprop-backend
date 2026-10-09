package bo.coprop.condominium.internal;

import bo.coprop.condominium.CondominiumStatus;
import bo.coprop.condominium.CondominiumType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Raiz del modelo multi-tenant (analisis 5.2).
 *
 * <p>Todo dato del sistema cuelga de un condominio, directa o indirectamente, y el
 * {@code condominium_id} es obligatorio en cualquier consulta (analisis 12.2). El aislamiento de
 * verdad lo impone el issue <strong>#17</strong>; esta entidad solo es la raiz de la que colgara.
 *
 * <p>La moneda se guarda aunque hoy sea siempre BOB: el analisis 12.2 exige moneda explicita en
 * cada monto, y un sistema de cobros que asume la moneda es el que un dia suma bolivianos con
 * dolares sin avisar.
 */
@Entity
@Table(name = "condominium")
class Condominium {

    @Id
    private UUID id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 20)
    private String nit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CondominiumType type;

    /** Zona horaria IANA. De ella dependen los vencimientos y el calculo de mora. */
    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CondominiumStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA necesita un constructor sin argumentos; nadie mas debe usarlo. */
    protected Condominium() {}

    Condominium(String name, String nit, CondominiumType type, String timeZone, String currency, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.nit = nit;
        this.type = type;
        this.timeZone = timeZone;
        this.currency = currency;
        this.status = CondominiumStatus.ACTIVO;
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getNit() {
        return nit;
    }

    CondominiumType getType() {
        return type;
    }

    String getTimeZone() {
        return timeZone;
    }

    String getCurrency() {
        return currency;
    }

    CondominiumStatus getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
