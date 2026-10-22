/**
 * Cargos y obligaciones de pago: expensas, agua, mora.
 *
 * <p>Dueno de {@code PaymentObligation}, la entidad central del sistema (issue #23), y de su maquina
 * de estados {@code DRAFT -> ISSUED -> PAID} con salidas a {@code CANCELLED} e
 * {@code IN_AGREEMENT} (analisis 5.3). Publica {@code PaymentObligationIssued} y
 * {@code PaymentObligationPaid} --el {@code ObligationPaid} del analisis 5.1, al que reacciona el
 * modulo que origino la deuda-- y reaccionara al evento de dinero recibido que publique
 * {@code payments}.
 *
 * <p><strong>La deuda no se hereda, se referencia</strong> (analisis 5.1): no hay una obligacion por
 * clase de cargo. Cada modulo guarda su entidad de negocio --el periodo de expensas, la lectura de
 * agua, la multa, la reserva-- y la obligacion apunta a ella con {@code sourceType} y
 * {@code sourceId}. Asi el cobro no conoce las reglas del agua ni de las reservas. <strong>Esos dos
 * campos estan vacios hasta el #24</strong>, que trae la primera entidad de origen.
 *
 * <p>Este modulo depende de {@code identity} por una sola cosa: la obligacion guarda quien responde
 * por ella, y comprobar que esa persona existe se le pregunta a {@code People}.
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
 * <p>Y del <strong>periodo de facturacion</strong> ({@code BillingPeriod}, issue #101), que es el mes al
 * que pertenece cada obligacion y la segunda maquina de estados del modulo:
 * {@code DRAFT -> ISSUED -> CLOSED}. Se abre antes de emitir y nace en {@code DRAFT}; la emision lo
 * abre si falta, de forma idempotente, para que el worker del #28 no falle en un mes que nadie
 * preparo. Las lecturas de agua del #26 se cuelgan de el, y por eso no podia nacer al emitir.
 *
 * <p>No conoce proveedores de pago ni QR: eso es de {@code payments}.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared", "condominium", "identity"},
        displayName = "Billing")
package bo.coprop.billing;
