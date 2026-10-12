package bo.coprop.identity.internal;

import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.MembershipStatus;
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
 * El vinculo entre una cuenta y un condominio, <strong>con vigencia</strong> (Seguridad 2).
 *
 * <p>Cuelga de la cuenta y no de la persona. La consecuencia practica es que un propietario que
 * nunca se registro no tiene membresia, y no le hace falta: existe como persona con su titularidad
 * desde que se carga el edificio.
 *
 * <p>No se borra: cerrar la fila con {@code validTo} deja la historia consultable, que es lo que
 * necesita el issue #19. Y por eso no hay un estado "terminada": lo dice la vigencia. Un estado y
 * una vigencia que signifiquen lo mismo acaban contradiciendose.
 *
 * <p>Los roles van como {@link ElementCollection} sobre {@code membership_role} y no como entidad
 * aparte: la tabla no tiene mas columnas que la pareja, asi que una entidad con clave compuesta
 * solo añadiria ceremonia. Se cargan con la membresia --{@code EAGER}-- porque no hay membresia
 * util sin ellos: toda decision de autorizacion los necesita, y dejarlos perezosos solo invita a
 * un {@code LazyInitializationException} fuera de la transaccion.
 */
@Entity
@Table(name = "membership")
class Membership {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID accountId;

    @Column(name = "condominium_id", nullable = false)
    private UUID condominiumId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipStatus status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "membership_role", joinColumns = @JoinColumn(name = "membership_id"))
    @Column(name = "role", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Set<MembershipRole> roles = EnumSet.noneOf(MembershipRole.class);

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    /** Nulo mientras sea la vigente. Solo puede haber una por cuenta y condominio. */
    @Column(name = "valid_to")
    private @Nullable Instant validTo;

    /** La cuenta que invito. Nulo en la del primer administrador, que no la invita nadie. */
    @Column(name = "invited_by")
    private @Nullable UUID invitedBy;

    protected Membership() {}

    Membership(
            UUID condominiumId,
            UUID accountId,
            Set<MembershipRole> roles,
            @Nullable UUID invitedBy,
            Instant validFrom) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.accountId = accountId;
        this.status = MembershipStatus.ACTIVA;
        // Copia defensiva y en EnumSet: el llamante puede seguir tocando su conjunto.
        this.roles = EnumSet.copyOf(roles);
        this.invitedBy = invitedBy;
        this.validFrom = validFrom;
    }

    /** Cierra la membresia. A partir de aqui la fila es historia. Lo usara el issue #19. */
    void close(Instant at) {
        this.validTo = at;
    }

    UUID getId() {
        return id;
    }

    UUID getAccountId() {
        return accountId;
    }

    UUID getCondominiumId() {
        return condominiumId;
    }

    MembershipStatus getStatus() {
        return status;
    }

    /** Inmodificable: los roles se fijan al conceder, y cambiarlos es el issue #20. */
    Set<MembershipRole> getRoles() {
        return Set.copyOf(roles);
    }

    Instant getValidFrom() {
        return validFrom;
    }

    @Nullable
    Instant getValidTo() {
        return validTo;
    }

    @Nullable
    UUID getInvitedBy() {
        return invitedBy;
    }
}
