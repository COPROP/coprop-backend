package bo.coprop.condominium.internal;

import bo.coprop.condominium.UnitStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UnitRepository extends JpaRepository<Unit, UUID> {

    /**
     * Los codigos de este lote que ya existen en el condominio.
     *
     * <p>Se consulta en bloque y no uno por uno: un alta masiva de 200 unidades no puede hacer
     * 200 consultas para averiguar lo mismo.
     */
    List<Unit> findByCondominiumIdAndCodeIn(UUID condominiumId, Collection<String> codes);

    /**
     * Las unidades del condominio en un estado, por codigo.
     *
     * <p>La pide la emision de expensas del #24, que solo cobra a las activas. El orden va en la
     * consulta y no en memoria, igual que en el resto del proyecto.
     */
    List<Unit> findByCondominiumIdAndStatusOrderByCode(UUID condominiumId, UnitStatus status);
}
