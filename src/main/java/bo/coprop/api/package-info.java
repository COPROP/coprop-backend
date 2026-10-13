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
 *
 * <p>Depende de {@code identity} desde el issue #13: el filtro que traduce el sujeto del token a la
 * cuenta de COPROP corre en la cadena de seguridad, que vive aqui, y pregunta por el puerto
 * {@code Identities}. Es la direccion permitida --api hacia el dominio-- y no la contraria: quien
 * sabe de HTTP llama a quien sabe de cuentas, nunca al reves.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared", "identity"},
        displayName = "API")
package bo.coprop.api;
