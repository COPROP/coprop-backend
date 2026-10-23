package bo.coprop.billing;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Una linea del calculo de expensas: lo que le toca a una unidad. Issue #24.
 *
 * <p>Es la obligacion vista desde la emision, no otra entidad: {@code obligationId} es la
 * obligacion de pago que ya existe en la base, en {@code DRAFT} mientras el calculo esta en
 * previsualizacion y en {@code ISSUED} despues.
 *
 * <p>Lleva el codigo de la unidad y la alicuota aunque los dos salgan de {@code condominium}:
 * previsualizar sin ellos obligaria al cliente a pedir las unidades aparte y emparejarlas, y la
 * alicuota es justo lo que una persona quiere revisar antes de confirmar un reparto.
 *
 * @param aliquot la alicuota con la que se calculo, tal como estaba al calcular
 * @param number el numero legible de la obligacion: {@code EXP-2026-09-A-302}
 * @param amount como cadena por lo mismo que en {@link PaymentObligationView}. La alicuota de al
 *     lado no: no es dinero, y va como numero igual que en {@code UnitView}
 */
public record ExpenseLineView(
        UUID obligationId,
        UUID unitId,
        String unitCode,
        BigDecimal aliquot,
        UUID responsiblePersonId,
        String number,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        LocalDate dueDate) {}
