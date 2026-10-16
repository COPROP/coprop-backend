package bo.coprop.condominium.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CondominiumRepository extends JpaRepository<Condominium, UUID> {

    boolean existsByNit(String nit);

    /**
     * Si ese NIT ya es de OTRO condominio.
     *
     * <p>El {@code IdNot} es lo que distingue una edicion de un alta: guardar un condominio con el
     * NIT que ya tenia no es un choque, y comprobarlo con {@link #existsByNit} rechazaria corregir
     * solo el nombre.
     */
    boolean existsByNitAndIdNot(String nit, UUID id);
}
