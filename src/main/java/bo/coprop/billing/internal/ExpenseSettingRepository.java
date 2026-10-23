package bo.coprop.billing.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ExpenseSettingRepository extends JpaRepository<ExpenseSetting, UUID> {

    /**
     * La configuracion vigente de un concepto, si la hay.
     *
     * <p>El indice parcial de la V14 garantiza que no haya dos, asi que esto devuelve una o ninguna
     * sin tener que ordenar por vigencia.
     */
    Optional<ExpenseSetting> findByCondominiumIdAndConceptIdAndValidToIsNull(UUID condominiumId, UUID conceptId);

    /**
     * Las vigentes del condominio, una por concepto.
     *
     * <p>El concepto desempata porque {@code validFrom} no basta: dos configuraciones puestas
     * seguidas caen en el mismo instante --el reloj de Windows tiene una granularidad de unos 15
     * milisegundos-- y entonces el orden seria el que la base devolviera, distinto entre dos
     * peticiones.
     */
    List<ExpenseSetting> findByCondominiumIdAndValidToIsNullOrderByValidFromDescConceptIdAsc(UUID condominiumId);

    /**
     * Toda la historia del condominio, de la mas reciente a la mas antigua.
     *
     * <p>Ordenada en la consulta y no en memoria, igual que el resto del modulo: el orden es parte de
     * lo que el cliente pinta.
     *
     * <p>Escrita a mano por el {@code nulls first}: con el mismo {@code validFrom} --dos cambios
     * seguidos-- el que desempata es {@code validTo}, y la vigente es justamente la que lo tiene
     * nulo, asi que tiene que ir primera. Dejarlo al dialecto funcionaria en PostgreSQL, que ordena
     * los nulos primero en {@code DESC}, y se rompe en silencio con cualquier otro.
     */
    @Query(
            """
            select s from ExpenseSetting s
            where s.condominiumId = :condominiumId
            order by s.validFrom desc, s.validTo desc nulls first, s.conceptId asc
            """)
    List<ExpenseSetting> historyOf(@Param("condominiumId") UUID condominiumId);
}
