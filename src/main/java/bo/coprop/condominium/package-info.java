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
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"},
        displayName = "Condominium")
package bo.coprop.condominium;
