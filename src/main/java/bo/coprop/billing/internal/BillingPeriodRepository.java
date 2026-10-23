package bo.coprop.billing.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface BillingPeriodRepository extends JpaRepository<BillingPeriod, UUID> {

    boolean existsByCondominiumIdAndYearAndMonth(UUID condominiumId, int year, int month);

    /**
     * El periodo de un mes, si esta abierto.
     *
     * <p>La pide la apertura idempotente que necesita la emision del #24: lo unico que distingue a
     * esa del alta normal es que mira antes en vez de chocar.
     */
    Optional<BillingPeriod> findByCondominiumIdAndYearAndMonth(UUID condominiumId, int year, int month);

    /**
     * El periodo, pero solo si es de ese condominio.
     *
     * <p>El condominio va en la consulta y no se comprueba despues de leer: es la misma forma que
     * usan las consultas de la unidad, y lo que hace que un periodo ajeno no exista en vez
     * de existir y ser rechazado. La diferencia se ve en la respuesta --404 y no 403-- que es lo que
     * pide Seguridad 7.2.
     */
    Optional<BillingPeriod> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    /**
     * Los periodos del condominio, del mas reciente al mas antiguo.
     *
     * <p>Ordenado en la consulta y no en memoria, por lo mismo que el catalogo de conceptos. El mes
     * de arriba es el que se esta facturando, que es el que se busca al abrir la pantalla.
     */
    List<BillingPeriod> findByCondominiumIdOrderByYearDescMonthDesc(UUID condominiumId);
}
