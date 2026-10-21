/**
 * Cargos y obligaciones de pago: expensas, agua, mora.
 *
 * <p>Dueno de {@code PaymentObligation}, la entidad central del sistema, y de su maquina de estados
 * {@code DRAFT -> ISSUED -> PAID} (analisis seccion 5.3). Emite el evento de obligacion emitida y
 * reacciona al de obligacion pagada que publica {@code payments}.
 *
 * <p>Dueno tambien del <strong>catalogo de conceptos de cobro</strong> ({@code ChargeConcept}, issue
 * #100), que es lo que dice <em>que</em> se cobra. Vive aqui y no en {@code condominium} --donde esta
 * la otra configuracion del edificio-- porque lo que lo usa es la obligacion de pago: su tipo es lo
 * que elige el metodo de calculo (#24, #25, #27) y su grupo de pago es lo que decide que obligaciones
 * caben en un mismo QR. La consecuencia es que la clave foranea de
 * {@code billing_responsibility.concept_id} apunta a una tabla de este modulo desde una de
 * {@code condominium}, al contrario que la dependencia de codigo; esa asimetria es a proposito y
 * esta explicada en la V11 y en ARCHITECTURE.md.
 *
 * <p>No conoce proveedores de pago ni QR: eso es de {@code payments}.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared", "condominium", "identity"},
        displayName = "Billing")
package bo.coprop.billing;
