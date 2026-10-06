/**
 * Tipos comunes a todos los modulos: identificadores, dinero, periodo, errores de dominio.
 *
 * <p>Modulo abierto: cualquier modulo puede usar sus tipos. No contiene reglas de negocio ni
 * acceso a datos. Nada entra aqui solo por ser reutilizable; entra si no pertenece a ningun
 * dominio en particular.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        displayName = "Shared")
package bo.coprop.shared;
