package bo.coprop.identity.internal;

import bo.coprop.identity.AccountStatus;
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
 * La credencial con la que se entra. Global y sin condominio (Seguridad 2).
 *
 * <p><strong>No guarda contraseña</strong>: eso es del proveedor de identidad. Lo que guarda es el
 * puente hacia el, {@code idpSubject}, que es el {@code sub} del token y no existe hasta el primer
 * login --lo rellenara el issue #13.
 *
 * <p>Una por persona, que es el titulo del issue #11. Lo impone un UNIQUE en la base.
 */
@Entity
@Table(name = "user_account")
class UserAccount {

    @Id
    private UUID id;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    /** El {@code sub} de Keycloak. Nulo mientras no haya entrado nunca. */
    @Column(name = "idp_subject", length = 64)
    private @Nullable String idpSubject;

    @Column(nullable = false, length = 160)
    private String email;

    @Column(length = 32)
    private @Nullable String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;

    @Column(name = "last_access_at")
    private @Nullable Instant lastAccessAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserAccount() {}

    UserAccount(UUID personId, String email, @Nullable String phone, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.personId = personId;
        this.email = email;
        this.phone = phone;
        // Tiene cuenta y no ha entrado nunca. El envio de la invitacion es el #18.
        this.status = AccountStatus.INVITADO;
        this.mfaEnabled = false;
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    UUID getPersonId() {
        return personId;
    }

    @Nullable
    String getIdpSubject() {
        return idpSubject;
    }

    String getEmail() {
        return email;
    }

    @Nullable
    String getPhone() {
        return phone;
    }

    AccountStatus getStatus() {
        return status;
    }

    boolean isMfaEnabled() {
        return mfaEnabled;
    }

    @Nullable
    Instant getLastAccessAt() {
        return lastAccessAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
