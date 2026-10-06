/**
 * Bitacora inmutable de cambios y acciones, con retencion de un ano (Seguridad, secciones 8 y
 * 10.1).
 *
 * <p>Tabla append-only: la aplicacion no tiene permiso de UPDATE ni DELETE sobre ella. Como
 * {@code notifications}, solo reacciona a eventos, de modo que anadir un evento auditable no
 * obliga a tocar este modulo desde quien lo origina.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"},
        displayName = "Audit")
package bo.coprop.audit;
