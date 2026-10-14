package bo.coprop.identity.internal;

import bo.coprop.identity.MembershipRole;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MembershipRepository extends JpaRepository<Membership, UUID> {

    /** La vigente es la unica sin fecha de cierre. Lo garantiza un indice parcial. */
    Optional<Membership> findByCondominiumIdAndAccountIdAndValidToIsNull(UUID condominiumId, UUID accountId);

    boolean existsByCondominiumIdAndAccountIdAndValidToIsNull(UUID condominiumId, UUID accountId);

    List<Membership> findByCondominiumIdAndValidToIsNull(UUID condominiumId);

    /**
     * Si esa cuenta tiene alguna membresia vigente con alguno de esos roles, en el condominio que
     * sea.
     *
     * <p>Lo necesitan las rutas que no cuelgan de un condominio --el alta de una persona-- donde no
     * hay autoridades de condominio que mirar. Es un EXISTS y no una lista: lo unico que se
     * pregunta es si hay alguna.
     */
    @Query(
            """
            select case when count(m) > 0 then true else false end
            from Membership m join m.roles r
            where m.accountId = :cuenta and m.validTo is null and m.status = bo.coprop.identity.MembershipStatus.ACTIVA
              and r in :roles
            """)
    boolean existeVigenteConAlgunRol(@Param("cuenta") UUID cuenta, @Param("roles") Collection<MembershipRole> roles);
}
