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
 *
 * <p>Depende tambien de {@code condominium} desde el issue #14, y por eso {@code MeController} vive
 * aqui y no en ninguno de los dos modulos de dominio. {@code GET /api/v1/me} es la unica respuesta
 * que cruza los dos: las membresias y la persona son de {@code identity}, y el nombre del
 * condominio y las unidades relacionadas son de {@code condominium}. Ponerlo en {@code identity}
 * exigiria que ese modulo dependiera de {@code condominium}, que es justo lo que prohibe la regla 2;
 * ponerlo en {@code condominium} invertiria la de {@code identity}. Componer en {@code api} lo que
 * cada dominio publica es la direccion permitida por la regla 6 y no relaja ningun limite.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared", "identity", "condominium"},
        displayName = "API")
package bo.coprop.api;
