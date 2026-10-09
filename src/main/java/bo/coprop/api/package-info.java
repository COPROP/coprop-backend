/**
 * Traduccion entre el dominio y HTTP: el contrato de la API REST.
 *
 * <p>Aqui vive lo que ningun modulo de dominio debe conocer: el formato problem+json, el mapa de
 * codigos de dominio a estados HTTP, el sobre de paginacion, el prefijo de version, la cadena de
 * seguridad y la especificacion OpenAPI.
 *
 * <p>La direccion de la dependencia es siempre api -> dominio, nunca al reves. Por eso la
 * anotacion {@code ControladorDeApi} vive en shared: un controlador de identity o de condominium
 * puede marcarse sin tener que depender de este modulo.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"},
        displayName = "API")
package bo.coprop.api;
