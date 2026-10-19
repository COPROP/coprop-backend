package bo.coprop.notifications.internal;

import bo.coprop.notifications.Mail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * El correo, por SMTP. En local lo recoge Mailpit, que el Compose levanta en el 8025.
 *
 * <p>No reintenta. Si el servidor de correo no responde, el fallo sube: quien llama es el que sabe
 * si eso se puede deshacer --la emision de una invitacion lo deshace-- y un reintento aqui solo
 * alargaria la peticion antes de contarlo. Cuando haya avisos que si convenga reintentar, lo que
 * toca es una cola, no un bucle.
 *
 * <p><strong>No registra el cuerpo en el log.</strong> El de la invitacion lleva el token: un
 * mensaje de depuracion con el cuerpo entero lo dejaria en el archivo de logs, que es el otro sitio
 * donde no puede estar.
 *
 * <p>El destinatario se registra <strong>con forma de clave y valor</strong> --{@code correo=...}--
 * y no suelto en la frase. No es estilo: {@code Masker} tapa por nombre de clave, asi que un
 * correo escrito como parte del texto saldria entero y Seguridad 8 pide taparlo.
 */
@Component
class SmtpMail implements Mail {

    private static final Logger log = LoggerFactory.getLogger(SmtpMail.class);

    private final JavaMailSender email;
    private final String remitente;

    SmtpMail(JavaMailSender email, @Value("${coprop.correo.remitente}") String remitente) {
        this.email = email;
        this.remitente = remitente;
    }

    @Override
    public void enviar(String destinatario, String asunto, String cuerpo) {
        var message = new SimpleMailMessage();
        message.setFrom(remitente);
        message.setTo(destinatario);
        message.setSubject(asunto);
        message.setText(cuerpo);
        try {
            this.email.send(message);
        } catch (MailException fallo) {
            throw new FalloDeEnvio("No se pudo enviar el correo a %s.".formatted(destinatario), fallo);
        }
        log.info("Mail enviado: correo={}, asunto={}", destinatario, asunto);
    }
}
