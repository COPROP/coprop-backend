package bo.coprop.billing;

import bo.coprop.shared.AuditableEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Se dio de alta un concepto de cobro.
 *
 * <p>Se audita porque configurar que se cobra es una de las acciones que la matriz de Seguridad 3
 * separa del resto --es {@code CONFIGURE_CHARGES}, del que maneja el dinero-- y la bitacora del
 * analisis 12.2 tiene que poder decir quien añadio un concepto y cuando. Un concepto nuevo aparece
 * despues en los estados de cuenta de todo el edificio.
 *
 * @param conceptId el concepto recien creado
 * @param code su codigo, que es como lo nombra quien lee la bitacora
 * @param actor quien lo dio de alta
 * @param occurredAt cuando
 */
public record ChargeConceptRegistered(
        UUID condominiumId,
        UUID conceptId,
        String code,
        String name,
        ChargeConceptType type,
        PaymentGroup paymentGroup,
        String actor,
        Instant occurredAt)
        implements AuditableEvent {

    @Override
    public String action() {
        return "CHARGE_CONCEPT_REGISTERED";
    }

    @Override
    public String entity() {
        return "concepto-de-cobro";
    }

    @Override
    public UUID entityId() {
        return conceptId;
    }

    /**
     * Los cuatro datos del concepto, y no solo su codigo.
     *
     * <p>Al contrario que el alta de un condominio --donde la bitacora no repite la ficha porque la
     * fila sigue ahi con todo-- aqui interesa el tipo y el grupo de pago en el momento del alta: son
     * los dos que deciden como se calcula y con que se cobra, y si algun dia se pueden editar, la
     * bitacora tiene que poder decir con cual se emitio lo de entonces.
     *
     * <p>{@code oldValues()} se queda en el nulo por defecto: no habia nada antes, que es el caso de
     * todo alta.
     */
    @Override
    public Map<String, String> newValues() {
        return Map.of("code", code, "name", name, "type", type.name(), "paymentGroup", paymentGroup.name());
    }
}
