/**
 * Envio de avisos. Solo correo en el MVP (analisis RF-38).
 *
 * <p>Una interfaz por canal, de modo que sumar WhatsApp o SMS en fase posterior no toque a quien
 * dispara la notificacion.
 *
 * <p>La idea original era que este modulo <strong>solo</strong> reaccionara a eventos, sin que nadie
 * lo llamara: asi añadir un aviso no obliga a tocar a quien lo origina. Para casi todo sigue siendo
 * lo correcto y es como entraran los avisos de M2. El issue <strong>#18</strong> obligo a abrir una
 * excepcion y a publicar el puerto {@code Correo}: el correo de la invitacion lleva el token, los
 * eventos de este sistema se persisten --la migracion V2-- y Seguridad 6.1 exige que de ese token
 * solo quede el hash. Por un evento no puede viajar, asi que lo envia quien lo tiene en memoria.
 *
 * <p>Por eso {@code identity} lo declara como dependencia. Es la unica, y la direccion es la que
 * cabe esperar: quien decide que hay que avisar llama a quien sabe enviar, nunca al reves.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"},
        displayName = "Notifications")
package bo.coprop.notifications;
