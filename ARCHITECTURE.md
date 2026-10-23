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
| `api` | Contrato HTTP: problem+json, prefijo de versión, seguridad, OpenAPI. | `shared`, `identity`, `condominium`, `billing` |
| `identity` | Personas, cuentas de acceso, membresías por condominio, roles, invitaciones. | `shared`, `notifications` |
| `condominium` | Condominios, su configuración, sus unidades, y las tres relaciones de la unidad con las personas: titularidad, ocupación y responsabilidad de pago. | `shared` |
| `billing` | El catálogo de conceptos de cobro, el periodo de facturación, `PaymentObligation` y su máquina de estados, la configuración de expensas y su emisión por unidad; agua y mora. | `shared`, `condominium`, `identity` |
| `payments` | Intents, QR, inbox de eventos, registro y aplicación de pagos, conciliación. | `shared`, `billing`, `condominium` |
| `notifications` | Envío de avisos. Solo correo en el MVP. | `shared` |
| `audit` | Bitácora inmutable append-only, retención de un año. Escucha `AuditableEvent`. | `shared` |

## Reglas de dependencia

1. **`shared` no depende de nadie.** Es el único módulo abierto: los demás pueden usar sus
   tipos. No contiene reglas de negocio ni acceso a datos. Un tipo entra ahí si no pertenece a
   ningún dominio en particular, no por ser reutilizable.

2. **`identity` no depende de `condominium`.** Su única dependencia, desde el #18, es
   `notifications`: el correo de una invitación lleva su token, y como los eventos se persisten, no
   puede viajar en uno —lo envía quien lo tiene en memoria, por el puerto `Mail`—. Referencia al
   condominio por identificador, no por entidad. Una persona tiene una sola cuenta y una membresía por condominio (Seguridad §2), y
   esa relación se resuelve por id.

   Esta regla decidió dónde viven `UnitOwnership`, `UnitOccupancy` y `BillingResponsibility`.
   Seguridad §2 las cuelga de la persona, así que por el diagrama serían de `identity`; pero las
   tres tienen que responder **404 y no 403** cuando la unidad no es del condominio de la ruta, y
   desde `identity` eso no se puede comprobar sin romper esta regla. Viven en `condominium`, que
   es el módulo de la unidad, y referencian a la persona por identificador —el mismo patrón, en
   la otra dirección—. Moverlas a `identity` más adelante exigiría un puerto en `shared` que
   responda si una unidad pertenece a un condominio.

   **Una clave foránea puede apuntar al contrario que la dependencia de código, y desde el #100 una
   lo hace.** `billing_responsibility` es de `condominium` y su `concept_id` referencia
   `charge_concept`, que es de `billing`; en el código la flecha va al revés —`billing` depende de
   `condominium` y no puede haber ciclo—. La asimetría es deliberada y es lo que hace cumplible el
   criterio del ticket: que una responsabilidad de pago no pueda apuntar al concepto de otro
   condominio **lo rechaza la base**, con una clave compuesta sobre `(id, condominium_id)`, porque
   el servicio que la crea no ve el módulo donde vive el concepto y no tiene forma de comprobarlo.
   La consecuencia conocida es que ese caso llega al cliente como un fallo de integridad y no como
   un 422 con el campo señalado; no es alcanzable desde la interfaz de administración, que elige el
   concepto de una lista del propio condominio.

3. **`payments` es el módulo con los límites más estrictos**, porque es el candidato a
   extraerse. Hacia `billing` se comunica por eventos y por puertos declarados, nunca tocando sus
   entidades. Los adaptadores por proveedor viven en `bo.coprop.payments.providers`, interno al
   módulo: el resto de la aplicación solo ve el puerto `PaymentProvider` y su
   `ProviderCapabilities`. No hay un solo `if (banco == X)` fuera de ahí (§10.1).

4. **`audit` no depende de nadie salvo `shared`.** Reacciona a eventos, y añadir uno auditable no
   obliga a tocarlo.

   Eso no sale gratis: **para escuchar un evento hace falta su tipo**, así que escuchar
   `MembershipGranted` obligaría a depender de `identity`, y después de `condominium`, y de cada
   módulo que naciera. La salida, desde el **#21**, es que lo que se escucha sea una interfaz de
   `shared` —`AuditableEvent`—: los dos lados miran a `shared` y ninguno al otro. Con eso,
   **implementar la interfaz *es* la declaración de que un evento es auditable**; no hay lista en
   ninguna parte.

   `notifications` empezó igual y **ya no**: desde el #18 publica el puerto `Mail` y `identity`
   lo declara, porque el correo de una invitación lleva su token y los eventos se persisten. El
   porqué está en su `package-info` y en *Cómo entra alguien*.

5. **Las transacciones no cruzan módulos.** Un caso de uso que toca dos módulos lo hace por
   evento, no abriendo una transacción compartida. La excepción deliberada es la aplicación de un
   pago: marca obligaciones pagadas, cierra el intent y registra el `Payment` en una sola
   transacción (§12.1), y por eso `payments` depende de `billing`.

6. **`api` depende del dominio; el dominio nunca de `api`.** La traducción a HTTP —el formato de
   error, los estados, el sobre de paginación— vive solo ahí. Un módulo de dominio que importara
   `api` arrastraría HTTP hasta las reglas de negocio y rompería la extracción de `payments`. Por
   eso `@ApiController`, que un controlador de cualquier módulo necesita, está en `shared` y no
   en `api`.

   `api` declara `identity` desde el #13 y **`condominium` desde el #14**. Ese segundo salió de
   `GET /api/v1/me`, la única respuesta que cruza los dos dominios: la persona y las membresías son
   de `identity`, y el nombre del condominio y las unidades relacionadas son de `condominium`.
   Ponerla en `identity` exigiría que ese módulo dependiera de `condominium`, que es lo que prohíbe
   la regla 2; ponerla en `condominium` invertiría la de `identity`. Componer en `api` lo que cada
   dominio ya publica es la dirección que esta regla permite y no relaja ningún límite: lo que
   sigue prohibido —y lo verifica `ModularityTests`— es que un dominio importe al otro o a `api`.

   Y **`billing` desde el #100**, por una sola razón: `GET /api/v1/catalogs` publica las
   enumeraciones del dominio, y las dos del concepto de cobro —`ChargeConceptType` y
   `PaymentGroup`— son contrato igual que las demás. Nada más de `api` importa ese módulo: el
   catálogo de conceptos tiene su propio controlador, dentro de `billing`.

   Desde el **#15** vive aquí por lo mismo `AccessControl`, la cadena de autorización completa: la
   matriz de permisos es de `identity` y las relaciones de la unidad son de `condominium`, y hace
   falta mirar las dos a la vez. Los controladores de los dos dominios lo invocan por nombre de bean
   desde `@PreAuthorize`, así que ninguno lo importa.

   Desde el **#19** hay un caso en la dirección contraria y **no es una excepción, es la inversión de
   siempre**: `identity` declara el puerto `Sessions` y `api` lo implementa con `BffSessions`. La
   baja de una membresía tiene que poder cerrar la sesión del BFF, que vive aquí —la gobierna la
   cadena de seguridad y su tabla la crea la V6 para Spring Session—, y el dominio no puede mirar
   hacia `api`. Declarar arriba y conectar abajo es lo que esta regla permite; la flecha sigue
   apuntando de `api` a `identity`. El precio es que en un `@ApplicationModuleTest` de `identity` ese
   bean no existe y hace falta un doble, que es precisamente la señal de que el límite está puesto.

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
`RoutesConfiguration` a toda clase anotada con `@ApiController`, así que un controlador
declara `@RequestMapping("/condominiums/{condominiumId}/units")` y queda servido en
`/api/v1/condominiums/{condominiumId}/units`. Lo que no es API versionada —el receptor de
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
| `code` | La parte estable del contrato. El cliente ramifica por él, nunca por `title` ni por `detail`, que pueden cambiar sin aviso. |
| `traceId` | El mismo identificador que llevan todas las líneas de log de esa petición, y que viaja en la cabecera `X-Trace-Id`. Ver [Observabilidad](#observabilidad). |
| `errores` | Solo en validación: un objeto por campo rechazado, con `campo`, `code` y `mensaje`. |

El catálogo de códigos es `ErrorCode`, en `shared`. El mapa a estados HTTP vive en
`ProblemCatalog`, en `api`: **el dominio no conoce HTTP**.

| Situación | HTTP | `code` |
|---|---|---|
| Entrada inválida | 422 | `VALIDATION` |
| El cuerpo no es JSON válido | 400 | `UNREADABLE_BODY` |
| Sin token o token inválido | 401 | `NOT_AUTHENTICATED` |
| Operación sensible sin segundo factor reciente | 401 | `REAUTHENTICATION_REQUIRED` |
| Es miembro, pero el rol no alcanza | 403 | `NO_PERMISSION` |
| El token es válido pero no hay cuenta en COPROP | 403 | `NO_ACCOUNT` |
| El recurso no existe, o no hay membresía activa en ese condominio | 404 | `NOT_FOUND` |
| Transición inválida o choque de concurrencia | 409 | `CONFLICT` |
| Petición bien formada que una regla rechaza | 422 | `BUSINESS_RULE` |
| Fallo no previsto | 500 | `INTERNAL` |

Cuatro filas merecen explicación:

- **422 en validación, no el 400 que Spring devuelve por defecto.** El 400 es para una petición
  que el servidor no puede ni parsear; una entidad bien formada que no pasa las reglas es 422.
- **400 solo cuando el cuerpo no se puede leer**, y esa es toda la diferencia con el 422. La
  frontera no es "pasó por la validación de Bean Validation o no": un valor que Jackson no puede
  convertir —un enum que no existe, un UUID mal escrito— falla *antes* de que haya objeto que
  validar, y aun así es **422 `VALIDATION` con su lista de campos**, porque el JSON estaba bien
  formado y el cliente necesita saber qué campo corregir. El 400 queda para el cuerpo roto, que
  no tiene campo que señalar. Lo reparte `handleHttpMessageNotReadable`; para un enum, el mensaje
  del campo enumera los valores admitidos, que son parte del contrato.
- **404 y no 403 cuando falta la membresía.** Lo exige Seguridad §7.2: distinguir "no existe" de
  "no puedes" le diría al que pregunta qué condominios hay. Eso deja al 403 con un único
  significado: eres miembro, pero tu rol no alcanza.
- **401 y no 403 cuando falta el segundo factor**, que es el único 401 de la API que llega con
  sesión válida. El 403 significa "no puedes" y aquí la verdad es "puedes, pero demuéstralo otra
  vez": lo que falta no es el permiso sino la fuerza de la autenticación, y el 401 es el estado que
  lleva un desafío. Lo pide Seguridad §7.3 y lo define el RFC 9470. El ticket #22 había escrito 403
  con código distinguible; el código distinguible está, el estado es el del análisis.

El 500 nunca lleva el mensaje de la excepción al cliente, porque puede contener nombres de tabla
o fragmentos de consulta. Al log sí va entero, con su `traceId`.

### PagedResponseción

El sobre `PagedResponse`: `contenido`, `pagina`, `tamano`, `total`, `totalPagedResponses`. **No se serializa el
`Page` de Spring Data**: su formato no es contrato estable y cambia entre versiones.

El primer endpoint paginado es la consulta de auditoría (#21), y toma `?pagina=0&tamano=50` —no
`page`/`size`—, por coherencia con el sobre que devuelve: lo que se lee en la respuesta es lo que se
escribe en la petición. Su tope de `tamano` está en 200, y existe porque sin él un `tamano=100000`
convierte la consulta en el listado completo que la paginación existe para evitar.

**`PagedResponse` vive en `shared`, no en `api`**, desde ese mismo issue. Nació aquí con el contrato del #5,
cuando ningún endpoint paginaba; el primero que lo hace tiene su controlador en `audit`, y un módulo
de dominio no puede depender de `api` (regla 6). Es el mismo traslado que ya hizo
`@ApiController`, por el mismo motivo, y estaba anotado como deuda desde el #10 —que por esto no
trajo listado de unidades—.

### Dinero y fechas

**En vigor desde el #24**, que es el primer endpoint que devuelve importes. Los montos viajan como
`{"amount": "1234.56", "currency": "BOB"}`, **el monto como string**: §12.2 prohíbe `double`, y un
número JSON en JavaScript es exactamente eso, así que el redondeo que `NUMERIC(14,2)` evita en la
base volvería en la pantalla. Se consigue con `@JsonFormat(shape = STRING)` en el campo, no con una
configuración global del `ObjectMapper`: la alícuota y el porcentaje de copropiedad también son
`BigDecimal` y **no** son dinero, y una regla global las arrastraría.

De entrada se admiten las dos formas —Jackson convierte una cadena a `BigDecimal` sin pedir nada—,
así que un cliente que mande el número no se rompe. Lo que está fijado es la salida.

Los instantes viajan en ISO-8601 UTC; los vencimientos se calculan en `America/La_Paz` y se guardan
como `DATE`, no como marca de tiempo: una obligación vence un día *allí*, y guardarla como instante
obligaría a elegir una hora que nadie decidió.

### OpenAPI

springdoc sirve la especificación en `/api/openapi.json`. En producción se usa el starter **sin
interfaz** (`springdoc-openapi-starter-webmvc-api`): el repositorio es público y publicar una
consola navegable es una decisión aparte, no un efecto secundario de documentar la API.

**La consola existe solo en desarrollo**, en `http://localhost:8080/swagger-ui.html`. El starter con
interfaz entra como `developmentOnly`, que el plugin de Boot pone en el classpath de `bootRun` y
deja fuera del jar: en producción no es que esté apagada por configuración, es que **no está en el
artefacto**. Comprobado: el `bootJar` no contiene ni una entrada de `swagger-ui`.

Sus rutas se abren en la cadena de seguridad **atadas a la misma propiedad que la enciende**
(`springdoc.swagger-ui.enabled`). Con dos interruptores separados —uno para la consola y otro para
abrirle las rutas— lo que pasa tarde o temprano es que quedan en desacuerdo, y el que sobra deja
rutas abiertas que ya no sirven a nada.

Ese starter arrastra Jackson 2 por swagger-core, que convive con el Jackson 3 (`tools.jackson`)
que gestiona Spring Boot 4. El que hay que inyectar es el de Boot.

### Seguridad

Issue #13. `SecurityConfiguration` declara **tres cadenas**, una por forma de llegar
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
§5.3. Su columna `PRINCIPAL_NAME` guarda hoy el **`sub` de Keycloak** y no el identificador de la
cuenta: Spring Security no persiste el contexto que `IdentityFilter` cambia a mitad de petición.
Por eso el puerto `Sessions` recibe un **conjunto de nombres** y no uno solo —el `sub` y el de la
cuenta—, para que el día que eso cambie no se rompa en silencio.

Una llamada de la SPA que perdió la sesión recibe un **401 en problem+json**, no un 302 hacia
Keycloak: la redirección la seguiría el XHR y el navegador acabaría pintando el login dentro de una
respuesta JSON. El CSRF usa los nombres que Angular trae de serie —cookie `XSRF-TOKEN`, cabecera
`X-XSRF-TOKEN`— para no tener que configurar el cliente.

#### De sujeto de Keycloak a identidad de COPROP

`IdentityFilter` es el punto donde "autenticado" deja de significar *tiene un token* y pasa a
significar *es alguien aquí*. Resuelve el `sub` contra el puerto `Identities` del módulo `identity`
y sustituye la autenticación por una `AuthenticatedIdentity`, cuyo `getName()` es el identificador
de la cuenta. Por eso los controladores no cambiaron: siguen recibiendo un `Principal`, y el actor
que acaba en los eventos y en la bitácora es el que la aplicación conoce, no un UUID de Keycloak.
El mismo valor queda en el MDC bajo `actorId`, la segunda clave que el issue #6 dejó declarada.

**Un token válido de quien no tiene cuenta no pasa de ahí**: responde 403 con `NO_ACCOUNT`. Es lo
que pide Seguridad §10.1 —el acceso lo da la invitación, no el haberse autenticado— y es lo que hace
que encender el login con Google no reparta acceso a nadie.

**El vínculo del primer login exige el correo verificado**, y una cuenta ya vinculada no se
revincula a otro sujeto. Lo primero es lo que hace que entrar con contraseña y entrar con Google
caigan en la misma cuenta; lo segundo es la red de seguridad por si alguna vez el realm admitiera
dos identidades con el mismo correo.

**Las autoridades se ponen en dos sitios**, y por eso son dos filtros: la de plataforma la pone
este, porque no depende de ningún condominio y vale también en las rutas globales; las del
condominio las añade `CondominiumFilter` leyendo la membresía de la ruta (§4.2). Ninguna viaja en
el token: se resuelven por petición, así que quitarle un rol a alguien surte efecto en la
siguiente y no cuando expire su token.

Lo que **no** está: la cadena de webhooks por firma, que llega con los pagos (issue #41), cuando
exista `/webhooks/**` y haya una firma que comprobar.

#### Tiempos de sesión y reautenticación

Issue #22, Seguridad §5.3, §7.3 y §8.

**Los tiempos de sesión no son configuración, y no pueden serlo.** La política es una hora de
inactividad para los roles administrativos y veinticuatro para el resto, con un tope de
veinticuatro en todo caso (§10.1); Spring Session tiene un único tiempo para toda la aplicación y
lo que distingue a un administrador de un propietario no está en la sesión, sino en una membresía
por condominio de la base de COPROP (§4.2). La misma cuenta puede administrar un edificio y ser
propietaria en otro. Lo resuelve `SessionValidityFilter`, **por petición**:

| Qué | Cómo |
|---|---|
| Inactividad | 1 h si la cuenta tiene algún rol administrativo en algún condominio, o administra la plataforma; 24 h si no |
| Tope absoluto | 24 h desde la creación de la sesión, comprobado contra `getCreationTime()` |
| Dónde corre | Solo en la cadena del BFF: la móvil es `STATELESS` y sus tiempos los gobierna el realm |

Las dos mitades son distintas y hacen falta las dos: Spring Session solo conoce **inactividad**, y
una sesión que se usa cada cincuenta minutos no caducaría nunca. Contra una sesión robada, lo que
la cierra es el tope, porque el ladrón la mantiene viva usándola. Al pasarse, la sesión se invalida
en el acto y la petición responde 401.

Cuesta **una consulta por petición** y se paga a propósito: decidirlo al crear la sesión dejaría a
quien acaba de recibir un rol administrativo con una sesión de 24 horas hasta su siguiente login.
Al revés no hace falta, porque a quien *pierde* un rol administrativo se le cierran las sesiones en
el acto desde el #20.

**El step-up es el cuarto control de la cadena** (§7.2) y lo añaden solo las operaciones sensibles
que §7.3 enumera. Lo comprueba `ReauthenticationControl` (bean `@reauthentication`) sobre dos
afirmaciones del token que `IdentityFilter` guarda en `AuthenticatedIdentity`: `amr` —`otp` o
`hwk`; `pwd` no es un segundo factor— y `auth_time`, que tiene que ser de hace menos de cinco
minutos. No es lo mismo que la expiración del token: uno recién renovado trae `iat` de hace un
segundo y `auth_time` de hace tres días.

```java
@PreAuthorize("@authorization.canChange(...) and @reauthentication.recent(authentication)")
```

**El orden de la expresión no es estético.** SpEL corta en el primer `false`, así que la
autorización va primero y a quien no tiene permiso se le responde 403 sin pedirle nada: al revés le
mandaríamos a buscar su teléfono para recibir el mismo 403, y de paso le confirmaríamos que la
operación existe. Por lo mismo, el 404 de un condominio ajeno sigue ganando.

Dónde se pide hoy, de las cinco operaciones sensibles de §7.3 —las otras cuatro son de M2 y M3:

| Ruta | Cuándo |
|---|---|
| `PUT .../memberships/{id}/roles` | Siempre: el cuerpo trae los roles que quedan, no los que se van, y quitar `CONDO_ADMIN` se escribe mandando un cuerpo sin él |
| `POST /platform-admins` y `.../revocation` | Siempre |
| `POST .../memberships` y `POST .../invitations` | Solo si el cuerpo trae un rol administrativo: lo sensible es nombrar a un administrador, no dar de alta a un propietario |

`PLATFORM_ADMIN` **no** dispara la condición de las dos últimas: ese rol no cabe en una membresía
ni en una invitación y las dos rutas lo rechazan con 422 venga de quien venga, así que pedir el
factor solo retrasaría la misma negativa.

**Las sesiones activas son dos mitades** (§8), y la lista las junta: `GET /api/v1/me/sessions`
devuelve las del BFF —con su caducidad, que esta aplicación gobierna— y las del proveedor de
identidad —con su IP, que la tabla de la V6 no guarda—. Una lista que mirara solo una de las dos
diría que no hay nadie dentro mientras la app móvil sigue renovando su token.
`POST /api/v1/me/sessions/closure` las cierra todas, **incluida la que hace la petición**, y deja
fila en la bitácora de plataforma. Un proveedor caído devuelve media lista y no un error: quien
mira esa pantalla está averiguando si le robaron la cuenta.

#### El registro del cliente se arma a mano

`OidcConfiguration` construye el `ClientRegistration` en Java en vez de declararlo en
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
`KeycloakRealmTests` falla si alguien escribe uno a mano.

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
issue. Lo pone `TraceFilter`, de los primeros de la cadena, para que un fallo de autenticación
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

Están en `LogKeys`, en `shared`, y no en el módulo que las rellena. La razón: cuando el filtro
de condominio del #17 empiece a poner la suya, tiene que usar exactamente la misma cadena que ya
salía en los logs, o las búsquedas dejan de funcionar sin que nadie se entere.

| Clave | Quién la pone |
|---|---|
| `traceId` | `TraceFilter` (#6) |
| `actorId` | `IdentityFilter` (#13) |
| `condominiumId` | `CondominiumFilter` (#17) |
| `ip` | `TraceFilter` (#21) |

Las cuatro están rellenas. El formato estructurado vuelca el MDC entero, así que aparecen en los
logs sin tocar nada más.

**La `ip` no está solo para los logs**: el análisis §5.2 la pide en `AuditLog`, y ningún evento de
dominio puede llevarla dentro —un evento no sabe de HTTP—. El MDC es el único sitio donde el
contexto de la petición está disponible para quien escribe la bitácora, y de eso salió una decisión
de diseño que está más abajo, en *La bitácora*.

No se lee `X-Forwarded-For` a mano: detrás de un proxy lo resuelve
`server.forward-headers-strategy`, que en producción está puesto, y hacerlo aquí además significaría
confiar en una cabecera que cualquiera puede escribir.

### Formato

En producción, una línea de JSON por evento: `logging.structured.format.console` apunta a
`JsonLogLayout`. En local manda la legibilidad, con el patrón de `logback-spring.xml`, que
antepone `[%X{traceId}]` al mensaje.

`JsonLogLayout` está escrito a mano en vez de usar ECS o Logstash por una razón concreta: todo
lo que sale pasa por `Masker`, incluidos el MDC y el mensaje de la excepción. Envolver un
formato de fábrica para quitarle cosas es más frágil que escribir las ocho claves que se usan.

### Secretos en los logs

`Masker` tapa credenciales, tokens, cuentas, datos de QR, el CI y el correo —los tres
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

**Si lo lee el compilador, la base de datos o un cliente HTTP, va en inglés. Si solo lo lee una
persona, se queda en español.** Esa es la regla entera, y resuelve sola casi todos los casos.

| Caso | Idioma |
| --- | --- |
| Clases, métodos, variables, parámetros, campos | Inglés |
| Tablas, columnas, índices, restricciones, funciones | Inglés |
| Valores de enumeración y nombres de acción de la bitácora | Inglés: se guardan y viajan en la API |
| Segmentos de ruta HTTP y códigos de error | Inglés: son contrato |
| Nombres de método de test | Inglés |
| Javadoc y comentarios | **Español** |
| `@DisplayName` | **Español**: es una frase para leer, no un identificador |
| Mensajes de error al usuario | **Español**: es la lengua del producto |
| Nombres de rama y mensajes de commit | **Español** |

Dos consecuencias que conviene ver de frente. Un test queda así, y está bien:

```java
@DisplayName("las invitaciones quedan revocadas al dar de baja la membresia")
void theInvitationsAreRecorded() { ... }
```

Y un javadoc en español describe un método en inglés. También está bien: es la combinación normal
en un equipo que habla español y escribe código en inglés.

**Un comentario que cita un identificador sí se actualiza**, aunque la prosa alrededor siga en
español: `{@link #isLive}`, no `{@link #estaViva}`.

**Qué se perdió al cambiar la convención, y se aceptó a sabiendas.** Hasta el 19 de octubre de 2026
esta sección decía lo contrario: los valores de enum y las rutas iban en español para tener **cero
traducción al leer §5.2 del análisis al lado del código**. Ese beneficio desaparece: ahora el
análisis dice `EDIFICIO` y el código dice `BUILDING`, y quien lea los dos tiene que traducir. Se
cambió igual porque un código mitad en inglés y mitad en español obliga a decidir el idioma en cada
nombre nuevo, y esa decisión se resolvía distinto cada vez. **El glosario de la tabla de abajo es
lo que sustituye a esa correspondencia directa.**

| Análisis | Identificador |
| --- | --- |
| condominio | `condominium` |
| expensas | `dues` |
| mora | `lateFee` |
| concepto de cobro | `chargeConcept` |
| grupo de pago | `paymentGroup` |
| periodo de facturación | `billingPeriod` |
| obligación de pago | `paymentObligation` |
| convenio de pago | `agreement` |
| alícuota | `aliquot` |
| saldo a favor | `creditBalance` |
| estado de cuenta | `accountStatement` |
| morosidad | `delinquency` |
| conciliación | `reconciliation` |
| área común | `amenity` |
| bitácora | `auditLog` |
| paramétricas | `catalog` |
| responsable de pago | `billingResponsible` |
| inquilino | `tenant` |
| ocupante | `occupant` |

**Cuidado con los nombres que ya existen en una librería.** `Pagina` no pudo ser `Page` por
`org.springframework.data.domain.Page`, `ErrorDeCampo` no pudo ser `FieldError` por
`org.springframework.validation.FieldError`, y el record `Autenticacion` no pudo ser
`Authentication` por el de Spring Security. Quedaron `PagedResponse`, `ApiFieldError` y
`AuthenticationFacts`. El error que sale en esos casos es `incompatible types`, que no menciona la
colisión.


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
- **Ordenar el historial por `valid_from DESC` no alcanza.** Dos cambios seguidos caen en el mismo
  instante —el reloj de Windows tiene una granularidad de unos 15 ms— y entonces el orden es el que
  devuelva la base, distinto entre dos peticiones. El desempate es `valid_to DESC NULLS FIRST`, que
  además pone la vigente arriba; y va escrito en la consulta, no dejado al dialecto. Lo descubrió un
  test del #24, no un usuario.

`expense_setting` (#24) es el tercer caso del patrón, y el primero donde la fila cerrada se
*referencia*: cada obligación de expensas guarda en su `source_id` la configuración con la que se
calculó, así que «¿con qué monto se emitió esto?» no se contesta buscando por fechas, se contesta
siguiendo un identificador.

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
existiera: distinguirlos ya diría que existe en algún sitio.

#### El perfil y la selección de condominio

Issue #14. `GET /api/v1/me` es la primera llamada de todo cliente después del login: devuelve la
persona, la cuenta, si administra la plataforma, y un elemento por condominio con **los roles de esa
membresía y las unidades con las que tiene relación vigente**. Si trae uno, el cliente entra directo;
si trae varios, pinta un selector.

Vive en `api` —`MeController`— y no en un módulo de dominio porque es la única respuesta que cruza
`identity` y `condominium`; el porqué está en la regla 6.

Tres decisiones que no son obvias:

- **No recibe ningún identificador.** Todo sale de la identidad que dejó el filtro. Pedir la cuenta
  en la ruta convertiría "mi perfil" en "el perfil de quien yo diga" y obligaría a decidir quién
  puede verlo; la consulta por identificador ya existe aparte, en `/api/v1/accounts/{accountId}`.
- **Sin `@PreAuthorize`.** No hay nada que autorizar: solo devuelve lo propio de quien pregunta, y
  quien no tiene cuenta no llegó hasta aquí —el filtro de identidad responde 403 `NO_ACCOUNT`—.
  Exigir un permiso dejaría sin perfil justo a quien acaba de entrar y aún no tiene membresía.
- **200 con lista vacía a quien no tiene ninguna membresía activa**, ni 404 ni 403: es una respuesta
  correcta a una pregunta sobre uno mismo. Es también el caso del superadministrador recién abierto
  por el arranque, que es quien crea el primer condominio.

El superadministrador va en un campo propio, `administraLaPlataforma`, y no como un rol más en la
lista de algún condominio: su alcance es la plataforma (Seguridad §3), y meterlo ahí le haría creer
al cliente que ese condominio se lo concedió.

**El condominio activo se elige por el segmento de ruta y no por una cabecera.** El issue admitía
las dos ("por cabecera o por segmento de ruta") y el análisis §5.4 fija la ruta:
`/api/v1/condominiums/{condominiumId}/...`. Se implementa solo esa, y no las dos, porque una cabecera
sería una segunda forma de nombrar el condominio que el filtro —que lee la ruta— no ve: dos fuentes
que pueden discrepar, y la que no se comprueba es la que acaba decidiendo. Una membresía
**suspendida** no se lista por lo mismo: el filtro le responde 404 a todo lo de ese condominio, así
que ofrecerla en el selector sería ofrecer una puerta cerrada.

Lo sostiene `MeTests`, con más casos de lo que *no* sale que de lo que sale: es la única respuesta de
la API que habla de varios condominios a la vez, así que es por donde se escaparía la lista de
condominios de la plataforma.

#### El filtro de membresía

Issue #17. `CondominiumFilter` lee el condominio de la ruta, carga la membresía vigente de quien
pide y, si no hay o no está activa, responde **404**. Es la puerta: a partir de ahí, toda petición
a `/api/v1/condominiums/{id}/...` llega con un condominio que ese actor puede ver, y el condominio
que reciben los servicios como primer argumento es el mismo de la ruta.

Lee la ruta con una expresión regular y no un `@PathVariable` porque corre **antes de que exista un
controlador**, que es precisamente lo que lo hace valer para todos los endpoints a la vez en lugar
de uno por uno.

Los roles de la membresía quedan como autoridades de la petición. Hoy no los mira nadie —la matriz
es el issue #16— pero es donde Spring los va a buscar, y cargarlos aquí evita que esa matriz vuelva
a consultar la membresía en cada comprobación.

Lo que lo sostiene es `CrossCondominiumLeakTests`: los treinta y tres endpoints acotados a un condominio,
cada uno pedido desde el condominio ajeno —tiene que dar 404— y desde el propio —tiene que dar
cualquier cosa menos 404—. **La segunda mitad no es decorativa**: sin ella, una ruta mal escrita
daría 404 en las dos y la batería entera pasaría sin comprobar nada. Pasó: cazó un cuerpo de
petición que usaba una cuenta inexistente.

#### Quién puede qué

Issue #16. La matriz de Seguridad §3 vive transcrita en `PermissionMatrix`, en el módulo `identity`,
y es el único sitio donde se decide. **Es código y no una tabla** —el issue pide "permisos derivados
del rol, no almacenados por usuario"— porque una tabla se edita a mano: el día que alguien cambie
una fila en producción, el análisis dirá una cosa y el sistema otra, y nadie se enterará hasta que
alguien vea lo que no debía. Aquí un cambio pasa por un PR y rompe un test.

Están las veintiuna filas, incluidas las de acciones cuyos endpoints son de M2 y M3. La decisión ya
está tomada en el análisis, y escribirla entera permite probarla fila por fila hoy; lo que llega con
cada funcionalidad es la anotación que exige el permiso, no la decisión.

Cada celda tiene tres valores, no dos: `ALL` es el "Sí" de la matriz, `NONE` el "—", y **`OWN`
es el "Propias"** de las columnas del propietario y el inquilino. Esa palabra no es un sí más
pequeño: es una condición sobre *cuál* recurso, y quien la resuelve es la cadena del issue #15
—la sección siguiente—. Tratarla como un sí abriría las unidades del vecino; como un no, dejaría al
propietario sin su estado de cuenta.

Los controladores preguntan con `@PreAuthorize("@authorization.can(authentication, 'X')")`. El bean
se referencia **por nombre**, que es una cadena: por eso un controlador de `condominium` lo usa sin
importar nada de `identity` y sin romper la regla de dependencias. El precio es que el compilador no
ve ese uso —un permiso mal escrito revienta en ejecución, no al compilar— y por eso cada endpoint
protegido tiene su caso en `ApiPermissionsTests`.

No hace falta consultar la base: los roles del condominio de la ruta ya los dejó el filtro del #17
como autoridades de la petición. Solo se baja a la base en las rutas que no cuelgan de un condominio
—el alta de una persona—, donde no hay autoridades de condominio que mirar.

#### Y sobre cuál recurso

Issue #15. La cadena de Seguridad §7.2 tiene cuatro eslabones y los tres primeros ya estaban: la
identidad (#13), la membresía y los roles del condominio (#17), y si el rol hace esa clase de cosas
(#16). Falta el que ninguno de ellos puede contestar: **si *este* recurso, el de la ruta, es suyo.**
Es el `canPayFor` del ejemplo del análisis, y lo resuelve `AccessControl`.

Vive en `api`, con el bean `acceso`, porque es **el único módulo que ve las dos mitades**: la matriz
es de `identity` y las relaciones de la unidad son de `condominium`, y las reglas 2 y 6 prohíben que
esos dos se miren entre sí. Mismo motivo que `MeController`, y no relaja ningún límite: `api`
compone lo que cada dominio ya publica. Los controladores lo invocan por nombre de bean desde
`@PreAuthorize`, igual que `@authorization`.

**Las tres relaciones cuentan por igual** —`UnitOwnership`, `UnitOccupancy` y
`BillingResponsibility`—, no solo la titularidad. El propietario que alquiló ya no ocupa y sigue
pagando; el inquilino residente tiene que poder subir su comprobante. Reducirlo a lo que la palabra
"propias" sugiere dejaría a uno de los dos fuera de lo suyo. Lo contesta `UnitRelations.isRelatedTo`
con tres `EXISTS` y sin traer una fila, porque se pregunta en cada petición, y **lleva el condominio
en el WHERE**: así una unidad de otro condominio no empareja nunca y quien pregunta no tiene que
comprobar aparte de quién es la unidad.

**Las dos negativas no responden lo mismo, y es el criterio del issue:**

| Qué falla | Respuesta | Por qué |
| --- | --- | --- |
| El rol no hace esto (`NONE`) | **403** `NO_PERMISSION` | La respuesta depende solo de los roles de quien pregunta, no de qué recurso pidió: no hay existencia que filtrar. |
| El recurso no es suyo (`OWN` sin relación) | **404** `NOT_FOUND` | Aquí sí depende del recurso. Un 403 confirmaría que esa unidad existe, y con eso un vecino enumera el edificio probando identificadores. |

El 404 sale de una **excepción lanzada dentro de la expresión de `@PreAuthorize`**, no de un
`false`: devolver `false` siempre acaba en 403. SpEL deja pasar sin envolver las excepciones no
comprobadas del método que invoca, así que `ErrorHandler` ve el `ResourceNotFound` y lo
traduce. Es un detalle del que depende el criterio entero y no está a la vista en el código, así que
lo prueba un caso propio.

Dos endpoints de hoy pasan por aquí, y cada uno muestra una mitad:

- `GET .../units/{id}/owners` va por `VIEW_ACCOUNT_STATEMENT`, que es una fila con "Propias":
  los dos administradores ven cualquier unidad, el propietario y el inquilino solo la suya —otra da
  404—, y portería y el directorio reciben 403. Las tres salidas de la cadena en una sola fila.
- `GET .../units/{id}` no tiene fila en la matriz —sus veintiuna son acciones sobre dinero y
  administración, ninguna dice "ver una unidad"—, así que **la decisión se toma en el endpoint y
  queda escrita ahí**, como ya se hizo con `CONFIGURE_CHARGES` en la configuración del condominio:
  la ve quien administra unidades, y cualquier otro miembro solo aquellas con las que tiene relación
  vigente. La consecuencia conocida es que `FINANCE_ADMIN` recibe 404 en la ficha de una unidad; su
  caso de uso real es un listado para cobrar, que llega con M2 y traerá su propio permiso.

`GET /api/v1/condominiums/{id}` **no pide permiso a propósito**: lo único que hace falta para verlo
es pertenecer, y eso ya lo exigió el filtro del #17. Los siete roles lo ven, porque un propietario
necesita el nombre y los días de emisión y vencimiento del edificio donde vive tanto como su
administrador. Exigir un permiso significaría elegir una fila que no habla de esto y dejar a
portería sin saber en qué edificio está.

Fuera del condominio, `GET /people/{id}` y `GET /accounts/{id}` añaden **lo propio** a la regla del
alta: la suya, o cualquiera si administra algún condominio. Sin esa excepción, la cabecera `Location`
del alta de una cuenta apuntaría a una ruta que a su dueño responde 403. Niegan con 403, no con 404,
porque la ruta es global y la respuesta no cambia con el identificador.

Lo que lo sostiene es `AuthorizationChainTests`: **los cuarenta y cinco endpoints por los siete roles**, con
el resultado declarado celda a celda y un caso que falla si una fila se olvida de un rol. Es el
mismo descuido que en el #16 dejó un permiso de menos en `CONDO_ADMIN`, y lo cazó justo el test que
exigía las columnas completas. Dos cosas que esa rejilla enseñó y conviene no volver a descubrir:

- **La validación del cuerpo corre antes de la autorización.** Spring resuelve los argumentos del
  método —y con ellos el `@Valid`— antes de invocarlo, y `@PreAuthorize` es un consejo alrededor de
  esa invocación. Un cuerpo inválido da 422 a quien no tiene permiso, así que una celda con un
  cuerpo mal formado no prueba la autorización.
- Para los permitidos se comprueba que **no sea ninguna de las dos negativas**, no un 200 exacto:
  que un alta repetida acabe en 422 es asunto de su propio test, y atarlo aquí convertiría la
  rejilla en una batería funcional de ciento cuarenta casos.

Lo que esta cadena todavía no hace es el **segundo factor** de las tres celdas "Sí (step-up)":
`PermissionMatrix.pideSegundoFactor` ya las nombra y hacerlo cumplir es el issue **#22**. Y la
segunda barrera que §7.2 pide en su punto 5 —RLS— sigue evaluada y sin implementar, más abajo.

#### El superadministrador y el arranque de la plataforma

`PLATFORM_ADMIN` es el único rol con alcance de plataforma, así que **no cabe en `membership`**, que
existe para atar una cuenta a un condominio. Vive en su propia tabla (migración V7). Seguridad §4.2
lo dejaba abierto —"puede ser un rol de realm"— y se eligió la tabla por tres razones: los roles se
resuelven en cada petición, y el superadministrador es justo el que no conviene tardar en revocar; el
token no lleva roles a propósito, y meter uno obligaría a devolverle el scope `roles` que el #12 le
quitó; y el realm se reconstruye desde un archivo versionado, así que otorgarlo exigiría editarlo o
llamar a la Admin API, mientras que aquí es un `INSERT`.

Concederlo y revocarlo a otra cuenta llegó con el **#20**, en
[Quién reparte los roles](#quién-reparte-los-roles): hasta entonces sólo lo abría el arranque, así que
la plataforma podía empezar pero no crecer.

**El primero lo abre la aplicación al arrancar**, si no hay ninguno y hay un correo en
`coprop.superadministrador-inicial` —igual que Keycloak con `KC_BOOTSTRAP_ADMIN`—. No es una puerta
trasera permanente: en cuanto existe uno, la propiedad deja de hacer nada. Y la cuenta nace **sin
vincular**: la propiedad dice a quién se le abre la puerta, no entra por él.

Con eso, una base limpia arranca sola. Comprobado de punta a punta: el superadministrador crea un
condominio, **no puede operarlo** —§10.1: no opera condominios—, se concede a sí mismo la primera
membresía `CONDO_ADMIN`, y a partir de ahí administra. Lo que sigue sin poder, ni siquiera entonces,
es tocar lo que se cobra: la separación de funciones no tiene excepciones.

El filtro del #17 deja pasar al superadministrador sin membresía, porque sin eso un condominio recién
creado no tendría forma de tener a nadie dentro. Lo que puede hacer una vez dentro lo decide la
matriz, y ahí casi todo le dice que no. **Las autoridades se suman, no se sustituyen**: quien
administra la plataforma y además es miembro de un condominio tiene las dos cosas a la vez.

#### Cómo entra alguien: la invitación

Issue #18. **No hay registro abierto: solo se entra por invitación** (Seguridad §6). La razón no es
comodidad: si cualquiera pudiera registrarse y declararse propietario del Dpto. 302, vería deudas
ajenas. La invitación nace de un dato que el administrador ya validó —la persona y su relación con
la unidad— y es lo que ata una cuenta a un condominio con unos roles.

**Es una membresía diferida**: la invitación lleva los roles y la membresía nace al aceptarse. La vía
directa, `Memberships.grant`, se queda para la primera membresía de un condominio, que concede el
superadministrador porque no hay nadie dentro a quien invitar.

El token son **256 bits de `SecureRandom`**, no un UUID: un UUID tiene 122 bits y, lo que importa
más, *parece* un identificador, así que acaba en un log o en un ticket de soporte. De la base solo
queda su **SHA-256**, sin sal —no hay diccionario que recorrer con 256 bits aleatorios, y sin sal se
busca por índice en una consulta—. Vive siete días y sirve una vez.

**El token no viaja en ningún evento, y eso decidió una dependencia.** Los eventos de este sistema
se persisten (migración V2), así que un `InvitationIssued` con el token dentro lo dejaría legible en
la base, que es justo lo que el hash evita. Por eso el correo lo envía quien tiene el token en
memoria —la emisión— a través del puerto `Mail`, y por eso **`identity` declara `notifications`**:
es su única dependencia. El módulo `notifications` seguía la regla de "nadie lo llama, solo reacciona
a eventos", y esta es la excepción, escrita en su `package-info`.

El correo se manda **dentro de la transacción y como último paso**: si el servidor de correo falla,
la emisión entera se deshace. Una invitación que nadie recibió no sirve, y es mejor que el
administrador vea el error que una fila viva con un enlace que no llegó. Queda una ventana —correo
entregado y transacción que no confirma— y su arreglo es un reenvío.

**Los tres cierres no son un estado almacenado.** Aceptada y revocada son fechas; vencida se deduce
del reloj. Un estado `EXPIRADA` habría que ponerlo con una tarea periódica y, entre que vence y que
alguien la marque, la fila diría que sigue viva. El diagrama de §6 dibuja EXPIRADO en la vida de la
*cuenta*; aquí lo que vence es el token.

El camino completo, y por qué tiene dos llamadas:

1. `POST /condominiums/{id}/invitations` — emite y manda el correo. Pide **poder conceder esos
   roles**, la misma regla que el alta directa de membresía, porque es lo mismo diferido.
2. El enlace del correo apunta a la **web**, no a la API: la página es la que sabe qué mostrar en
   cada caso y la que manda al login. Una ruta de la API obligaría al backend a devolver
   redirecciones y a conocer las rutas de la SPA.
3. `POST /invitations/opening` — **pública**, porque quien acaba de recibir el enlace no tiene con
   qué autenticarse; lo que la autoriza es el token. Resuelve los dos casos de §6.1: si ese correo no
   tiene credencial, se le crea el usuario en Keycloak y se le pide al proveedor que le escriba para
   poner su contraseña; si ya participa en otro condominio, solo le toca iniciar sesión. **No consume
   el token**: abrirlo dos veces vuelve a mandar el correo, porque el correo del proveedor se pierde
   más fácil que el nuestro.
4. `POST /invitations/acceptance` — ya **autenticada**. Sella la invitación y concede la membresía.
   Para llegar aquí el filtro del #13 ya vinculó el sujeto del proveedor con la cuenta de COPROP por
   el correo verificado, y eso es lo que hace que entrar con contraseña y entrar con Google caigan en
   la misma cuenta.

**El token viaja en el cuerpo, no en la ruta.** Es una credencial: en la ruta acabaría en el log de
accesos, en el historial del navegador y en cualquier `Referer`. En el cuerpo, además, `Masker`
ya lo tapa, porque `token` es una de sus claves.

**COPROP no ve contraseñas, ni para ponerlas.** El realm tiene `registrationAllowed: false`, así que
la persona invitada no puede crearse el usuario; y una pantalla nuestra que recogiera la contraseña
para enviarla a Keycloak nos pondría en el camino de la credencial, que es lo que el #12 y el #13
decidieron evitar. De ahí la forma del puerto `IdentityProvider`: se crea el usuario con
`manage-users` del cliente `coprop-admin` y se le pide a Keycloak que le escriba. El usuario nace con
`emailVerified: false` a propósito —lo verifica la acción que Keycloak envía, y el primer login lo
exige—: darlo por verificado convertiría "nos dijeron ese correo" en "ese correo es suyo".

Dos cosas del adaptador de la Admin API que costaron tiempo:

- **No se inyecta un `RestClient.Builder`.** En Boot 4 ese bean lo trae la autoconfiguración del
  cliente REST, que no está en el classpath —el starter de webmvc trae solo el lado servidor— y el
  contexto no arranca. Se arma a mano, con tiempos de espera explícitos: sin ellos, un Keycloak que
  acepta la conexión y no contesta deja colgada la petición del navegador, un hilo y una transacción.
- No hace **ninguna llamada al arrancar**, que es la lección que el #13 ya pagó con `issuer-uri`.

Lo que lo sostiene: `InvitationsTests` con los cuatro motivos por los que un token no sirve —no
existe, usado, revocado, vencido— y los dos casos de §6.1; `AcceptInvitationTests` con un token
firmado de verdad, para la costura con el #13; y los cinco endpoints nuevos añadidos a
`CrossCondominiumLeakTests` y a `AuthorizationChainTests`. **El test lee el token del correo**, con
un doble del puerto, igual que lo leería la persona invitada: es el único sitio donde existe, y uno
que lo sacara de la base estaría probando otro sistema.

Y una trampa del esquema: **`token_hash` es `VARCHAR(64)` y no `CHAR(64)`** aunque el largo sea fijo.
`CHAR` rellena con espacios a la derecha, así que el valor leído no es igual al escrito; y
`ddl-auto: validate` espera `VARCHAR` para un `String`, de modo que con `CHAR` el arranque falla. Se
descubrió así.

#### La bitácora

Issue #21. `audit_log` guarda lo que §5.2 le pide —actor, acción, entidad, antes, después, fecha e
IP— más el `trace_id` que resolvió §13.2, para poder cruzar una fila con la petición que la originó
en los logs. Retención de un año (§10.1).

**Nadie escribe en la bitácora.** `AuditTrail` no tiene operación de escritura: las filas aparecen
porque un módulo publicó un evento que implementa `AuditableEvent`. Un método para registrar a mano
sería la primera forma de que una acción quedara auditada a medias —o dos veces— según se acordara
quien la escribió.

Hoy la implementan siete eventos, que son los que el issue nombra de lo que existe: conceder una
membresía, conceder el rol de plataforma, las tres de invitaciones, cambiar la configuración y la
activación de una cuenta. Las de dinero —emisión, pagos, devoluciones, credenciales del proveedor—
llegan con M2 y M3, y entrarán implementando la interfaz.

**Un reenvío es una acción distinta de una emisión**, y una revocación por reenvío lo es de una
revocación a mano. No son una bandera dentro del JSON: quien lee la bitácora busca "a quién se
invitó" o "a quién hubo que insistirle", y eso con una bandera no se filtra.

##### Append-only de verdad

El issue lo pide de dos formas —"sin `UPDATE` ni `DELETE` concedidos a la aplicación" y "un intento
de modificar un registro falla a nivel de base de datos"— y **la primera no se puede cumplir hoy**:
la aplicación se conecta como `coprop`, que la imagen de PostgreSQL crea como **superusuario**, y un
superusuario se salta cualquier `REVOKE`. Es exactamente lo que se descubrió evaluando RLS, más
abajo.

Lo que sí muerde a un superusuario es un **disparador**, y es lo que lo hace cumplir: un `UPDATE` o
un `DELETE` sobre `audit_log` revienta, venga de la aplicación, de un `psql` o de una migración
escrita por descuido. El `REVOKE` queda escrito y comentado en la V9 para el día que exista el rol
sin superusuario que RLS también necesita; entonces son dos barreras, y la de privilegios es la que
no se puede desactivar desde una sesión normal.

La purga del año **no es un `DELETE`**, porque el disparador los rechaza todos: es la función
`audit_log_purgar`, el único camino autorizado, que apaga el disparador dentro de su propia
transacción y lo vuelve a dejar puesto. Que lo deje puesto tiene su propio test: si lo dejara
apagado, la bitácora quedaría modificable desde la primera purga y nadie lo notaría.

##### Después del commit, en su propia transacción, y en el mismo hilo

Las tres cosas importan, y cada una descarta una alternativa:

- *Después del commit*, no dentro de la transacción de quien publicó: un `@EventListener` normal
  correría dentro de ella, y eso es una transacción compartida entre dos módulos, que la regla 5
  prohíbe para que un módulo no pueda tumbar la operación de otro.
- *En su propia transacción* (`REQUIRES_NEW`), porque después del commit ya no hay ninguna abierta y
  un `save` sin transacción no escribe nada.
- *En el mismo hilo*, y **no con `@ApplicationModuleListener`**, que es la forma idiomática de Spring
  Modulith y fue la primera que se escribió. Es asíncrona, y con eso **se pierden la IP y la traza**:
  viven en el MDC, que es un `ThreadLocal`. Hacerlas viajar exige un `TaskDecorator` que copie el
  MDC, y **Boot 4 no aplica un bean de `TaskDecorator` al ejecutor que usan los `@Async`** —se
  comprobó con un log dentro del decorador: no se llama nunca, y las dos columnas quedaban nulas—. Lo
  que se ganaba a cambio, que la publicación quede registrada en la tabla de eventos de la V2, no
  reentrega nada por sí solo. Y de paso, síncrono, un test lee la fila justo después de la acción en
  vez de sondear.

Un fallo al escribir la bitácora **no se relanza**: la acción ya se confirmó y no hay nada que
deshacer, así que lo único útil es dejarlo gritado en el log con todo lo que hace falta para
reconstruir la fila a mano.

##### La consulta

`GET /api/v1/condominiums/{id}/audit-log`, con `VIEW_AUDIT_LOG` —los dos administradores del
condominio y el superadministrador, que no opera pero audita (§10.1)—, y los tres filtros del
criterio: actor, entidad y rango de fechas, combinables y todos opcionales. `desde` es inclusivo y
`hasta` exclusivo, para que dos rangos contiguos no devuelvan la misma fila dos veces.

**Siempre acotada a un condominio.** Las acciones de plataforma —conceder el superadministrador, el
primer login de una cuenta— van con `condominium_id` nulo y no salen aquí: sin eso, el administrador
de un edificio vería acciones de la plataforma entera. Tienen su propia consulta desde el **#20**,
que es el que trae la operación de concederlo; está más abajo, en
[Quién reparte los roles](#quién-reparte-los-roles).

Y dos cosas de la consulta que costaron una corrida de tests:

- **Los filtros opcionales de texto van con `coalesce`, no con `:x is null or ...`**, que es lo
  primero que se escribe y no funciona: en `? is null` PostgreSQL no tiene de dónde deducir el tipo
  del parámetro y responde *could not determine data type of parameter*. Dentro de un `coalesce` el
  parámetro está al lado de una columna tipada, así que lo deduce de ella; y comparar una columna
  consigo misma cuando el filtro es nulo es cierto siempre, que es justo "no filtrar".
- Las **fechas no llegan nulas**: el servicio las sustituye por los extremos del tiempo, porque un
  `<` no se puede escribir con ese truco —comparar una columna consigo misma con `<` es falso
  siempre, no cierto—.

Los valores de `antes` y `despues` se guardan como **texto con JSON dentro, no como `jsonb`**. Lo que
se guarda es una foto opaca —valores en cadena, para que la bitácora siga siendo legible dentro de un
año cuando la forma de la entidad ya no sea la de hoy— y nada la consulta por dentro. `jsonb`
prometería una consultabilidad en la que no conviene apoyarse, y además obligaría a que Hibernate
mapeara un `jsonb`: su mapeador de JSON busca el `ObjectMapper` de Jackson 2 y aquí manda Jackson 3,
que vive en otro paquete.

#### Cómo se sale: la baja, la suspensión y la venta

El issue **#19**, que es el otro extremo de la invitación: cómo alguien deja de ver un edificio sin
que su historia desaparezca con él.

**Nada se borra. Todo cierra una vigencia.** La membresía, la titularidad, la ocupación y la
responsabilidad de pago tenían `valid_to` desde el día que nacieron, y aquí se usa por primera vez.
Es lo que hace cierto el segundo criterio del issue —lo histórico se conserva— y de paso lo que
permite volver a entrar más adelante: el índice parcial de la V5 sólo prohíbe **dos filas abiertas**,
no dos filas.

##### Baja y suspensión no son lo mismo, y la diferencia está en la fila

| Operación | Qué toca | Qué significa para quien lee la tabla |
| --- | --- | --- |
| Baja | cierra `valid_to` | ya no es del condominio; se deshace concediendo una membresía **nueva** |
| Suspensión | cambia `status` a `SUSPENDED`, la fila sigue abierta | sigue siendo del condominio y no entra; se deshace reactivando |

De ahí que `MembershipStatus` **no tenga un valor para "terminada"**: eso lo dice la vigencia, que
además guarda cuándo pasó. Y cerrar la fila **no toca el estado**, así que queda diciendo cómo estaba
cuando se la dio de baja.

Rutas: `POST .../memberships/{id}/termination`, `/suspension` y `/reactivation`. Son `POST` a un
sub-recurso y no un `DELETE` sobre la membresía porque **las tres llevan motivo** —el alcance del
issue lo exige— y un `DELETE` con cuerpo es algo que cada intermediario trata como quiere. En cambio
los cierres de relación de la unidad (`DELETE .../occupants/{personId}`,
`DELETE .../billing-responsibles/{personId}`) sí son `DELETE` y responden 204: ahí no hay motivo que
registrar, y que por debajo se cierre una vigencia en vez de borrarse es asunto del dominio.

##### El motivo vive sólo en la bitácora

La fila cerrada dice **cuándo** y la bitácora dice **por qué**. No se guarda además en `membership`
para no tener el mismo dato en dos sitios, que es como acaban discrepando.

**La consecuencia conocida es la retención**: la bitácora se purga al año (§10.1) y la fila cerrada se
queda, así que pasado un año se sabrá que la baja existió y no por qué. Si alguna vez hace falta más,
es una columna en `membership`, no un cambio de diseño.

##### Lo que de verdad corta el acceso es el filtro, no el cierre de sesiones

El primer criterio del issue —*"tras la baja, el usuario autentica pero no ve el condominio"*— lo
cumple `CondominiumFilter`, que relee la membresía **en cada petición**: la baja surte efecto en la
siguiente, sin esperar a que caduque ningún token. Los roles nunca estuvieron en el token (§4.2), y
es aquí donde eso se paga.

El cierre de sesiones es una **segunda puerta** —la cookie del navegador y el refresh token de la app
móvil— y tiene su propia regla, que vive entera en `AccessRevocation`:

> Se cierran las sesiones de una cuenta cuando pierde su última membresía que da acceso y no
> administra la plataforma.

La condición no es un adorno. Una sesión de COPROP **no es de un condominio**: vale para todos los
edificios en los que alguien participe, y cerrarla en cada baja echaría de un edificio a quien sigue
perteneciendo a otro, cuando §6.3 dice de la baja justo lo contrario —"afecta solo a ese
condominio"—. Donde la cuenta ya no tiene ningún sitio al que entrar, cerrarla no le quita nada a
nadie y hace cierto lo que §10.1 pide: que no queden sesiones activas de quien ya no pertenece.
La suspensión usa la misma regla, por lo mismo.

**Son dos cierres y hacen falta los dos.** `Sessions` —puerto de `identity`, implementado en `api`
por `BffSessions`— borra la fila de Spring Session, que es lo que §5.1 llama "se puede revocar";
`IdentityProvider.cerrarSessions` llama a `POST /users/{id}/logout` de Keycloak, que invalida sus
refresh tokens. Cerrar sólo la de Keycloak dejaría entrando a quien ya tiene cookie —su sesión no
vuelve a preguntarle a Keycloak— y cerrar sólo la local dejaría a la app móvil renovando su token.

`Sessions` recibe un **conjunto de nombres** y no un identificador. Una sesión queda indexada por el
nombre de la autenticación que se guardó en ella, y aquí hay dos candidatos: el `sub` de Keycloak,
que es el nombre de la autenticación que escribe el login, y el identificador de la cuenta, que es el
de la identidad con la que el filtro del #13 la sustituye en cada petición. Hoy se guarda la primera
—Spring Security 6 no persiste el contexto que cambia a mitad de petición— pero eso es un detalle de
una librería, y una baja que dependa de él fallaría en silencio el día que cambie. Pasar los dos
cuesta una consulta por índice, y `BffSessionsTests` prueba las dos mitades.

**Un fallo del proveedor NO deshace la baja.** Es la decisión contraria a la del correo de la
invitación (#18), y la diferencia importa: allí el correo *era* la operación —una invitación que no
llega no está emitida— mientras que aquí el acceso ya está cortado por el filtro. Deshacer la baja
porque Keycloak no contesta dejaría a la persona **dentro** del condominio, que es mucho peor que
dejarle una sesión abierta de la que no verá nada. Queda un `log.error` con lo que hace falta para
cerrarla a mano.

##### Dos reglas que evitan un edificio inservible

- **Nadie se da de baja ni se suspende a sí mismo.** No es seguridad —quien puede hacérselo a otro
  podría pedirle a otro administrador que se lo haga— sino recuperación: el único administrador que
  se diera de baja se dejaría fuera de la única pantalla desde la que podría volver.
- **No se cierra ni se suspende la última membresía con `CONDO_ADMIN`.** El edificio quedaría sin
  nadie que pueda invitar, repartir roles ni dar de alta unidades. Es más fácil impedirlo que
  arreglarlo.

Y una de autorización: las tres operaciones piden `@authorization.canChange`, que es `canGrant`
leído al revés, con los roles que la membresía **tiene** en vez de los que se van a conceder: *quien
no puede nombrar a un administrador tampoco puede quitarlo*. Sin eso, el superadministrador —que
según la matriz sólo reparte roles administrativos— podría dar de baja a cualquier propietario de
cualquier edificio, que es justo lo que esa celda acota. La excepción lanzada **dentro** de la
expresión da el 404 de una membresía de otro condominio, igual que en el #15.

##### La venta de una unidad es una sola operación

`POST .../units/{id}/transfer` **cierra las tres relaciones del vendedor y abre la
titularidad del comprador en una transacción**. No es un cierre seguido de un alta por dos motivos:
entre los dos pasos la unidad se quedaría sin dueño —y si el segundo falla, se queda así—, y en la
bitácora dos filas sueltas obligarían a quien la lee a emparejarlas por la fecha y adivinar si fueron
la misma venta.

**Las tres y no sólo la titularidad.** Si el vendedor seguía ocupándola o respondiendo por sus cobros,
esas filas le seguirían dando acceso —las tres cuentan por igual en `isRelatedTo`— y el criterio del
issue no se cumpliría. Si se queda como inquilino, el administrador vuelve a registrar su ocupación,
que es una decisión explícita y no un resto de la venta. El inquilino de otra persona no se toca.

El comprador recibe **el mismo porcentaje** que tenía el vendedor, y no viaja en el cuerpo: una venta
parcial cambia el reparto de la unidad y se registra titularidad por titularidad. Por lo mismo se
rechaza vender a quien ya es copropietario.

Hace falta un **`flush()` entre cerrar y abrir**, que es la misma trampa que enseñó la configuración
con vigencia del #9: Hibernate ordena todos los `INSERT` antes que los `UPDATE` dentro de un flush.

##### Lo histórico, y quién lo ve

| Consulta | Permission | Por qué |
| --- | --- | --- |
| `GET .../memberships` | `INVITE_USERS` | el padrón de hoy |
| `GET .../memberships?cerradas=true` | el mismo | quién estuvo y ya no está, con sus fechas |
| `GET .../units/{id}/owners` | `VIEW_ACCOUNT_STATEMENT` | es la cabecera de su estado de cuenta, y el propietario ve la suya |
| `GET .../units/{id}/owners/history` | `MANAGE_UNITS` | §6.2 deja el comprobante de la época del vendedor **en manos del administrador** |

El histórico va por `MANAGE_UNITS` y no por el permiso del listado a propósito: dejar que un
copropietario de hoy vea la cadena entera de dueños le daría el nombre de gente que ya no tiene nada
que ver con el edificio.

Las vistas de membresía y de titularidad **llevan la vigencia** desde este issue. Sin ella, una lista
de membresías cerradas no podría distinguir una vigente de una terminada —la baja no es un estado,
es una fecha— ni se podría decir quién era el dueño en un mes dado, que es lo que hace falta para dar
ese comprobante.

##### Lo que este issue NO trae, y dónde está

- **La baja automática** que §6.2 describe —"el inquilino deja de ver esa unidad al día siguiente,
  sin intervención manual"— necesita una fecha de fin en la ocupación, que el modelo del análisis no
  tiene. Hoy el cierre lo registra el administrador; el día que esa fecha exista, la tarea llamará a
  `endOccupancy`, que ya está.
- **La desactivación de la cuenta** (§6.3): el estado `DEACTIVATED` existe en `AccountStatus` y nadie
  lo pone. No se hizo aquí a propósito: desactivar la cuenta de quien pierde su única membresía haría
  que recibiera `NO_ACCOUNT` en vez de autenticar, y el primer criterio del issue pide justo lo
  contrario —que autentique y no vea el condominio—. Es otro ticket.
- **La revocación de sesiones al retirar un rol administrativo**, que §8 nombra junto a la
  desactivación: la trajo el **#20**, en `AccessRevocation`.
- **Listar las sesiones activas** de alguien, también de §8: es del **#22**.

##### Los tres eventos nuevos de `condominium` se auditan, y los tres viejos también

`MEMBERSHIP_ENDED`, `MEMBERSHIP_SUSPENDED`, `MEMBERSHIP_REACTIVATED`,
`OWNERSHIP_TRANSFERRED`, `OCCUPANCY_ENDED` y `BILLING_RESPONSIBILITY_ENDED` implementan
`AuditableEvent` —§8 pide los cambios de rol en la bitácora, y una baja es el mayor de todos—. Y de
paso lo hacen `OWNERSHIP_GRANTED`, `OCCUPANCY_REGISTERED` y
`BILLING_RESPONSIBILITY_ASSIGNED`, que el #21 había dejado fuera: **una bitácora que registra el fin
de una relación y no su principio es peor que una que no registre ninguno**, porque quien la lea
creerá que la relación nació sola.

Reactivar es una acción distinta de suspender y no la misma con una bandera, por lo mismo que un
reenvío de invitación es distinto de una emisión: quien lee la bitácora busca "a quién se suspendió"
o "a quién se le devolvió el acceso", y una bandera dentro del JSON no se filtra.

En todos ellos la entidad auditada es **la unidad o la membresía**, no la fila de la relación: las
relaciones llevan vigencia, así que su identificador cambia en cada cierre y no serviría para seguir
la historia de un recurso.

#### Quién reparte los roles

Issue **#20**. El administrador del condominio asigna y quita `CONDO_ADMIN` y `FINANCE_ADMIN` dentro
de **su** condominio, sin pasar por el superadministrador (§10.1), y el rol de plataforma se concede
y se revoca por su propia puerta. Son dos operaciones y no una, porque los dos roles no viven en la
misma tabla ni tienen el mismo alcance.

##### El cambio edita la fila; no la cierra y abre otra

Era la decisión abierta del issue, y se resolvió por lo que significa la vigencia de `membership`:
**el tiempo que esa cuenta perteneció al condominio**. De eso vive el padrón del #19 —quién estuvo y
ya no está, con sus fechas—, así que un cambio de rol que cerrara la fila dejaría ahí **una baja que
nadie dio**, y encima sin `MembershipRevoked` que la explicara. La alternativa prometía un historial
de roles consultable en la tabla; lo que da en realidad es un segundo historial, parcial, que puede
discrepar del primero.

**La historia de los roles es la bitácora**, que para esto es mejor sitio: es append-only de verdad
(V9), y cada fila trae actor, motivo, fecha, IP y las dos mitades del cambio. `MembershipRolesChanged`
lleva los roles de antes y los de después dentro, porque lo que se pregunta después no es qué roles
tiene —eso lo dice la fila— sino **cuál le quitaron y quién**. El precio conocido es la retención: la
bitácora se purga al año (§10.1), así que pasado un año la tabla dirá qué roles hay y no cómo se
llegó a ellos. Es el mismo precio ya aceptado para el motivo de una baja.

Es un `PUT` del **conjunto completo** y no un `PATCH` que añada o quite: los roles de una membresía
son un solo valor. Así la petición es idempotente —y repetir los roles que ya hay no publica evento,
porque la bitácora registra cambios y no peticiones— y el cliente no tiene que adivinar el estado
anterior para describir el que quiere.

##### La autorización pide las dos mitades, y en ese orden

```java
@PreAuthorize("@authorization.canChange(authentication, #condominiumId, #membershipId)"
        + " and @authorization.canGrant(authentication, #nuevos.roles())"
        + " and @reauthentication.recent(authentication)")
```

La tercera cláusula la añadió el #22 y está explicada en
[Tiempos de sesión y reautenticación](#tiempos-de-sesión-y-reautenticación). `canChange` por los
roles que se van, `canGrant` por los que llegan. Con una sola mitad se
escapa justo lo que la celda "Solo administradores" acota: con sólo `canGrant`, el
superadministrador convertiría a un propietario en `CONDO_ADMIN`; con sólo `canChange`, podría
quitarle a un administrador sus roles y dejarle `OWNER`, que es repartir un rol que no le toca. Las
dos negativas se prueban en `AdminRolesTests`, una por mitad.

**El orden no es estético.** `canChange` *lanza* cuando la membresía es de otro condominio, y SpEL
corta en el primer `and` que falla: puesto primero, el segundo criterio del issue —"un `CONDO_ADMIN`
no puede tocar roles de otro condominio"— responde **404** y no un 403 que confirmaría que esa
membresía existe. Al revés, un rol sin permiso vería el 403 antes de que nadie comprobara de qué
condominio es la membresía.

El primer criterio —"un `CONDO_ADMIN` no puede asignar `SUPER_ADMIN`"— son **dos puertas, y responden
distinto a propósito**:

| Por dónde | Respuesta | Por qué |
|---|---|---|
| `POST /api/v1/platform-admins` | **403** `NO_PERMISSION` | La ruta es global, así que el filtro del #17 no corre y no lleva las autoridades de su membresía. Le falta el permiso. |
| `PUT .../memberships/{id}/roles` con `PLATFORM_ADMIN` | **422** `BUSINESS_RULE` | Aquí no le falta permiso —un `CONDO_ADMIN` concede cualquier rol de su condominio— sino que ese rol **no cabe en una membresía**, y no cabe para nadie: ni el superadministrador puede colarlo por ahí. |

La segunda fila es la misma regla que el alta ya tenía desde el #16, y las dos la comparten en
`exigirRolesDeMembresia` para que no puedan separarse: un alta que rechaza `PLATFORM_ADMIN` y un
cambio que lo admitiera dejarían entrar por la segunda puerta lo que la primera prohíbe.

##### Las dos reglas del #19 se heredan tal cual, con su precio

- **Nadie se cambia los roles a sí mismo.** Misma razón de recuperación: quien se quitara
  `CONDO_ADMIN` se dejaría fuera de la única pantalla desde la que podría devolvérselo. El precio
  conocido es que un administrador que quiera **quitarse** `FINANCE_ADMIN` —para que haya separación
  de funciones— tiene que pedírselo a otro. La alternativa, permitir el cambio propio salvo que se
  quite `CONDO_ADMIN`, es una regla más y no cierra nada que esta no cierre.
- **No se le quita `CONDO_ADMIN` al último administrador**, por lo mismo que no se le da de baja:
  el edificio quedaría sin nadie que pueda invitar ni repartir roles. Es el mismo
  `exigirQueNoSeaElUltimoAdministrador`, llamado sólo cuando el conjunto que llega no lo trae.

##### Retirar un rol administrativo cierra las sesiones, aunque le queden condominios

§8 pide revocar las sesiones "al retirar un rol administrativo", y esa es la **segunda entrada** de
`AccessRevocation`. Su regla es distinta de la del #19 y conviene verlas juntas:

| Entrada | Cuándo cierra |
|---|---|
| `siSeQuedaSinCondominios` (#19) | Sólo si a la cuenta no le queda ninguna membresía que dé acceso y no administra la plataforma |
| `siPierdeAlgunRolAdministrativo` (#20) | **Aunque le queden**: basta con que los roles retirados incluyan uno administrativo |
| `deTodasFormas` (#20) | Sin condición: la revocación del rol de plataforma |

La segunda **no hace falta para cortar lo que ese rol permitía** —los roles se releen en cada
petición, así que la siguiente ya va con los nuevos— y por eso podría parecer de adorno. No lo es: lo
que caducó no es el acceso sino el **nivel de confianza** con el que se abrió esa sesión, y la
política que el **#22** le pondrá encima —una hora de inactividad para los roles administrativos y
veinticuatro para el resto (§5.3)— se decide por petición justamente porque la sesión no sabe con qué
roles nació. Mientras eso no exista, cerrarla es la única forma de que un rol retirado no deje detrás
una sesión que se comporta como la de un administrador.

Sólo cuando se **quita**: añadir `FINANCE_ADMIN` a quien ya administra no cierra nada —echarle de la
pantalla en la que está trabajando sin que haya perdido nada— y cambiar entre roles que no son
administrativos tampoco.

##### El aviso de concentración es la bitácora, no la respuesta

El alcance del issue pide "aviso cuando una misma persona concentra `CONDO_ADMIN` y `FINANCE_ADMIN`".
**Avisa, no impide**: §10.1 permite la concentración como decisión explícita del condominio —en
edificios pequeños una sola persona administra y maneja el dinero— a condición de que quede
registrada. De ahí las dos salidas: un `WARN` en el log, para quien opera, y `concentraFunciones` en
la fila de la bitácora, que es donde el análisis lo exige.

**No va en la respuesta de la API**, y es deliberado: quien hace el cambio ya sabe qué roles mandó. El
aviso existe para quien lea después y se pregunte si en ese edificio hay separación de funciones.

##### El rol de plataforma: se concede, y al revocarlo se borra la fila

Hasta este issue el rol sólo lo abría el arranque, y sólo cuando no había ninguno: la plataforma
podía empezar pero no crecer, y el segundo superadministrador salía de un `INSERT` a mano.
`Identities.grantPlatformAdmin` y `revokePlatformAdmin` son esa operación, con actor, motivo y
bitácora.

Las tres rutas son **globales** —`/api/v1/platform-admins`— y eso es lo que decide quién pasa:
sin condominio en la ruta, el filtro del #17 no corre y las únicas autoridades de la petición son las
que puso el de identidad. Un `CONDO_ADMIN` llega sin ninguna y recibe 403 **por la forma de la ruta**,
sin una regla escrita aparte. El permiso es `INVITE_ADMINS_ONLY`, que es la celda de
`PLATFORM_ADMIN` en la fila "Invitar usuarios y asignar roles": conceder el rol de plataforma es
asignar un rol administrativo, el mayor de ellos, así que la celda ya lo cubre y **no hace falta
añadir una fila a la matriz**, que sería cambiar el análisis.

**Revocar borra la fila**, al contrario que la baja de una membresía, que la cierra. Las dos
decisiones son coherentes con lo que cada tabla significa: `membership` describe un periodo y
`platform_admin` describe el presente —o administras la plataforma o no, sin grados—. Y hay un motivo
práctico que pesa igual: `existsById` sobre esa tabla es lo que pregunta el filtro de identidad en
cada petición, y lo que mira el arranque para decidir si abre el primero. Una fila cerrada habría que
filtrarla en los dos sitios, y eso es exactamente lo que se olvida.

De ahí que `PlatformAdminRevoked` sea más importante que la mayoría de los eventos: sin esa fila,
haber tenido el rol sería indistinguible de no haberlo tenido nunca.

**Nadie se lo concede ni se lo revoca a sí mismo.** Lo primero lo impide además un `CHECK` de la V7;
se comprueba antes para poder explicarlo, porque un fallo de integridad saldría como un 500 sin decir
nada útil. Lo segundo es la regla de recuperación de siempre. Y revocárselo a una cuenta que no lo
tiene responde **404 y no 422**: quién administra la plataforma no es dato público.

##### La bitácora de plataforma: dos consultas y no una

`GET /api/v1/audit-log` devuelve las filas con `condominium_id` nulo —conceder y revocar el rol de
superadministrador, y el primer acceso de una cuenta—. Llega con este issue porque es el que trae la
operación de concederlo: antes, la única fila de plataforma la escribía el arranque y no había a quién
enseñársela.

**Son dos consultas y no una con el condominio opcional.** Los dos públicos no son el mismo —el
administrador de un edificio no tiene por qué ver acciones de la plataforma entera— y una sola donde
olvidarse de pasar el filtro lo muestra todo es la clase de endpoint que acaba filtrando lo que no
debía. Además no podría ser una ni queriendo: en `a.condominiumId = :condominio` un parámetro nulo no
empareja con las filas nulas, porque en SQL `null = null` no es cierto.

Las dos piden `VIEW_AUDIT_LOG` y **el alcance de la ruta hace el resto**, igual que con el rol de
plataforma. Por eso viven en el mismo controlador aunque sus rutas no se parezcan: lo que hay que
poder leer junto es en qué se diferencian.

#### Row-Level Security: evaluado, y por qué todavía no está

§7.2 lo pide como segunda barrera, para que un filtro olvidado en una consulta no cruce
condominios. **Funciona y conviene**, pero cuesta más que una migración, y eso se comprobó contra
el PostgreSQL de desarrollo antes de decidir:

| Cómo se conecta la aplicación | Qué hace RLS |
|---|---|
| Como `coprop`, que es **superusuario** (el usuario que crea la imagen de Postgres) | **Nada.** Ni con `FORCE ROW LEVEL SECURITY`: un superusuario salta toda política, en silencio |
| Como un rol sin superusuario | Sin contexto devuelve **0 filas**; con contexto, solo las de ese condominio; y un `INSERT` con otro `condominium_id` lo **rechaza la base** |

Es decir: RLS da exactamente el criterio "ninguna consulta de negocio puede ejecutarse sin
condominio en contexto", pero **solo si la aplicación deja de conectarse como superusuario**. Eso es
un cambio de despliegue —rol propio, permisos, el Compose y las variables de producción—, más un
enganche que ejecute `SET COMMERCIAL` al abrir cada transacción, más resolver el alta de un condominio,
que ocurre cuando todavía no hay condominio en contexto. Mezclarlo con el filtro habría hecho un
cambio de seguridad y uno de infraestructura en el mismo PR.

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

**`audit_log` es la excepción declarada, y en dos sitios.** Su `condominium_id` no lleva clave
foránea y su `actor` es texto en vez de apuntar a `user_account`: la bitácora tiene que sobrevivir a
la baja de lo que audita —su retención es de un año— y una FK la volvería imborrable o dejaría la
fila huérfana. Además hay actores que no son cuentas, como el arranque de la plataforma. Es una
tabla de registro, no de negocio: nadie navega desde ella, y lo que necesita es no poder perderse.

## Esquema de base de datos

Todo cambio de esquema entra por una migración de Flyway en
`src/main/resources/db/migration`. No se usa generación automática de DDL: `ddl-auto` está en
`validate`, así que el arranque falla si las entidades no coinciden con el esquema migrado.

Convención de nombres: `V<n>__<descripcion_en_snake_case>.sql`, numeración correlativa sin
huecos.

## Referencias al análisis

El documento de análisis es la fuente de las decisiones; este archivo solo describe cómo se
materializan en el código. Cada issue del repositorio cita la sección que lo origina.
