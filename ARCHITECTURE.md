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
| `identity` | Personas, membresías por condominio, roles, invitaciones. | `shared` |
| `condominium` | Condominios, su configuración y sus unidades. | `shared` |
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
| Sin token o token inválido | 401 | `NO_AUTENTICADO` |
| Es miembro, pero el rol no alcanza | 403 | `SIN_PERMISO` |
| El recurso no existe, o no hay membresía activa en ese condominio | 404 | `NO_ENCONTRADO` |
| Transición inválida o choque de concurrencia | 409 | `CONFLICTO` |
| Petición bien formada que una regla rechaza | 422 | `REGLA_DE_NEGOCIO` |
| Fallo no previsto | 500 | `INTERNO` |

Dos filas merecen explicación:

- **422 en validación, no el 400 que Spring devuelve por defecto.** El 400 es para una petición
  que el servidor no puede ni parsear; una entidad bien formada que no pasa las reglas es 422.
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

`ConfiguracionDeSeguridad` es una cadena mínima y provisional: existe para que el 401 y el 403
salgan en problem+json como el resto, y para que `/actuator/health` deje de responder 401. Las
cuatro cadenas reales de Seguridad §7.1 —BFF web con cookie, API móvil con Bearer, webhooks por
firma, actuator en red interna— llegan con el issue #13 en M1.

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

`Enmascarador` tapa credenciales, tokens, cuentas y datos de QR. Las reglas son **por nombre de
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

## Esquema de base de datos

Todo cambio de esquema entra por una migración de Flyway en
`src/main/resources/db/migration`. No se usa generación automática de DDL: `ddl-auto` está en
`validate`, así que el arranque falla si las entidades no coinciden con el esquema migrado.

Convención de nombres: `V<n>__<descripcion_en_snake_case>.sql`, numeración correlativa sin
huecos.

## Referencias al análisis

El documento de análisis es la fuente de las decisiones; este archivo solo describe cómo se
materializan en el código. Cada issue del repositorio cita la sección que lo origina.
