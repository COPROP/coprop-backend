package bo.coprop.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    /** Una cuenta por persona, que es el titulo del issue #11. */
    boolean existsByPersonId(UUID personId);

    boolean existsByEmail(String email);

    /** Por donde entra quien ya se vinculo: el camino de todo login que no es el primero. */
    Optional<UserAccount> findByIdpSubject(String idpSubject);

    /**
     * Por donde entra el primer login, que todavia no tiene {@code idpSubject}.
     *
     * <p>El correo se compara en minusculas porque {@code UNIQUE (email)} distingue mayusculas y
     * el proveedor de identidad no promete como las escribe.
     */
    Optional<UserAccount> findByEmailIgnoreCase(String email);
}
