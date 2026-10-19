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
import org.jspecify.annotations.Nullable;

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

    /**
     * Donde esta. Nula mientras nadie la escriba: el alta no la pide (issue #90).
     *
     * <p>No forma parte de la identidad del condominio --eso es el NIT-- ni de ningun calculo. Es
     * dato para quien lo busca en una lista, y por eso se puede corregir sin mas ceremonia que el
     * nombre.
     */
    @Column(length = 200)
    private @Nullable String address;

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
        this.status = CondominiumStatus.ACTIVE;
        this.createdAt = createdAt;
    }

    /**
     * Corrige los datos descriptivos: nombre, NIT y direccion.
     *
     * <p>Los tres juntos y en un solo metodo para que no haya un camino que cambie el NIT sin pasar
     * por aqui. Lo que NO se toca desde el modelo es el tipo ni el estado: el tipo habria que
     * validarlo contra la estructura ya registrada --un BUILDING con bloques no cabe en HOUSES-- y
     * suspender un condominio es una accion de negocio con efectos sobre los QR vivos y la mora que
     * todavia no estan decididos. Mientras no lo esten, {@code CondominiumStatus.SUSPENDED} sigue
     * siendo un valor que nadie pone.
     *
     * @param address nula se admite y significa que no se sabe, no que se borre un dato correcto;
     *     para dejarla en blanco a proposito se manda nula igual, que es lo mismo que no tenerla
     */
    void edit(String name, String nit, @Nullable String address) {
        this.name = name;
        this.nit = nit;
        this.address = address;
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

    @Nullable
    String getAddress() {
        return address;
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
