package bo.coprop.shared;

import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Tapa secretos en el texto que va a los logs.
 *
 * <p>El analisis lo exige en dos sitios: nunca registrar credenciales bancarias, tokens ni datos
 * de QR completos (issue #6), y enmascarar CI y cuentas (analisis 12.2, proteccion de datos).
 *
 * <p><strong>Esto es una red, no la defensa.</strong> La defensa es no meter un secreto en un log;
 * un enmascarado siempre se puede esquivar concatenando el valor de una forma que la expresion no
 * reconozca. Existe porque el dia que alguien registre por descuido el cuerpo entero de una
 * respuesta del banco, conviene que el secreto no quede escrito en disco.
 *
 * <p>Las reglas son <strong>por nombre de clave</strong> y no por forma del valor, a proposito:
 * reconocer "esto parece un numero de cuenta" produce falsos positivos que destrozan los logs
 * utiles. Las dos excepciones son el JWT y el encabezado {@code Bearer}, que si tienen una forma
 * inconfundible.
 */
public final class Enmascarador {

    /** Lo que se escribe en lugar del secreto. */
    public static final String TAPADO = "***";

    /**
     * Claves cuyo valor nunca debe salir. Se comparan sin distinguir mayusculas y aceptan guion o
     * guion bajo donde los nombres suelen variar ({@code api_key}, {@code api-key}, {@code apikey}).
     */
    private static final String CLAVES = "password|passwd|contrasena|contrasenia|clave|secret|secreto"
            + "|token|api[-_]?key|apikey|authorization|client[-_]?secret|credential|credencial"
            + "|cuenta|account|iban|ci|carnet|nit|qr|qr[-_]?payload|emv";

    private record Regla(Pattern patron, String reemplazo) {}

    private static final List<Regla> REGLAS = List.of(
            // "password": "loquesea"  ->  "password": "***"
            new Regla(
                    Pattern.compile("(\"(?:" + CLAVES + ")\"\\s*:\\s*\")[^\"]*(\")", Pattern.CASE_INSENSITIVE),
                    "$1" + TAPADO + "$2"),
            // password=loquesea  ->  password=***
            new Regla(
                    Pattern.compile("\\b((?:" + CLAVES + ")\\s*[=:]\\s*)[^\\s,;)}\\]]+", Pattern.CASE_INSENSITIVE),
                    "$1" + TAPADO),
            // Authorization: Bearer <lo que sea>
            new Regla(
                    Pattern.compile("\\bBearer\\s+[A-Za-z0-9._~+/=-]+", Pattern.CASE_INSENSITIVE), "Bearer " + TAPADO),
            // Un JWT suelto, sin clave que lo anuncie. La forma es inconfundible.
            new Regla(Pattern.compile("\\beyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"), TAPADO));

    /** La clave suelta, para cuando el nombre no viaja pegado al valor: el MDC, una cabecera. */
    private static final Pattern CLAVE_SUELTA = Pattern.compile("^(?:" + CLAVES + ")$", Pattern.CASE_INSENSITIVE);

    private Enmascarador() {}

    /**
     * Enmascara un valor del que se conoce la clave por separado.
     *
     * <p>Hace falta porque las reglas son por nombre de clave, y en el MDC o en una cabecera el
     * nombre no viaja dentro del texto: enmascarar el valor a secas no encuentra nada que tapar.
     * Lo descubrio el test del criterio del issue #6, con un {@code token} en el MDC que salia
     * entero.
     */
    public static String valorDe(String clave, @Nullable String valor) {
        return CLAVE_SUELTA.matcher(clave).matches() ? TAPADO : enmascarar(valor);
    }

    /** Devuelve el texto con los secretos tapados. Un texto nulo sale como cadena vacia. */
    public static String enmascarar(@Nullable String texto) {
        if (texto == null || texto.isEmpty()) {
            return "";
        }
        String resultado = texto;
        for (Regla regla : REGLAS) {
            // El reemplazo lleva $1 y $2 a proposito: conserva el nombre de la clave y tapa solo
            // el valor. Las cadenas son constantes de esta clase, nunca entrada del usuario.
            resultado = regla.patron().matcher(resultado).replaceAll(regla.reemplazo());
        }
        return resultado;
    }
}
