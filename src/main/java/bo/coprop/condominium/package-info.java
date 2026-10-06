/**
 * Condominios, su configuracion y sus unidades.
 *
 * <p>Raiz del modelo multi-tenant. Cada condominio trae su propia configuracion: politicas de
 * mora, formula de agua, reglas de reserva, dia de emision y de vencimiento (analisis RF-01).
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"},
        displayName = "Condominium")
package bo.coprop.condominium;
