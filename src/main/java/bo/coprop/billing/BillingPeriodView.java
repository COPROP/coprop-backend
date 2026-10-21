package bo.coprop.billing;

import java.util.UUID;

/** Lo que se devuelve de un periodo de facturacion. */
public record BillingPeriodView(UUID id, UUID condominiumId, int year, int month, BillingPeriodStatus status) {}
