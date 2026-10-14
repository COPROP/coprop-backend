package bo.coprop.identity.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    /**
     * La invitacion de un token, buscada por su hash.
     *
     * <p>No filtra por viva: quien llama necesita distinguir "no existe ningun token asi" de "existe
     * y ya no sirve" para registrarlo en el log, aunque las dos respondan lo mismo al cliente.
     */
    Optional<Invitation> findByTokenHash(String tokenHash);

    /**
     * La invitacion viva de una cuenta en un condominio, si la hay.
     *
     * <p>Mira las dos columnas de cierre y no el vencimiento: una vencida sigue siendo la fila que
     * el indice parcial considera abierta, asi que es la que un reenvio tiene que revocar. Si
     * filtrara tambien por fecha, el reenvio intentaria insertar una segunda fila abierta y
     * chocaria con {@code invitation_viva_uq}.
     */
    Optional<Invitation> findByCondominiumIdAndAccountIdAndAcceptedAtIsNullAndRevokedAtIsNull(
            UUID condominiumId, UUID accountId);

    List<Invitation> findByCondominiumIdOrderByCreatedAtDesc(UUID condominiumId);
}
