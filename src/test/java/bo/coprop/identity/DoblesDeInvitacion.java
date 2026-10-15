package bo.coprop.identity;

import bo.coprop.notifications.Correo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Los tres dobles que necesitan las invitaciones: el correo, el reloj y --via
 * {@link DoblesDeAcceso}-- el proveedor de identidad.
 *
 * <p>Ninguno de los tres se puede usar de verdad en un test. El correo saldria a un SMTP; el
 * proveedor de identidad a la Admin API de Keycloak, que en CI no existe --y arrancarlo costaria
 * mas que todo el build, que es la razon por la que {@code RealmDeKeycloakTests} comprueba el realm
 * como archivo--; y el reloj real no deja probar que una invitacion vence en siete dias sin esperar
 * siete dias.
 *
 * <p>El del proveedor <strong>vive en {@link DoblesDeAcceso} y se importa</strong>, no se duplica
 * aqui: lo necesitan tambien los tests de la baja del issue #19, que no pueden importar esta clase
 * porque su reloj movible choca con el parado que tienen. Dos dobles del mismo puerto en dos sitios
 * serian dos cosas que mantener de acuerdo.
 *
 * <p><strong>El correo es por donde el test lee el token</strong>, y eso no es un atajo: es el unico
 * sitio donde el token existe, igual que para la persona invitada. Un test que lo sacara de la base
 * estaria probando un sistema distinto del que se construyo, porque en la base solo hay un hash.
 *
 * <p>Los metodos de fabrica no se llaman como los beans de produccion --{@code correoSmtp},
 * {@code proveedorDeIdentidadKeycloak}, {@code reloj}--: dos definiciones con el mismo nombre dan un
 * {@code BeanDefinitionOverrideException} en vez de sustituirse, y eso ya costo tiempo en el issue
 * #9. El {@code @Primary} es el que decide cual se inyecta.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(DoblesDeAcceso.class)
public class DoblesDeInvitacion {

    @Bean
    @Primary
    CorreoDePrueba buzon() {
        return new CorreoDePrueba();
    }

    @Bean
    @Primary
    RelojDePrueba relojMovible() {
        return new RelojDePrueba();
    }

    /** Guarda lo que se envio, y sabe sacar el token del cuerpo. */
    public static class CorreoDePrueba implements Correo {

        /**
         * El token tal como lo escribe el cuerpo del correo: el final del enlace.
         *
         * <p>Se busca por la forma del enlace y no por la del token, porque lo que el test tiene que
         * comprobar es que llego algo que se puede pulsar. Si manana el cuerpo cambia de redaccion
         * pero conserva el enlace, esto sigue valiendo.
         */
        private static final Pattern ENLACE = Pattern.compile("/invitaciones/([A-Za-z0-9_-]+)");

        private final List<Enviado> enviados = new ArrayList<>();

        @Override
        public synchronized void enviar(String destinatario, String asunto, String cuerpo) {
            enviados.add(new Enviado(destinatario, asunto, cuerpo));
        }

        public synchronized List<Enviado> enviados() {
            return List.copyOf(enviados);
        }

        public synchronized void vaciar() {
            enviados.clear();
        }

        /** El token del ultimo correo enviado a esa direccion. */
        public synchronized Optional<String> ultimoTokenPara(String destinatario) {
            return enviados.reversed().stream()
                    .filter(enviado -> enviado.destinatario().equals(destinatario))
                    .map(enviado -> ENLACE.matcher(enviado.cuerpo()))
                    .filter(java.util.regex.Matcher::find)
                    .map(encontrado -> encontrado.group(1))
                    .findFirst();
        }

        public record Enviado(String destinatario, String asunto, String cuerpo) {}
    }

    /**
     * Un reloj que se puede adelantar.
     *
     * <p>Es lo que hace comprobable el vencimiento de siete dias, y la razon de que la regla de
     * ARCHITECTURE.md --nadie llama a {@code Instant.now()}, se inyecta el {@code Clock}-- valga la
     * pena: con un {@code now()} dentro del servicio, el unico test posible seria esperar una semana.
     */
    public static class RelojDePrueba extends Clock {

        private volatile Instant ahora = Instant.parse("2026-10-14T12:00:00Z");

        public void adelantar(Duration cuanto) {
            ahora = ahora.plus(cuanto);
        }

        @Override
        public Instant instant() {
            return ahora;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zona) {
            // El sistema entero trabaja en UTC (analisis 12.2) y nadie pide otra zona. Devolver
            // this en vez de un reloj con zona distinta evita que un uso inesperado pase
            // desapercibido con una hora equivocada.
            return this;
        }
    }
}
