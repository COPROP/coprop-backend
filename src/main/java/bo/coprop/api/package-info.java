/**
 * Traduccion entre el dominio y HTTP: el contrato de la API REST.
 *
 * <p>Aqui vive lo que ningun modulo de dominio debe conocer: el formato problem+json, el mapa de
 * codigos de dominio a estados HTTP, el sobre de paginacion, el prefijo de version, la cadena de
 * seguridad y la especificacion OpenAPI.
 *
 * <p>La direccion de la dependencia es siempre api -> dominio, nunca al reves. Por eso la
 * anotacion {@code ApiController} vive en shared: un controlador de identity o de condominium
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
 *
 * <p>Por ese mismo motivo vive aqui {@code AccessControl} desde el issue #15: la cadena de
 * autorizacion completa cruza los dos dominios --la matriz de permisos es de {@code identity} y las
 * relaciones de la unidad son de {@code condominium}-- y este es el unico modulo que ve las dos.
 * Los controladores lo invocan por nombre de bean desde {@code @PreAuthorize}, igual que
 * {@code @authorization}, asi que ninguno de los dos dominios tiene que importarlo.
 *
 * <p>Depende de {@code billing} desde el issue #100, y por una sola razon: el catalogo de
 * parametricas. {@code GET /api/v1/catalogs} publica las enumeraciones del dominio para que ningun
 * cliente las copie a mano, y las dos del concepto de cobro --{@code ChargeConceptType} y
 * {@code PaymentGroup}-- son contrato igual que las de los otros modulos. Es la direccion que
 * permite la regla 6 --api hacia el dominio-- y no cambia nada mas: ningun otro archivo de aqui
 * importa {@code billing}, que tiene su propio controlador.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared", "identity", "condominium", "billing"},
        displayName = "API")
package bo.coprop.api;
