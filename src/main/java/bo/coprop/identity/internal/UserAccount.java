package bo.coprop.identity.internal;

import bo.coprop.identity.AccountStatus;
import bo.coprop.identity.AccountView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * La credencial con la que se entra. Global y sin condominio (Seguridad 2).
 *
 * <p><strong>No guarda contraseña</strong>: eso es del proveedor de identidad. Lo que guarda es el
 * puente hacia el, {@code idpSubject}, que es el {@code sub} del token y no existe hasta el primer
 * login: lo rellena {@code vincular}, que invoca el filtro de identidad del issue #13.
 *
 * <p>Una por persona, que es el titulo del issue #11. Lo impone un UNIQUE en la base.
 */
@Entity
@Table(name = "user_account")
class UserAccount {

    private static final int MINUTOS_ENTRE_ACCESOS = 15;

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

    /** Lo que de esta cuenta se puede enseñar. Sin {@code idpSubject}: es un puente interno. */
    AccountView vista() {
        return new AccountView(id, personId, email, phone, status, mfaEnabled);
    }

    /**
     * Primer login: ata la cuenta al sujeto del proveedor de identidad y la activa.
     *
     * <p>El vinculo es para siempre. Si alguna vez hiciera falta rehacerlo --una cuenta de Keycloak
     * recreada-- tendra que ser una operacion explicita y no un efecto de entrar. <strong>No tiene
     * ticket todavia</strong>: se le atribuia al issue #19, que resulto ser de la membresia y no de
     * la cuenta, y de momento nadie lo ha pedido.
     */
    void vincular(String idpSubject, Instant cuando) {
        this.idpSubject = idpSubject;
        this.status = AccountStatus.ACTIVO;
        this.lastAccessAt = cuando;
    }

    /**
     * Cada acceso posterior. Solo mueve la fecha: no publica evento ni cambia el estado.
     *
     * <p><strong>No la mueve en cada peticion.</strong> La identidad se resuelve una vez por
     * request, asi que escribir la fecha siempre seria un UPDATE por peticion sobre la fila de cada
     * usuario conectado --y por un dato que a nadie le importa al minuto--. Con la ventana, el coste
     * cae a un UPDATE cada {@value #MINUTOS_ENTRE_ACCESOS} minutos por cuenta y el dato sigue
     * sirviendo para lo que se guarda: saber si alguien entra o no entra.
     */
    void registrarAcceso(Instant cuando) {
        if (lastAccessAt == null || lastAccessAt.isBefore(cuando.minus(Duration.ofMinutes(MINUTOS_ENTRE_ACCESOS)))) {
            this.lastAccessAt = cuando;
        }
    }

    /**
     * Si esta cuenta puede entrar hoy.
     *
     * <p>{@code INVITADO} si puede: es justo quien no ha entrado nunca. Lo que no puede es una
     * cuenta bloqueada --por seguridad, se desbloquea-- ni una dada de baja, que no vuelve.
     */
    boolean puedeEntrar() {
        return status == AccountStatus.INVITADO || status == AccountStatus.ACTIVO;
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
