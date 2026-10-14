package bo.coprop.identity.internal;

import bo.coprop.identity.MembershipRole;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Una invitacion a un condominio: de un solo uso, siete dias, y <strong>solo el hash del
 * token</strong> (Seguridad 6.1).
 *
 * <p>Apunta a una cuenta que ya existe, no a un correo suelto. Es el orden de 6.1: el administrador
 * registra primero a la persona y su relacion con la unidad, y despues invita. Lleva los roles que
 * concedera, porque es una membresia diferida: la membresia no existe hasta que alguien acepta.
 *
 * <p><strong>El token en claro no esta aqui ni en ningun sitio.</strong> Se genera una vez, viaja
 * al correo y se olvida; lo que queda es su SHA-256. El motivo no es ceremonia: esta misma base
 * guarda el registro de eventos, asi que un token en claro en una fila seria un acceso reutilizable
 * por quien pueda leerla.
 *
 * <p>Los tres cierres posibles --aceptada, revocada, vencida-- no son un estado almacenado. Los dos
 * primeros son una fecha, y el tercero se deduce del reloj: un estado {@code EXPIRADA} habria que
 * ponerlo con una tarea periodica y, entre que vence y que alguien la marque, la fila diria que
 * sigue viva. El diagrama de Seguridad 6 dibuja EXPIRADO en la vida de la <em>cuenta</em>; aqui lo
 * que vence es el token.
 */
@Entity
@Table(name = "invitation")
class Invitation {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false)
    private UUID condominiumId;

    @Column(name = "user_id", nullable = false)
    private UUID accountId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "invitation_role", joinColumns = @JoinColumn(name = "invitation_id"))
    @Column(name = "role", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Set<MembershipRole> roles = EnumSet.noneOf(MembershipRole.class);

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "accepted_at")
    private @Nullable Instant acceptedAt;

    @Column(name = "revoked_at")
    private @Nullable Instant revokedAt;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    protected Invitation() {}

    Invitation(
            UUID condominiumId,
            UUID accountId,
            String tokenHash,
            Set<MembershipRole> roles,
            UUID invitedBy,
            Instant createdAt,
            Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.accountId = accountId;
        this.tokenHash = tokenHash;
        // Copia defensiva y en EnumSet, igual que en Membership: el llamante puede seguir tocando
        // su conjunto.
        this.roles = EnumSet.copyOf(roles);
        this.invitedBy = invitedBy;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /**
     * Si todavia sirve: ni aceptada, ni revocada, ni vencida.
     *
     * <p>Recibe el instante en vez de preguntarlo. Es la regla de ARCHITECTURE.md --nadie llama a
     * {@code Instant.now()}-- y aqui tiene una consecuencia practica: sin ella no habria forma de
     * probar el vencimiento sin esperar siete dias.
     */
    boolean estaViva(Instant ahora) {
        return acceptedAt == null && revokedAt == null && expiresAt.isAfter(ahora);
    }

    /** Sella la invitacion. De un solo uso: a partir de aqui {@link #estaViva} dice que no. */
    void aceptar(Instant at) {
        this.acceptedAt = at;
    }

    void revocar(Instant at) {
        this.revokedAt = at;
    }

    UUID getId() {
        return id;
    }

    UUID getCondominiumId() {
        return condominiumId;
    }

    UUID getAccountId() {
        return accountId;
    }

    /** Inmodificable: los roles se fijan al emitir. Cambiarlos es revocar y emitir otra. */
    Set<MembershipRole> getRoles() {
        return Set.copyOf(roles);
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    @Nullable
    Instant getAcceptedAt() {
        return acceptedAt;
    }

    @Nullable
    Instant getRevokedAt() {
        return revokedAt;
    }

    UUID getInvitedBy() {
        return invitedBy;
    }
}
