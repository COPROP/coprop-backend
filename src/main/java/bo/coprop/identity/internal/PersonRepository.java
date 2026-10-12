package bo.coprop.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PersonRepository extends JpaRepository<Person, UUID> {

    /**
     * La persona con ese carnet, si la hay.
     *
     * <p>Es lo que hace posible que el alta responda igual exista o no: se reutiliza la fila en vez
     * de chocar con el indice unico y delatar que ya estaba.
     */
    Optional<Person> findByNationalId(String nationalId);
}
