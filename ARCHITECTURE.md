# Arquitectura del backend

Monolito modular en Spring Boot 4 con Spring Modulith. La decisión y sus motivos están en la
sección 9.1 del análisis: con un equipo pequeño, la consistencia entre obligación y pago se
resuelve con una transacción ACID local en lugar de sagas, y el camino de salida queda abierto —
extraer `payments` como servicio propio cuando haga falta, **siempre que los límites se
respeten**.

Esos límites no son una convención escrita: los declara cada módulo en su `package-info.java` y
los hace cumplir `ModularityTests`. Si un módulo importa internals de otro, o depende de uno que
no declaró, el build falla.

## Módulos

| Módulo | Responsabilidad | Depende de |
|---|---|---|
| `shared` | Tipos comunes: identificadores, dinero, periodo, errores de dominio. Módulo abierto. | — |
| `api` | Contrato HTTP: problem+json, paginación, prefijo de versión, seguridad, OpenAPI. | `shared` |
| `identity` | Personas, cuentas de acceso, membresías por condominio, roles, invitaciones. | `shared` |
| `condominium` | Condominios, su configuración, sus unidades, y las tres relaciones de la unidad con las personas: titularidad, ocupación y responsabilidad de pago. | `shared` |
| `billing` | `PaymentObligation` y su máquina de estados; expensas, agua, mora. | `shared`, `condominium`, `identity` |
| `payments` | Intents, QR, inbox de eventos, registro y aplicación de pagos, conciliación. | `shared`, `billing`, `condominium` |
| `notifications` | Envío de avisos. Solo correo en el MVP. | `shared` |
| `audit` | Bitácora inmutable, retención de un año. | `shared` |

## Reglas de dependencia

1. **`shared` no depende de nadie.** Es el único módulo abierto: los demás pueden usar sus
   tipos. No contiene reglas de negocio ni acceso a datos. Un tipo entra ahí si no pertenece a
   ningún dominio en particular, no por ser reutilizable.

2. **`identity` no depende de `condominium`.** Referencia al condominio por identificador, no por
   entidad. Una persona tiene una sola cuenta y una membresía por condominio (Seguridad §2), y
   esa relación se resuelve por id.

   Esta regla decidió dónde viven `UnitOwnership`, `UnitOccupancy` y `BillingResponsibility`.
   Seguridad §2 las cuelga de la persona, así que por el diagrama serían de `identity`; pero las
   tres tienen que responder **404 y no 403** cuando la unidad no es del condominio de la ruta, y
   desde `identity` eso no se puede comprobar sin romper esta regla. Viven en `condominium`, que
   es el módulo de la unidad, y referencian a la persona por identificador —el mismo patrón, en
   la otra dirección—. Moverlas a `identity` más adelante exigiría un puerto en `shared` que
   responda si una unidad pertenece a un condominio.

3. **`payments` es el módulo con los límites más estrictos**, porque es el candidato a
   extraerse. Hacia `billing` se comunica por eventos y por puertos declarados, nunca tocando sus
   entidades. Los adaptadores por proveedor viven en `bo.coprop.payments.providers`, interno al
   módulo: el resto de la aplicación solo ve el puerto `PaymentProvider` y su
   `ProviderCapabilities`. No hay un solo `if (banco == X)` fuera de ahí (§10.1).

4. **`notifications` y `audit` no dependen de nadie salvo `shared`.** Solo reaccionan a eventos.
   Añadir un evento auditable o notificable no obliga a tocar a quien lo origina.

5. **Las transacciones no cruzan módulos.** Un caso de uso que toca dos módulos lo hace por
   evento, no abriendo una transacción compartida. La excepción deliberada es la aplicación de un
   pago: marca obligaciones pagadas, cierra el intent y registra el `Payment` en una sola
   transacción (§12.1), y por eso `payments` depende de `billing`.

6. **`api` depende del dominio; el dominio nunca de `api`.** La traducción a HTTP —el formato de
   error, los estados, el sobre de paginación— vive solo ahí. Un módulo de dominio que importara
   `api` arrastraría HTTP hasta las reglas de negocio y rompería la extracción de `payments`. Por
   eso `@ControladorDeApi`, que un controlador de cualquier módulo necesita, está en `shared` y no
   en `api`.

## Cómo se verifica

```
./gradlew test --tests "bo.coprop.ModularityTests"
```

El test también genera los diagramas de módulos en `build/spring-modulith-docs/`.

Para cambiar un límite: se edita el `allowedDependencies` del `package-info.java` del módulo y se
anota aquí por qué. Un límite que se relaja sin explicación es el primer paso para perder la
opción de extraer `payments`.

## Contrato de la API

Fijado antes del primer endpoint, a propósito: el formato de error, la paginación y el prefijo de
versión tocan todos los endpoints, y cambiarlos después obliga a reescribirlos. Issue #5.

### Rutas y versión

Las rutas van bajo `/api/v1`. **El controlador no lo escribe**: lo antepone
`ConfiguracionDeRutas` a toda clase anotada con `@ControladorDeApi`, así que un controlador
declara `@RequestMapping("/condominios/{condominioId}/unidades")` y queda servido en
`/api/v1/condominios/{condominioId}/unidades`. Lo que no es API versionada —el receptor de
webhooks— no lleva la anotación y escribe su ruta entera.

La anotación vive en `shared` y no en `api` para no invertir la dirección de dependencia: un
controlador de `identity` puede marcarse sin tener que depender de la capa web.

El análisis §5.4 define las rutas sin versión. El prefijo se acordó al resolver el #5, y esa
sección del análisis queda actualizada en consecuencia.

### Errores

Todo error sale en `application/problem+json` (RFC 9457). Sobre los campos del estándar se añaden
tres extensiones:

| Campo | Para qué |
|---|---|
| `codigo` | La parte estable del contrato. El cliente ramifica por él, nunca por `title` ni por `detail`, que pueden cambiar sin aviso. |
| `traceId` | El mismo identificador que llevan todas las líneas de log de esa petición, y que viaja en la cabecera `X-Trace-Id`. Ver [Observabilidad](#observabilidad). |
| `errores` | Solo en validación: un objeto por campo rechazado, con `campo`, `codigo` y `mensaje`. |

El catálogo de códigos es `CodigoDeError`, en `shared`. El mapa a estados HTTP vive en
`CatalogoDeProblemas`, en `api`: **el dominio no conoce HTTP**.

| Situación | HTTP | `codigo` |
|---|---|---|
| Entrada inválida | 422 | `VALIDACION` |
| El cuerpo no es JSON válido | 400 | `CUERPO_ILEGIBLE` |
| Sin token o token inválido | 401 | `NO_AUTENTICADO` |
| Es miembro, pero el rol no alcanza | 403 | `SIN_PERMISO` |
| El token es válido pero no hay cuenta en COPROP | 403 | `SIN_CUENTA` |
| El recurso no existe, o no hay membresía activa en ese condominio | 404 | `NO_ENCONTRADO` |
| Transición inválida o choque de concurrencia | 409 | `CONFLICTO` |
| Petición bien formada que una regla rechaza | 422 | `REGLA_DE_NEGOCIO` |
| Fallo no previsto | 500 | `INTERNO` |

Tres filas merecen explicación:

- **422 en validación, no el 400 que Spring devuelve por defecto.** El 400 es para una petición
  que el servidor no puede ni parsear; una entidad bien formada que no pasa las reglas es 422.
- **400 solo cuando el cuerpo no se puede leer**, y esa es toda la diferencia con el 422. La
  frontera no es "pasó por la validación de Bean Validation o no": un valor que Jackson no puede
  convertir —un enum que no existe, un UUID mal escrito— falla *antes* de que haya objeto que
  validar, y aun así es **422 `VALIDACION` con su lista de campos**, porque el JSON estaba bien
  formado y el cliente necesita saber qué campo corregir. El 400 queda para el cuerpo roto, que
  no tiene campo que señalar. Lo reparte `handleHttpMessageNotReadable`; para un enum, el mensaje
  del campo enumera los valores admitidos, que son parte del contrato.
- **404 y no 403 cuando falta la membresía.** Lo exige Seguridad §7.2: distinguir "no existe" de
  "no puedes" le diría al que pregunta qué condominios hay. Eso deja al 403 con un único
  significado: eres miembro, pero tu rol no alcanza.

El 500 nunca lleva el mensaje de la excepción al cliente, porque puede contener nombres de tabla
o fragmentos de consulta. Al log sí va entero, con su `traceId`.

### Paginación

`?page=0&size=20&sort=campo,desc`, con el sobre `Pagina`: `contenido`, `pagina`, `tamano`,
`total`, `totalPaginas`. **No se serializa el `Page` de Spring Data**: su formato no es contrato
estable y cambia entre versiones. El tope de `size` está en 100.

### Dinero y fechas

Convención fijada aquí, sin código todavía porque aún no hay nada que devuelva importes; entra
con `billing`. Los montos viajan como `{"monto": "1234.56", "moneda": "BOB"}`, **el monto como
string**: §12.2 prohíbe `double`, y un número JSON en JavaScript es exactamente eso. Los
instantes viajan en ISO-8601 UTC; los vencimientos se calculan en `America/La_Paz`.

### OpenAPI

springdoc sirve la especificación en `/api/openapi.json`. Se usa el starter **sin interfaz**
(`springdoc-openapi-starter-webmvc-api`): el repositorio es público y publicar una consola
navegable es una decisión aparte, no un efecto secundario de documentar la API.

Ese starter arrastra Jackson 2 por swagger-core, que convive con el Jackson 3 (`tools.jackson`)
que gestiona Spring Boot 4. El que hay que inyectar es el de Boot.

### Seguridad

Issue #13. `ConfiguracionDeSeguridad` declara **tres cadenas**, una por forma de llegar
(Seguridad §7.1), y se eligen en este orden:

| Cadena | Qué la selecciona | Cómo autentica |
|---|---|---|
| Operación | `/actuator/**` | No autentica a nadie: el health es público y el resto está denegado |
| API móvil | Cabecera `Authorization: Bearer` | `oauth2ResourceServer` con JWT: firma, emisor, audiencia y expiración |
| BFF web | Todo lo demás | `oauth2Login` con sesión en el servidor y CSRF |

La cadena móvil se distingue por la **cabecera y no por la ruta**: las dos sirven `/api/**`, que es
el mismo contrato visto desde dos clientes. Distinguirlas por ruta dejaría dos árboles de URLs que
mantener en paralelo.

**La web no es un cliente público.** El flujo lo corre el backend como cliente confidencial, guarda
los tres tokens en la sesión del servidor y le devuelve al navegador una cookie `HttpOnly`
(Seguridad §5.1). La SPA arranca el login en `GET /bff/login` y cierra sesión con `POST /logout`,
que también la cierra en Keycloak: si solo se borrara la local, el siguiente login entraría solo.

**La sesión vive en la base** (`spring_session`, migración V6), no en memoria. Así sobrevive a un
reinicio y se puede revocar, que es lo que necesita "cerrar sesión en todos los dispositivos" de
§5.3: su columna `PRINCIPAL_NAME` guarda el identificador de la **cuenta de COPROP**.

Una llamada de la SPA que perdió la sesión recibe un **401 en problem+json**, no un 302 hacia
Keycloak: la redirección la seguiría el XHR y el navegador acabaría pintando el login dentro de una
respuesta JSON. El CSRF usa los nombres que Angular trae de serie —cookie `XSRF-TOKEN`, cabecera
`X-XSRF-TOKEN`— para no tener que configurar el cliente.

#### De sujeto de Keycloak a identidad de COPROP

`FiltroDeIdentidad` es el punto donde "autenticado" deja de significar *tiene un token* y pasa a
significar *es alguien aquí*. Resuelve el `sub` contra el puerto `Identities` del módulo `identity`
y sustituye la autenticación por una `IdentidadAutenticada`, cuyo `getName()` es el identificador
de la cuenta. Por eso los controladores no cambiaron: siguen recibiendo un `Principal`, y el actor
que acaba en los eventos y en la bitácora es el que la aplicación conoce, no un UUID de Keycloak.
El mismo valor queda en el MDC bajo `actorId`, la segunda clave que el issue #6 dejó declarada.

**Un token válido de quien no tiene cuenta no pasa de ahí**: responde 403 con `SIN_CUENTA`. Es lo
que pide Seguridad §10.1 —el acceso lo da la invitación, no el haberse autenticado— y es lo que hace
que encender el login con Google no reparta acceso a nadie.

**El vínculo del primer login exige el correo verificado**, y una cuenta ya vinculada no se
revincula a otro sujeto. Lo primero es lo que hace que entrar con contraseña y entrar con Google
caigan en la misma cuenta; lo segundo es la red de seguridad por si alguna vez el realm admitiera
dos identidades con el mismo correo.

Lo que **no** está: la cadena de webhooks por firma, que llega con los pagos (issue #41), cuando
exista `/webhooks/**` y haya una firma que comprobar. Y los roles: `IdentidadAutenticada` no lleva
autoridades todavía porque los roles por condominio se resuelven por petición contra la base
(§4.2), y de eso se encargan el filtro de condominio del #17 y la matriz del #16.

**Todavía no hay forma de crear la primera cuenta.** Toda la API exige identidad, y la identidad
exige una cuenta que alguien tuvo que dar de alta. El arranque de la plataforma —el
superadministrador— es del issue #16; hasta entonces, en local se siembra por SQL.

#### El registro del cliente se arma a mano

`ConfiguracionDeOidc` construye el `ClientRegistration` en Java en vez de declararlo en
`spring.security.oauth2.client.*`. La razón es concreta: con `issuer-uri`, Spring Boot resuelve el
registro con `ClientRegistrations.fromIssuerLocation`, que **descarga el documento de
descubrimiento al arrancar**. Eso significa que la aplicación no levanta si Keycloak está caído
—un reinicio durante una caída dejaría también sin servicio a la app móvil, que solo necesita las
claves y las pide cuando le hacen falta— y que cualquier test que arranque el contexto necesitaría
un Keycloak en pie.

Escribir los extremos a mano en el YAML **no basta**: con `issuer-uri` presente, Boot descubre
igual y los ignora. Armarlo en Java da las dos cosas que hacen falta: ninguna llamada de red al
arrancar y el emisor declarado, que es contra lo que Spring compara el `iss` del id token. Quitar
el emisor para evitar la descarga haría desaparecer esa comprobación sin que nadie lo note.

El resource server sí usa `issuer-uri`: ahí Boot envuelve el decodificador en un
`SupplierJwtDecoder` y la descarga es perezosa.

### El realm de Keycloak

Issue #12. **Un solo realm para toda la plataforma**, en
[`docker/keycloak/coprop-realm.json`](docker/keycloak/coprop-realm.json), que el Compose importa al
arrancar. Un realm por condominio no sirve: la misma persona participa en varios y tendría una
cuenta en cada uno (Seguridad §4.2).

Cuatro clientes, y la diferencia entre los dos primeros es lo que decide dónde vive el token:

| Cliente | Qué es | Dónde acaba el token |
|---|---|---|
| `coprop-web` | Confidencial. El flujo lo corre el **backend**, no el navegador | En la sesión del servidor; el navegador solo recibe una cookie `HttpOnly` |
| `coprop-movil` | Público con PKCE | En el Keychain o Keystore del dispositivo |
| `coprop-api` | Solo portador. Es la **audiencia** de todo access token que llega a la API | No obtiene tokens, solo los valida |
| `coprop-admin` | Client credentials para la Admin API de Keycloak | Solo `manage-users` y `view-users`: no administra el realm |

La web es un BFF porque lo pide Seguridad §5.1, y por eso `redirectUris` apunta al backend
(`/login/oauth2/code/coprop-web`) y no al dev server. **El texto del issue #12 decía "cliente
público para la SPA" y está viejo**: manda el análisis, como con el #11. Con el token fuera del
navegador, `webOrigins` queda vacío: el navegador nunca llama a Keycloak por XHR.

**El token no lleva roles.** Los roles por condominio viven en la base de COPROP y se resuelven en
cada request, para que quitar un rol o terminar un alquiler surta efecto sin esperar a que el token
expire (§4.2). Dos cosas lo garantizan en el realm, y hacen falta las dos: los clientes de usuario
no piden el scope `roles`, y llevan `fullScopeAllowed: false`.

**Ningún secreto literal.** Los tres repos son públicos. El import sustituye `${VARIABLE}` y
`${VARIABLE:defecto}` con el entorno del contenedor, así que todo secreto del archivo es un
marcador con un valor de desarrollo por defecto, y en producción llega por variable de entorno.
`RealmDeKeycloakTests` falla si alguien escribe uno a mano.

**Google autentica, no reparte acceso.** El proveedor está declarado pero **apagado** hasta que se
le den credenciales (`GOOGLE_HABILITADO=true` más el id y el secreto). Con `trustEmail` y el flujo
`vincular por correo verificado`, quien entra por Google cae en la cuenta que ya tiene ese correo
en vez de abrir una segunda. Quien entra sin membresía en ningún condominio no ve nada: el acceso
lo da la invitación (§10.1).

Lo que **no** está todavía: el flujo condicional de MFA con el atributo `requires-mfa` (§4.4), los
claims `amr` y `auth_time` (§5.3) y los tiempos de sesión por rol, que son del issue #22. El realm
fija hoy lo que no depende del rol: access token de 5 minutos, refresh rotativo y revocación de la
sesión entera si se reutiliza uno ya rotado.

## Observabilidad

Issue #6. Sin esto, la conciliación es indepurable en producción: el análisis lo dice en §12.2 y
es la razón de que entre en M0 y no más tarde.

### El `traceId`

Toda línea de log de una petición lleva el mismo `traceId`, que es el criterio de aceptación del
issue. Lo pone `FiltroDeTrazas`, de los primeros de la cadena, para que un fallo de autenticación
—que ocurre antes de cualquier controlador— también lo lleve.

El mismo valor sale por tres sitios, y eso es lo que lo hace útil:

| Dónde | Cómo |
|---|---|
| En cada línea de log | clave `traceId` del MDC |
| En la cabecera de respuesta | `X-Trace-Id` |
| En el cuerpo de un error | campo `traceId` del problem+json |

Así, lo que un usuario reporta se busca tal cual en los logs. Si el cliente manda su propio
`X-Trace-Id` se respeta, para poder seguir una operación que empieza en la app móvil.

**No hay librería de trazas, y es deliberado.** Esto es un monolito sin colector al que exportar
nada, así que Micrometer Tracing hoy solo aportaría el identificador que el filtro ya genera, a
cambio de una dependencia y de vigilar el orden de los filtros. El día que haya un segundo
servicio o un colector OTel, el cambio es sustituir el filtro por el puente de tracing: ni la
clave del MDC ni el nombre del campo cambian.

### Las claves del contexto

Están en `ClavesDeLog`, en `shared`, y no en el módulo que las rellena. La razón: cuando el filtro
de condominio del #17 empiece a poner la suya, tiene que usar exactamente la misma cadena que ya
salía en los logs, o las búsquedas dejan de funcionar sin que nadie se entere.

| Clave | Quién la pone |
|---|---|
| `traceId` | `FiltroDeTrazas`, ya |
| `condominiumId` | el filtro de Seguridad §7.2, con el **#17** |
| `actorId` | la cadena de seguridad, con el **#13** |

Las dos últimas todavía no las rellena nadie: no hay condominios ni identidad hasta M1. El formato
estructurado vuelca el MDC entero, así que aparecerán en los logs sin tocar nada más.

### Formato

En producción, una línea de JSON por evento: `logging.structured.format.console` apunta a
`FormatoJsonDeLog`. En local manda la legibilidad, con el patrón de `logback-spring.xml`, que
antepone `[%X{traceId}]` al mensaje.

`FormatoJsonDeLog` está escrito a mano en vez de usar ECS o Logstash por una razón concreta: todo
lo que sale pasa por `Enmascarador`, incluidos el MDC y el mensaje de la excepción. Envolver un
formato de fábrica para quitarle cosas es más frágil que escribir las ocho claves que se usan.

### Secretos en los logs

`Enmascarador` tapa credenciales, tokens, cuentas, datos de QR, el CI y el correo —los tres
últimos los pide el análisis §12.2 y Seguridad §8—. Las reglas son **por nombre de
clave**, no por forma del valor: reconocer "esto parece un número de cuenta" produce falsos
positivos que destrozan los logs útiles. Las excepciones son el JWT y el encabezado `Bearer`, que
sí tienen forma inconfundible.

**Es una red, no la defensa.** La defensa es no meter un secreto en un log. El enmascarado existe
para el día que alguien registre por descuido el cuerpo entero de una respuesta del banco.

Se aplica en dos sitios porque el nombre de la clave no siempre viaja pegado al valor: dentro del
texto (`enmascarar`) y cuando la clave llega aparte, como en el MDC (`valorDe`). Olvidar el
segundo caso dejaba salir un `token` del MDC entero, y lo encontró el test del criterio.

### Métricas

Micrometer y Actuator, con `/actuator/metrics` expuesto y toda métrica etiquetada con
`application`. Las de negocio llegan con sus módulos; el análisis §12.2 ya fija cuáles, y conviene
respetar estos nombres para no inventarlos dos veces:

| Métrica | Cuándo |
|---|---|
| `coprop.qr.generados` | M3, con la generación de QR |
| `coprop.pagos.confirmados` | M3 |
| `coprop.pagos.latencia_callback_registro` | M3, latencia del webhook al registro |
| `coprop.pagos.detectados_por_polling` | M3; si sube, se están perdiendo webhooks |
| `coprop.conciliacion.excepciones_abiertas` | M4 |
| `coprop.saldos.a_favor_generados` | M4, por semana y proveedor |
| `coprop.proveedor.circuito_abierto` | M3, estado del circuit breaker |

### El health no depende del correo

`management.health.mail.enabled` está en `false`. Un SMTP caído no es motivo para declarar enferma
la aplicación, y menos para que un orquestador la reinicie: el reinicio no arregla un servidor de
correo ajeno. Se descubrió en CI, donde no hay Mailpit y el health raíz devolvía 503 con la
aplicación perfectamente viva. Si interesa vigilar el correo, va como métrica.

## Cómo se escribe un módulo de dominio

La forma la fija el issue #9 con `condominium`, que es el primero. Lo que sigue aplica a los
demás salvo que haya una razón escrita para desviarse.

### Qué se ve desde fuera y qué no

```
bo/coprop/condominium/
  Condominiums.java           la interfaz: lo único que otros módulos usan
  CondominiumView.java        lo que devuelve
  NewCondominium.java         lo que recibe
  CondominiumType.java        enums del dominio
  CondominiumRegistered.java  eventos que otros módulos escuchan
  internal/
    Condominium.java          la entidad JPA, de paquete
    CondominiumRepository.java
    CondominiumsService.java  la implementación
    CondominiumController.java
```

El paquete raíz es la API; `internal` no lo es, y Spring Modulith lo hace cumplir. **Las
entidades JPA son de paquete, no públicas**: así el resto de la aplicación no puede tocarlas ni
por descuido, sin depender de que `ModularityTests` lo pille después. Exponer una entidad ata el
contrato de la API a cómo esté guardado el dato.

Por eso la API es una interfaz y la implementación vive en `internal`: si el servicio estuviera
en la raíz, tendría que ver las entidades y ellas tendrían que ser públicas.

El controlador también va en `internal`. Es un adaptador: nada debe depender de él.

### Nombres

**Lo que el análisis nombra, se llama igual que en el análisis.** El análisis §5.2 nombra las
entidades en inglés —`Condominium`, `Unit`, `PaymentObligation`— y sus valores de enum en español
—`EXPENSA`, `BORRADOR`, `EDIFICIO`—. El código reproduce esa mezcla tal cual, y las tablas y
columnas siguen los mismos nombres en `snake_case`. El motivo es tener cero traducción al leer
§5.2 al lado del código: un nombre traducido es un sitio donde dos personas pueden entender cosas
distintas.

Lo que el análisis no nombra —`api`, `shared`, filtros, manejadores— va en español, como ya
estaba. Las rutas HTTP van en español porque las fija Seguridad §5.4:
`/api/v1/condominios/{condominioId}`.

Los comentarios y el Javadoc, siempre en español y sin acentos en el código Java, con acentos en
los documentos.

### Configuración con vigencia

Lo que puede cambiar y afecta a documentos ya emitidos **no se actualiza en sitio**: se cierra la
fila vigente poniéndole `valid_to` y se abre otra. `condominium_config` lo hace, y el análisis
§5.2 usa el mismo patrón en `LateFeePolicy` y `WaterTariff`.

Es lo que permite responder «¿con qué configuración se emitió esto?» un año después. Una tabla
que se actualiza en sitio no puede responder esa pregunta, y en un sistema de cobros esa pregunta
llega siempre.

Dos detalles que cuesta descubrir solos:

- **La unicidad de «una sola vigente» es un índice parcial**, no un `UNIQUE` normal: PostgreSQL no
  considera iguales dos nulos, así que sin el `WHERE valid_to IS NULL` un condominio podría
  acumular varias filas abiertas sin que nada lo impidiera.
- **Hay que forzar un `flush` entre cerrar la vieja y crear la nueva.** Hibernate ordena todos los
  `INSERT` antes que los `UPDATE` dentro de un mismo flush, así que sin eso la fila nueva entra
  mientras la vieja sigue abierta y el índice la rechaza.

### El reloj

Nadie llama a `Instant.now()`. Se inyecta el `Clock` que declara `CopropBackendApplication`, de
modo que un test puede fijar la hora. En un sistema que calcula mora por días de atraso, poder
mentirle al reloj no es comodidad: es la única forma de probarlo.

### Eventos en lugar de dependencias

Cuando algo que pasa en un módulo le interesa a otro, se publica un evento. `condominium` publica
`CondominiumRegistered` sin conocer a `audit`, que lo consumirá con el issue #21. Es la regla 4 de
más arriba, y es lo que permite añadir un oyente nuevo sin tocar a quien lo origina.

**Toda operación de escritura recibe el actor como último argumento y publica su evento**, con los
valores de antes cuando cambia algo que ya existía (`CondominiumConfigChanged` lleva
`previousIssueDay` y `previousDueDay`). El análisis §12.2 pide una bitácora con actor, acción,
antes y después; si la operación no recibe al actor, esa bitácora no se puede escribir después y
las filas ya creadas se quedan sin autor para siempre. Un alta en lote publica **un** evento con
la lista, no uno por fila: es una sola decisión de una sola persona.

### Aislamiento por condominio

**El condominio es el primer argumento de toda operación del dominio, consultas incluidas.** El
análisis §12.2 exige el `condominium_id` en toda consulta y Seguridad §7.1 pide comprobar que el
recurso pedido pertenece al condominio del contexto. Una firma como `find(unitId)` deja esa
comprobación al criterio de quien llama, y basta olvidarla una vez para servir el recurso de otro
condominio.

Cuando el recurso no es del condominio de la ruta se responde **404, no 403**, igual que si no
existiera: distinguirlos ya diría que existe en algún sitio. El filtro de membresía del issue #17
es la otra mitad del aislamiento; esta mitad no lo espera.

**Toda tabla de negocio lleva `condominium_id`, y lo referencia con clave foránea compuesta.** La
columna la exige §6 del análisis, y una política de Row-Level Security (§12.2) la necesita en la
propia fila: no puede salir a buscarla con un join. Pero una columna denormalizada que hay que
acordarse de mantener termina mintiendo, así que no se declara sola: la tabla referencia a su
padre por el par, no solo por el id.

```sql
-- el padre expone el par
ALTER TABLE unit ADD CONSTRAINT unit_id_condominium_uq UNIQUE (id, condominium_id);

-- y el hijo lo referencia entero
CONSTRAINT unit_ownership_unit_fk
    FOREIGN KEY (unit_id, condominium_id) REFERENCES unit (id, condominium_id)
```

Así es **imposible** que una fila declare un condominio distinto del de su unidad, en vez de
depender de que el servicio lo compruebe —que es el defecto que corrigió el PR #72 en cuatro
sitios—. Lo estableció la V5 para `unit_ownership`, `unit_occupancy`, `billing_responsibility` y
`floor`; las tablas de M2 y M3 que cuelguen de una unidad siguen el mismo patrón.

## Esquema de base de datos

Todo cambio de esquema entra por una migración de Flyway en
`src/main/resources/db/migration`. No se usa generación automática de DDL: `ddl-auto` está en
`validate`, así que el arranque falla si las entidades no coinciden con el esquema migrado.

Convención de nombres: `V<n>__<descripcion_en_snake_case>.sql`, numeración correlativa sin
huecos.

## Referencias al análisis

El documento de análisis es la fuente de las decisiones; este archivo solo describe cómo se
materializan en el código. Cada issue del repositorio cita la sección que lo origina.
