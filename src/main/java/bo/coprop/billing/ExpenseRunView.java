package bo.coprop.billing;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * El calculo de expensas de un concepto en un mes: lo que se previsualiza y lo que se emite. Issue
 * #24.
 *
 * <p><strong>No es una entidad y no hay tabla detras.</strong> Lo que esta guardado son las
 * obligaciones --una por unidad-- y el periodo; esto es la vista que las agrupa para que una persona
 * pueda revisar el reparto antes de confirmarlo. Una tabla de "lote de emision" no aportaria nada
 * que no se pueda reconstruir de sus lineas, y seria una fila mas que mantener al anular una.
 *
 * @param status el estado en el que estan sus obligaciones: {@code DRAFT} mientras es una
 *     previsualizacion, {@code ISSUED} una vez emitido. Es lo que distingue las dos pantallas
 * @param settingId la configuracion con la que se calculo. Es la misma que cada obligacion guarda en
 *     su {@code sourceId}, y la que contesta "por que me cobran esto"
 * @param total la suma de las lineas, como cadena por lo mismo que en
 *     {@link PaymentObligationView}. Con {@code BY_ALIQUOT} coincide exactamente con el monto
 *     configurado, que es el criterio del ticket; con {@code FIXED_PER_UNIT} es el monto por el
 *     numero de unidades
 */
public record ExpenseRunView(
        UUID condominiumId,
        UUID periodId,
        int year,
        int month,
        UUID conceptId,
        UUID settingId,
        ExpenseMethod method,
        PaymentObligationStatus status,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal total,
        String currency,
        List<ExpenseLineView> lines) {}
