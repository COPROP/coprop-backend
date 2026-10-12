package bo.coprop.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * La persona fisica, separada de la cuenta con la que entra (Seguridad 2).
 *
 * <p>La cuenta es una credencial que se bloquea, se desactiva o todavia no existe; la persona es un
 * hecho del mundo y no deja de serlo porque nunca se registre. Esa separacion es lo que permite
 * cargar el edificio entero antes de invitar a nadie.
 *
 * <p><strong>No lleva condominio.</strong> RF-03 pide que la misma persona sea propietaria en un
 * condominio e inquilina en otro, asi que la fila se comparte. Es la excepcion a la regla de que
 * toda tabla de negocio lleva {@code condominium_id}, y esta declarada tambien en la migracion.
 */
@Entity
@Table(name = "person")
class Person {

    @Id
    private UUID id;

    @Column(name = "first_names", nullable = false, length = 120)
    private String firstNames;

    @Column(name = "last_names", nullable = false, length = 120)
    private String lastNames;

    /** Cedula de identidad. Nulo cuando no se conoce; unico cuando viene. */
    @Column(name = "national_id", length = 20)
    private @Nullable String nationalId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Person() {}

    Person(String firstNames, String lastNames, @Nullable String nationalId, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.firstNames = firstNames;
        this.lastNames = lastNames;
        this.nationalId = nationalId;
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    String getFirstNames() {
        return firstNames;
    }

    String getLastNames() {
        return lastNames;
    }

    @Nullable
    String getNationalId() {
        return nationalId;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
