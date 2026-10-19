package bo.coprop.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * El token de una invitacion: se genera una vez, se guarda su hash y el original se olvida
 * (Seguridad 6.1).
 *
 * <p><strong>256 bits de {@link SecureRandom}</strong>, no un UUID. Un UUID aleatorio tiene 122
 * bits de entropia y, lo que importa mas, se parece a un identificador: cualquiera que vea uno en
 * una URL asume que es un dato y no una credencial, y acaba en un log o en un ticket de soporte.
 * Esto se ve lo que es.
 *
 * <p>Se codifica en base64 sin relleno y <strong>seguro para URL</strong>, porque viaja en la ruta
 * del enlace del correo: con el alfabeto normal, un {@code +} o un {@code /} obligarian a escapar y
 * cualquier cliente de correo que no lo haga bien parte el enlace.
 *
 * <p>El hash es SHA-256 y <strong>no lleva sal</strong>, a proposito. Una contraseña se sala porque
 * la elige una persona y es adivinable; esto son 256 bits aleatorios, asi que no hay diccionario
 * que recorrer, y sin sal el hash se puede buscar por indice, que es como se resuelve un enlace en
 * una sola consulta. Salarlo obligaria a leer todas las filas y comparar una por una.
 */
final class InvitationToken {

    private static final SecureRandom AZAR = new SecureRandom();

    private static final int BYTES = 32;

    private InvitationToken() {}

    /** Un token nuevo. El unico sitio donde existe en claro es el valor que devuelve. */
    static String nuevo() {
        byte[] crudo = new byte[BYTES];
        AZAR.nextBytes(crudo);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(crudo);
    }

    /** El hash que se guarda y por el que se busca. 64 caracteres hexadecimales en minusculas. */
    static String hashDe(String token) {
        return HexFormat.of().formatHex(sha256().digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException imposible) {
            // SHA-256 es obligatorio en toda implementacion de Java desde la 1.4. Si falta, el
            // problema no es la invitacion.
            throw new IllegalStateException("Esta maquina virtual no trae SHA-256.", imposible);
        }
    }
}
