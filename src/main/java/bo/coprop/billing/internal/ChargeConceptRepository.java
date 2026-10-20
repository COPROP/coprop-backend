package bo.coprop.billing.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChargeConceptRepository extends JpaRepository<ChargeConcept, UUID> {

    boolean existsByCondominiumIdAndCode(UUID condominiumId, String code);

    /**
     * El catalogo del condominio, por codigo.
     *
     * <p>Ordenado en la consulta y no en memoria: el orden es parte de lo que el cliente pinta, y
     * dejarlo al que la base devuelva mueve las opciones de sitio entre dos peticiones.
     */
    List<ChargeConcept> findByCondominiumIdOrderByCode(UUID condominiumId);
}
