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
| `correlationId` | Une el error que reporta un usuario con su traza en los logs. |
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
o fragmentos de consulta. Al log sí va entero, con su `correlationId`.

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

## Esquema de base de datos

Todo cambio de esquema entra por una migración de Flyway en
`src/main/resources/db/migration`. No se usa generación automática de DDL: `ddl-auto` está en
`validate`, así que el arranque falla si las entidades no coinciden con el esquema migrado.

Convención de nombres: `V<n>__<descripcion_en_snake_case>.sql`, numeración correlativa sin
huecos.

## Referencias al análisis

El documento de análisis es la fuente de las decisiones; este archivo solo describe cómo se
materializan en el código. Cada issue del repositorio cita la sección que lo origina.
