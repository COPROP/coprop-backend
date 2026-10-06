/**
 * Envio de avisos. Solo correo en el MVP (analisis RF-38).
 *
 * <p>Una interfaz por canal, de modo que sumar WhatsApp o SMS en fase posterior no toque a quien
 * dispara la notificacion. Este modulo solo reacciona a eventos de otros modulos: nadie lo llama
 * directamente, por eso no depende de ninguno.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"},
        displayName = "Notifications")
package bo.coprop.notifications;
