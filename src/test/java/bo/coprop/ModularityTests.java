package bo.coprop;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Hace cumplir los limites entre modulos descritos en ARCHITECTURE.md.
 *
 * <p>Si un modulo importa internals de otro, o depende de uno que no declaro en su
 * {@code package-info.java}, este test falla. Es la red que hace realista la opcion del analisis
 * (seccion 9.1) de extraer {@code payments} como servicio cuando haga falta.
 */
class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(CopropBackendApplication.class);

    @Test
    void losModulosRespetanSusLimites() {
        modules.verify();
    }

    @Test
    void generaElDiagramaDeModulos() {
        new Documenter(modules).writeDocumentation();
    }
}
