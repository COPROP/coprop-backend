package bo.coprop.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Una cuenta con alcance de plataforma (Seguridad 3).
 *
 * <p>No es una membresia y por eso no vive en {@code membership}: la membresia existe para atar una
 * cuenta a un condominio, y este rol justamente no depende de ninguno.
 *
 * <p>La clave es la cuenta: o eres superadministrador o no, no hay grados ni duplicados.
 */
@Entity
@Table(name = "platform_admin")
class PlatformAdmin {

    @Id
    @Column(name = "user_id")
    private UUID accountId;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    /** Nulo solo en el primero, que no lo concede nadie: lo abre el arranque. */
    @Column(name = "granted_by")
    private @Nullable UUID grantedBy;

    protected PlatformAdmin() {}

    PlatformAdmin(UUID accountId, @Nullable UUID grantedBy, Instant grantedAt) {
        this.accountId = accountId;
        this.grantedBy = grantedBy;
        this.grantedAt = grantedAt;
    }

    UUID getAccountId() {
        return accountId;
    }
}
