/**
 * Cargos y obligaciones de pago: expensas, agua, mora.
 *
 * <p>Dueno de {@code PaymentObligation}, la entidad central del sistema, y de su maquina de
 * estados {@code BORRADOR -> EMITIDA -> PAGADA} (analisis seccion 5.3). Emite el evento de
 * obligacion emitida y reacciona al de obligacion pagada que publica {@code payments}.
 *
 * <p>No conoce proveedores de pago ni QR: eso es de {@code payments}.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared", "condominium", "identity"},
        displayName = "Billing")
package bo.coprop.billing;
