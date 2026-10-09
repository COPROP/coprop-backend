package bo.coprop.api;

import bo.coprop.shared.ControladorDeApi;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Antepone el prefijo de version a todo controlador anotado con {@link ControladorDeApi}.
 *
 * <p>Se hace aqui, en un solo sitio, y no repitiendo "/api/v1" en cada {@code @RequestMapping}:
 * asi no depende de que nadie se acuerde, y el dia que exista una v2 el cambio es local.
 */
@Configuration(proxyBeanMethods = false)
class ConfiguracionDeRutas implements WebMvcConfigurer {

    /** Analisis 5.4 define las rutas sin version; el prefijo se acordo al resolver el issue #5. */
    static final String PREFIJO_DE_VERSION = "/api/v1";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurador) {
        configurador.addPathPrefix(PREFIJO_DE_VERSION, HandlerTypePredicate.forAnnotation(ControladorDeApi.class));
    }
}
