/**
 * Condominios, su configuracion, sus unidades y las relaciones de la unidad con las personas.
 *
 * <p>Raiz del modelo multi-tenant. Cada condominio trae su propia configuracion: politicas de
 * mora, formula de agua, reglas de reserva, dia de emision y de vencimiento (analisis RF-01).
 *
 * <p>Aqui viven tambien la titularidad, la ocupacion y la responsabilidad de pago, que Seguridad 2
 * dibuja colgando de la persona. El motivo esta en la regla 2 de ARCHITECTURE.md: las tres tienen
 * que responder 404 --y no 403-- cuando la unidad no es del condominio de la ruta, y desde
 * {@code identity} eso no se puede comprobar sin depender de este modulo. La persona se referencia
 * por identificador, que es el mismo patron en la otra direccion.
 *
 * <p><strong>La responsabilidad de pago tiene un defecto, y es una fila de verdad</strong> (issue
 * #29): al registrar al propietario unico de una unidad se le abre tambien la responsabilidad
 * general, porque RF-08 y la decision 13.3 dicen que por defecto paga el propietario para todos los
 * conceptos y el inquilino solo si se configura. Se eligio una fila y no una regla que la emision
 * resuelva para que {@code billing_responsibility} conteste sola "a quien se le cobra esta unidad",
 * que es lo que el analisis 7.1 da por supuesto. Una unidad en copropiedad no recibe defecto: la
 * obligacion lleva un responsable y elegir al del porcentaje mayor seria decidirlo sin que nadie lo
 * haya dicho.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"},
        displayName = "Condominium")
package bo.coprop.condominium;
