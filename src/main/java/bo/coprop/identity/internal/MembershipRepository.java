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

    List<Membership> findByAccountIdAndValidToIsNull(UUID accountId);

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
            where m.accountId = :cuenta and m.validTo is null and m.status = bo.coprop.identity.MembershipStatus.ACTIVE
              and r in :roles
            """)
    boolean currentExistsWithAnyRole(@Param("cuenta") UUID account, @Param("roles") Collection<MembershipRole> roles);

    /**
     * Todas las del condominio, vigentes y cerradas, de la mas reciente a la mas antigua.
     *
     * <p>Por {@code validFrom} descendente y no por {@code validTo}: la vigente lo tiene nulo y en
     * PostgreSQL los nulos ordenan al final, asi que las actuales saldrian las ultimas, que es al
     * contrario de como se lee un padron. El identificador desempata para que dos membresias
     * concedidas el mismo instante --el alta de un condominio-- no cambien de sitio entre dos
     * consultas.
     */
    List<Membership> findByCondominiumIdOrderByValidFromDescIdDesc(UUID condominiumId);

    /**
     * Si en ese condominio hay <strong>otra</strong> membresia que de acceso y tenga ese rol.
     *
     * <p>Lo pregunta la baja y la suspension del issue #19 antes de dejar un condominio sin
     * administrador: una baja que se lleve al ultimo {@code CONDO_ADMIN} deja un edificio en el que
     * nadie puede invitar a nadie, y recuperarlo exige al superadministrador. Es mas facil impedirlo
     * que arreglarlo.
     *
     * <p>Exige {@code ACTIVE} ademas de vigente: una membresia suspendida no administra nada
     * mientras lo este, asi que contarla dejaria pasar justo el caso que esto evita.
     */
    @Query(
            """
            select case when count(m) > 0 then true else false end
            from Membership m join m.roles r
            where m.condominiumId = :condominio and m.validTo is null
              and m.status = bo.coprop.identity.MembershipStatus.ACTIVE
              and r = :rol and m.id <> :salvo
            """)
    boolean anotherCurrentExistsWithRole(
            @Param("condominio") UUID condominium, @Param("rol") MembershipRole rol, @Param("salvo") UUID salvo);
}
