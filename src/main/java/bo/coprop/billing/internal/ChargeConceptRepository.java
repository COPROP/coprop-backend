package bo.coprop.billing.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChargeConceptRepository extends JpaRepository<ChargeConcept, UUID> {

    boolean existsByCondominiumIdAndCode(UUID condominiumId, String code);

    /**
     * El concepto, pero solo si es de ese condominio.
     *
     * <p>La pide la obligacion de pago (#23) para componer su numero legible con el codigo del
     * concepto. El condominio va en la consulta por lo mismo que en todo este esquema: asi un
     * concepto ajeno no existe en vez de existir y ser rechazado.
     */
    Optional<ChargeConcept> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    /**
     * El catalogo del condominio, por codigo.
     *
     * <p>Ordenado en la consulta y no en memoria: el orden es parte de lo que el cliente pinta, y
     * dejarlo al que la base devuelva mueve las opciones de sitio entre dos peticiones.
     */
    List<ChargeConcept> findByCondominiumIdOrderByCode(UUID condominiumId);
}
