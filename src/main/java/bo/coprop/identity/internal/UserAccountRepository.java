package bo.coprop.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    /** Una cuenta por persona, que es el titulo del issue #11. */
    boolean existsByPersonId(UUID personId);

    boolean existsByEmail(String email);

    /** Lo necesitara el issue #13 para resolver la identidad del token. */
    Optional<UserAccount> findByIdpSubject(String idpSubject);
}
