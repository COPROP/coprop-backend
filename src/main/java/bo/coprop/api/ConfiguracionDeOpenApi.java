package bo.coprop.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadatos de la especificacion OpenAPI, que springdoc sirve en /api/openapi.json.
 *
 * <p>Se usa el starter sin interfaz grafica: el repositorio es publico y una consola navegable
 * colgada en produccion es una decision aparte, no un efecto secundario de documentar la API.
 */
@Configuration(proxyBeanMethods = false)
class ConfiguracionDeOpenApi {

    @Bean
    OpenAPI especificacionDeCoprop() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("COPROP")
                                .version("v1")
                                .description(
                                        """
                                API de gestion de condominios. Los errores siguen el RFC 9457 \
                                (application/problem+json) y llevan un campo "codigo" estable: \
                                ramifica por ese codigo, nunca por el titulo ni por el detalle, \
                                que pueden cambiar sin aviso.\
                                """));
    }
}
