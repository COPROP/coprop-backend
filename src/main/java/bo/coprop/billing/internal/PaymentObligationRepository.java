package bo.coprop.billing.internal;

import bo.coprop.billing.PaymentObligationStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentObligationRepository extends JpaRepository<PaymentObligation, UUID> {

    /**
     * La obligacion, pero solo si es de ese condominio.
     *
     * <p>El condominio va en la consulta y no se comprueba despues de leer, igual que en el periodo:
     * asi una obligacion ajena no existe en vez de existir y ser rechazada, que es lo que pide
     * Seguridad 7.2.
     */
    Optional<PaymentObligation> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    /** La deuda de una unidad, de la mas reciente a la mas antigua. */
    List<PaymentObligation> findByCondominiumIdAndUnitIdOrderByDueDateDesc(UUID condominiumId, UUID unitId);

    /** Las obligaciones de un periodo, por numero: es como se leen al emitir y al cerrar el mes. */
    List<PaymentObligation> findByCondominiumIdAndPeriodIdOrderByNumber(UUID condominiumId, UUID periodId);

    /**
     * Si el periodo tiene alguna obligacion en ese estado.
     *
     * <p>La usa el cierre del mes del #101 para rechazar un cierre con borradores dentro. Vive aqui
     * y no en la interfaz publica porque quien pregunta --{@code BillingPeriodsService}-- es de este
     * mismo modulo, asi que no hace falta pasar por el contrato.
     */
    boolean existsByCondominiumIdAndPeriodIdAndStatus(
            UUID condominiumId, UUID periodId, PaymentObligationStatus status);

    /**
     * Si ese numero legible ya esta usado en el condominio.
     *
     * <p>Lo pregunta la composicion del numero antes de insertar, para añadir el correlativo cuando
     * hace falta. El unico de la base es el que de verdad lo garantiza.
     */
    boolean existsByCondominiumIdAndNumber(UUID condominiumId, String number);

    /** Si el concepto ya tiene obligaciones: lo pregunta el criterio del #100. */
    boolean existsByCondominiumIdAndConceptId(UUID condominiumId, UUID conceptId);

    /**
     * Los borradores de un concepto en un mes, por numero.
     *
     * <p>Son lo que la emision de expensas del #24 reemplaza al recalcular y saca a cobro al
     * emitir. El concepto entra en la consulta porque un mes puede llevar expensas y fondo de
     * reserva calculados por separado, y recalcular uno no puede borrar el otro.
     */
    List<PaymentObligation> findByCondominiumIdAndPeriodIdAndConceptIdAndStatusOrderByNumber(
            UUID condominiumId, UUID periodId, UUID conceptId, PaymentObligationStatus status);

    /**
     * Cuantas obligaciones de ese concepto hay ya a cobro en ese mes.
     *
     * <p>Es lo que contesta el primer criterio del #24 --"emitir un periodo ya emitido no crea nada
     * y lo informa"--: si hay alguna, la emision responde conflicto con este numero dentro.
     *
     * <p><strong>Se pregunta por estados y no por "distinto de borrador"</strong> a proposito: una
     * anulada no cuenta como emitida. Un mes que se emitio y se anulo entero se puede volver a
     * calcular y a emitir, que es lo que corresponde cuando la emision estuvo mal.
     */
    long countByCondominiumIdAndPeriodIdAndConceptIdAndStatusIn(
            UUID condominiumId, UUID periodId, UUID conceptId, Collection<PaymentObligationStatus> statuses);
}
