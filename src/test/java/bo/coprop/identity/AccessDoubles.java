package bo.coprop.identity;

import bo.coprop.identity.IdentityProvider.ProviderSession;
import bo.coprop.identity.Sessions.BffSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Los dos dobles de lo que esta al otro lado de una puerta: el proveedor de identidad y las
 * sesiones del BFF.
 *
 * <p>Van juntos porque los pide la misma operacion --la baja del issue #19 cierra las dos cosas-- y
 * aparte del reloj y el correo porque un test de membresias no quiere un reloj movible: tiene el
 * suyo parado, y dos {@code @Primary} del mismo tipo chocan.
 *
 * <p><strong>El de sesiones hace falta en todo test de modulo de {@code identity}</strong>, aunque
 * no toque ninguna baja: {@link Sessions} es un puerto que implementa {@code api}, y en un
 * {@code @ApplicationModuleTest} ese modulo no esta, asi que sin este doble el contexto no arranca.
 * Que haga falta es la prueba de que la dependencia va en la direccion correcta: el dominio declara
 * y {@code api} implementa.
 *
 * <p><strong>Y desde el #23 hace falta tambien en los tests de modulo de {@code billing}</strong>
 * --que la importan a traves de {@link InvitationDoubles}, porque ahi arrastran ademas el correo--,
 * aunque no
 * tocan ninguna sesion. El motivo: la obligacion de pago pregunta a {@code People} si la persona
 * responsable existe, asi que {@code billing} pasa a depender de {@code identity} de verdad --no
 * solo en el {@code package-info}-- y un {@code ApplicationModuleTest} con sus dependencias
 * directas arranca ahora los beans de {@code identity}. El sintoma, cuando falta:
 * {@code accessRevocation} no encuentra {@code Sessions} y no arranca ni un test del modulo.
 *
 * <p>Los metodos de fabrica no se llaman como los beans de produccion
 * --{@code proveedorDeIdentidadKeycloak}, {@code sesionesDelBff}--: dos definiciones con el mismo
 * nombre dan un {@code BeanDefinitionOverrideException} en vez de sustituirse.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AccessDoubles {

    @Bean
    @Primary
    ProveedorDeIdentidadDePrueba proveedorDePrueba() {
        return new ProveedorDeIdentidadDePrueba();
    }

    @Bean
    @Primary
    SesionesDePrueba sesionesDePrueba() {
        return new SesionesDePrueba();
    }

    /** Anota a quien se le preparo credencial y a quien se le cerraron sesiones. */
    public static class ProveedorDeIdentidadDePrueba implements IdentityProvider {

        private final List<String> prepared = new ArrayList<>();
        private final List<String> closed = new ArrayList<>();
        private final Map<String, List<ProviderSession>> abiertas = new HashMap<>();
        private volatile boolean falla;

        @Override
        public synchronized void prepararCredencial(String email) {
            prepared.add(email);
        }

        @Override
        public synchronized void closeSessions(String email) {
            if (falla) {
                throw new IdentityProviderFailure("Keycloak no contesta (simulado).");
            }
            closed.add(email);
        }

        @Override
        public synchronized List<ProviderSession> sessionsOf(String email) {
            if (falla) {
                throw new IdentityProviderFailure("Keycloak no contesta (simulado).");
            }
            return List.copyOf(abiertas.getOrDefault(email, List.of()));
        }

        /** Deja a ese correo con una sesion abierta en el proveedor. */
        public synchronized void conSesionAbierta(String email, ProviderSession session) {
            abiertas.computeIfAbsent(email, any -> new ArrayList<>()).add(session);
        }

        public synchronized List<String> prepared() {
            return List.copyOf(prepared);
        }

        public synchronized long vecesQueSePreparo(String email) {
            return prepared.stream().filter(email::equals).count();
        }

        public synchronized List<String> aQuienSeLeCerraronSesiones() {
            return List.copyOf(closed);
        }

        /**
         * Hace que el proveedor falle al cerrar sesiones.
         *
         * <p>Es lo que permite comprobar la decision del issue #19: un Keycloak caido
         * <strong>no deshace</strong> la baja, al contrario que un SMTP caido en una invitacion.
         */
        public void makeItFail() {
            this.falla = true;
        }

        /**
         * Deja el doble como recien creado.
         *
         * <p>Hace falta porque el bean es del contexto y el contexto se comparte entre tests: sin
         * esto, {@link #makeItFail()} de un test hundiria los siguientes y las listas se irian
         * acumulando.
         */
        public synchronized void reiniciar() {
            prepared.clear();
            closed.clear();
            abiertas.clear();
            falla = false;
        }
    }

    /** Cuenta a quien se le pidio cerrar sesiones, en vez de borrar filas de Spring Session. */
    public static class SesionesDePrueba implements Sessions {

        private final List<Set<String>> closed = new ArrayList<>();
        private final Map<String, List<BffSession>> abiertas = new HashMap<>();

        @Override
        public synchronized int closeThoseOf(Set<String> names) {
            closed.add(Set.copyOf(names));
            int habia = (int) names.stream()
                    .flatMap(name -> abiertas.getOrDefault(name, List.of()).stream())
                    .map(BffSession::id)
                    .distinct()
                    .count();
            names.forEach(abiertas::remove);
            // Sin ninguna abierta se devuelve el tamaño del conjunto, que es lo que este doble
            // hacia antes del #22 y de lo que dependen los tests de la baja: alli no interesa el
            // numero sino que se pidiera el cierre.
            return habia > 0 ? habia : names.size();
        }

        @Override
        public synchronized List<BffSession> listThoseOf(Set<String> names) {
            return names.stream()
                    .flatMap(name -> abiertas.getOrDefault(name, List.of()).stream())
                    .distinct()
                    .toList();
        }

        /** Deja una sesion abierta a nombre de ese principal. */
        public synchronized void conSesionAbierta(String name, BffSession session) {
            abiertas.computeIfAbsent(name, any -> new ArrayList<>()).add(session);
        }

        /** Los conjuntos de nombres con los que se pidio cerrar, en orden. */
        public synchronized List<Set<String>> closed() {
            return List.copyOf(closed);
        }

        /** Por lo mismo que en el otro doble: el bean es del contexto y el contexto se comparte. */
        public synchronized void reiniciar() {
            closed.clear();
            abiertas.clear();
        }
    }
}
