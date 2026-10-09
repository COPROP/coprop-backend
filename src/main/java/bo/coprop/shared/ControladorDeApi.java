package bo.coprop.shared;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca un controlador como parte de la API publica versionada.
 *
 * <p>La capa web le antepone el prefijo de version, asi que el controlador declara su ruta sin el:
 * {@code @RequestMapping("/condominios/{condominioId}/unidades")} queda servido en
 * {@code /api/v1/condominios/{condominioId}/unidades}. Lo que no es API versionada -- el receptor
 * de webhooks, por ejemplo -- no lleva esta anotacion y declara su ruta completa.
 *
 * <p>Vive en shared y no en api para que un modulo de dominio pueda anotar su controlador sin
 * declarar una dependencia hacia la capa web, que invertiria la direccion permitida.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ControladorDeApi {}
