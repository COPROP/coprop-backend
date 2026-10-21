package bo.coprop.billing;

import java.util.UUID;

/** Lo que se devuelve de un concepto de cobro. */
public record ChargeConceptView(
        UUID id, UUID condominiumId, String code, String name, ChargeConceptType type, PaymentGroup paymentGroup) {}
