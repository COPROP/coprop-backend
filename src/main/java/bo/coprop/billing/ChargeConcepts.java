package bo.coprop.billing;

import java.util.List;
import java.util.UUID;

/**
 * El catalogo de que se cobra en cada condominio. Issue #100.
 *
 * <p>El analisis 5.2 lo modela como un catalogo configurable y no como una enumeracion fija: lo que
 * un edificio cobra como "expensas" otro lo parte en dos conceptos, y el nombre y el codigo los pone
 * su administrador. {@code PaymentObligation} (#23) apunta aqui para decir que se cobra.
 *
 * <p><strong>El condominio es el primer argumento de todo</strong>, consultas incluidas, por la
 * misma razon que en {@code Units}: el analisis 12.2 exige el {@code condominium_id} en toda
 * consulta y una firma que acepta solo el identificador del concepto deja la comprobacion al
 * criterio de quien llama.
 *
 * <p><strong>Lo que este catalogo no trae, y donde esta cada cosa:</strong>
 *
 * <ul>
 *   <li><em>El monto o la formula</em>: son del #24 (expensas por alicuota), del #25 (las cuatro
 *       estrategias de agua) y del #27 (politica de mora). Aqui el concepto solo dice que se cobra.
 *   <li><em>El borrado</em>: no existe, y es deliberado. Un concepto que ya tiene obligaciones
 *       emitidas no se puede borrar sin partir en dos la historia de un cobro, y la base lo rechaza
 *       --la clave foranea de {@code billing_responsibility} no lleva {@code ON DELETE}, y la de
 *       {@code payment_obligation} tampoco la llevara--. Retirar un concepto que ya no se usa es
 *       otra decision, de la clase que resolvio {@code UnitStatus}, y no tiene ticket todavia.
 *   <li><em>El campo "admite pago parcial"</em> que el analisis 5.2 le atribuye: se decidio el
 *       19-oct-2026 que en el MVP no hay pagos parciales, asi que es de Fase 2.
 * </ul>
 */
public interface ChargeConcepts {

    /**
     * Da de alta un concepto de cobro.
     *
     * @throws bo.coprop.shared.ResourceNotFound si el condominio no existe
     * @throws bo.coprop.shared.StateConflict si ese condominio ya tiene un concepto con ese codigo
     */
    ChargeConceptView register(UUID condominiumId, NewChargeConcept body, String actor);

    /**
     * El catalogo del condominio, por codigo.
     *
     * <p>Sin paginar, al contrario que la bitacora: un catalogo de conceptos tiene unas pocas filas
     * --las clases de cobro son seis-- y quien lo pide lo pide entero, para pintar un desplegable o
     * para emitir. Paginarlo obligaria a todo cliente a recorrer paginas de una lista que cabe en
     * una pantalla.
     *
     * @throws bo.coprop.shared.ResourceNotFound si el condominio no existe
     */
    List<ChargeConceptView> list(UUID condominiumId);
}
