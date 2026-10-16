package bo.coprop.identity;

import bo.coprop.identity.ProveedorDeIdentidad.SesionDelProveedor;
import bo.coprop.identity.Sesiones.SesionDelBff;
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
 * no toque ninguna baja: {@link Sesiones} es un puerto que implementa {@code api}, y en un
 * {@code @ApplicationModuleTest} ese modulo no esta, asi que sin este doble el contexto no arranca.
 * Que haga falta es la prueba de que la dependencia va en la direccion correcta: el dominio declara
 * y {@code api} implementa.
 *
 * <p>Los metodos de fabrica no se llaman como los beans de produccion
 * --{@code proveedorDeIdentidadKeycloak}, {@code sesionesDelBff}--: dos definiciones con el mismo
 * nombre dan un {@code BeanDefinitionOverrideException} en vez de sustituirse.
 */
@TestConfiguration(proxyBeanMethods = false)
public class DoblesDeAcceso {

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
    public static class ProveedorDeIdentidadDePrueba implements ProveedorDeIdentidad {

        private final List<String> preparados = new ArrayList<>();
        private final List<String> cerrados = new ArrayList<>();
        private final Map<String, List<SesionDelProveedor>> abiertas = new HashMap<>();
        private volatile boolean falla;

        @Override
        public synchronized void prepararCredencial(String email) {
            preparados.add(email);
        }

        @Override
        public synchronized void cerrarSesiones(String email) {
            if (falla) {
                throw new FalloDelProveedorDeIdentidad("Keycloak no contesta (simulado).");
            }
            cerrados.add(email);
        }

        @Override
        public synchronized List<SesionDelProveedor> sesionesDe(String email) {
            if (falla) {
                throw new FalloDelProveedorDeIdentidad("Keycloak no contesta (simulado).");
            }
            return List.copyOf(abiertas.getOrDefault(email, List.of()));
        }

        /** Deja a ese correo con una sesion abierta en el proveedor. */
        public synchronized void conSesionAbierta(String email, SesionDelProveedor sesion) {
            abiertas.computeIfAbsent(email, cualquiera -> new ArrayList<>()).add(sesion);
        }

        public synchronized List<String> preparados() {
            return List.copyOf(preparados);
        }

        public synchronized long vecesQueSePreparo(String email) {
            return preparados.stream().filter(email::equals).count();
        }

        public synchronized List<String> aQuienSeLeCerraronSesiones() {
            return List.copyOf(cerrados);
        }

        /**
         * Hace que el proveedor falle al cerrar sesiones.
         *
         * <p>Es lo que permite comprobar la decision del issue #19: un Keycloak caido
         * <strong>no deshace</strong> la baja, al contrario que un SMTP caido en una invitacion.
         */
        public void queFalle() {
            this.falla = true;
        }

        /**
         * Deja el doble como recien creado.
         *
         * <p>Hace falta porque el bean es del contexto y el contexto se comparte entre tests: sin
         * esto, {@link #queFalle()} de un test hundiria los siguientes y las listas se irian
         * acumulando.
         */
        public synchronized void reiniciar() {
            preparados.clear();
            cerrados.clear();
            abiertas.clear();
            falla = false;
        }
    }

    /** Cuenta a quien se le pidio cerrar sesiones, en vez de borrar filas de Spring Session. */
    public static class SesionesDePrueba implements Sesiones {

        private final List<Set<String>> cerradas = new ArrayList<>();
        private final Map<String, List<SesionDelBff>> abiertas = new HashMap<>();

        @Override
        public synchronized int cerrarLasDe(Set<String> nombres) {
            cerradas.add(Set.copyOf(nombres));
            int habia = (int) nombres.stream()
                    .flatMap(nombre -> abiertas.getOrDefault(nombre, List.of()).stream())
                    .map(SesionDelBff::id)
                    .distinct()
                    .count();
            nombres.forEach(abiertas::remove);
            // Sin ninguna abierta se devuelve el tamaño del conjunto, que es lo que este doble
            // hacia antes del #22 y de lo que dependen los tests de la baja: alli no interesa el
            // numero sino que se pidiera el cierre.
            return habia > 0 ? habia : nombres.size();
        }

        @Override
        public synchronized List<SesionDelBff> listarLasDe(Set<String> nombres) {
            return nombres.stream()
                    .flatMap(nombre -> abiertas.getOrDefault(nombre, List.of()).stream())
                    .distinct()
                    .toList();
        }

        /** Deja una sesion abierta a nombre de ese principal. */
        public synchronized void conSesionAbierta(String nombre, SesionDelBff sesion) {
            abiertas.computeIfAbsent(nombre, cualquiera -> new ArrayList<>()).add(sesion);
        }

        /** Los conjuntos de nombres con los que se pidio cerrar, en orden. */
        public synchronized List<Set<String>> cerradas() {
            return List.copyOf(cerradas);
        }

        /** Por lo mismo que en el otro doble: el bean es del contexto y el contexto se comparte. */
        public synchronized void reiniciar() {
            cerradas.clear();
            abiertas.clear();
        }
    }
}
