# coprop-backend

[![CI del backend](https://github.com/COPROP/coprop-backend/actions/workflows/backend-ci.yml/badge.svg)](https://github.com/COPROP/coprop-backend/actions/workflows/backend-ci.yml)

API y reglas de negocio de COPROP: gestión de cuentas de condominios y comunidades residenciales.

Java 21 · Spring Boot 4.1 · Spring Modulith · PostgreSQL 16 · Keycloak

La arquitectura y las reglas de dependencia entre módulos están en
[ARCHITECTURE.md](ARCHITECTURE.md).

## Requisitos

| Herramienta | Versión | Nota |
|---|---|---|
| JDK | 21 | El wrapper de Gradle se descarga solo |
| Docker | con Compose v2 | Para las dependencias locales y para los tests con Testcontainers |

## Arranque local

Levantar las dependencias:

```bash
docker compose up -d
```

Deja cuatro servicios en pie:

| Servicio | Puerto | Para qué |
|---|---|---|
| PostgreSQL | 15432 | base `coprop`, usuario `coprop`. No es el 5432: ese puerto lo suele tener tomado un PostgreSQL instalado en la máquina |
| Keycloak | 8081 | realm `coprop` importado al arrancar desde [`docker/keycloak/coprop-realm.json`](docker/keycloak/coprop-realm.json); consola en http://localhost:8081 |
| MinIO | 9000 (API) | bucket `coprop-local`, creado al arrancar. Sin consola web: se inspecciona con `docker exec coprop-minio mc ls local/coprop-local` |
| Mailpit | 1025 (SMTP), 8025 (web) | captura todo el correo que envía la aplicación |

Arrancar la aplicación:

```bash
./gradlew bootRun
```

El perfil `local` es el de defecto y apunta a esos servicios, así que no hace falta configurar
nada. Comprobar que está arriba:

```bash
curl -i http://localhost:8080/actuator/health
```

Responde **200** con `{"status":"UP"}`: el health y sus sondas (`/actuator/health/liveness` y
`/readiness`) son públicos desde el issue #6. Si no hay respuesta, la aplicación no levantó: mirar
la salida de `bootRun`. El resto del actuator no se sirve por HTTP a nadie.

Todo lo demás de la API exige identidad. **La aplicación arranca aunque Keycloak no esté**, y eso
está buscado: un reinicio durante una caída del proveedor no debe dejar también sin servicio a la
app móvil. Ver [ARCHITECTURE.md](ARCHITECTURE.md#el-registro-del-cliente-se-arma-a-mano).

Para revisar los endpoints:

| Dónde | Qué es |
|---|---|
| http://localhost:8080/swagger-ui.html | La consola navegable. **Solo en desarrollo**: el webjar no entra al artefacto de producción |
| http://localhost:8080/api/openapi.json | La especificación, que sí se sirve en producción |

### Entrar

```
http://localhost:8080/bff/login
```

Lleva a Keycloak, y al volver el backend deja una cookie de sesión. El navegador **nunca recibe un
token**: los tres quedan en la sesión del servidor, que vive en la base
(Seguridad §5.1, [ARCHITECTURE.md](ARCHITECTURE.md#seguridad)). Para cerrar sesión, `POST /logout`,
que también la cierra en Keycloak.

Entrar no da acceso a nada por sí solo. Hace falta además una **cuenta de COPROP con ese correo**
—si no la hay, la API responde 403 con `SIN_CUENTA`— y una **membresía activa** en el condominio que
se pida —si no la hay, 404—.

### El primer superadministrador

**La aplicación lo abre sola al arrancar.** Si no hay ninguno y `coprop.superadministrador-inicial`
trae un correo —en el perfil `local`, `dev@coprop.local`—, crea la persona, la cuenta y el registro.
Es el mismo arranque que hace Keycloak con `KC_BOOTSTRAP_ADMIN`.

No es una puerta trasera: **solo actúa si la plataforma está vacía de superadministradores**, así que
dejar la propiedad puesta no abre nada más, y quitarla no cierra lo ya abierto. La cuenta nace sin
vincular: la propiedad dice a quién se le abre la puerta, no entra por él.

Con eso, una base limpia arranca sin sembrar nada:

```
POST /api/v1/condominios                      → 201   crea el condominio
POST /api/v1/condominios/{id}/membresias      → 201   se pone de primer administrador
POST /api/v1/condominios/{id}/unidades        → 201   y ya administra
```

Entre medias verás algún **403 `SIN_PERMISO`, y es lo correcto**: el superadministrador no opera
condominios (Seguridad §10.1). Antes de darse la membresía no puede crear unidades ni personas, y
después sigue sin poder tocar lo que se cobra —eso es del `FINANCE_ADMIN`—. La matriz está en
[ARCHITECTURE.md](ARCHITECTURE.md#quién-puede-qué).

### Invitar a alguien, y ver el correo

**El acceso a un condominio nace siempre de una invitación**: no hay registro abierto (Seguridad §6).
El orden es el de §6.1 —primero la persona y su relación con la unidad, después la invitación—:

```
POST /api/v1/personas                                 → 201   la persona
POST /api/v1/personas/{id}/cuenta                     → 201   su cuenta, en estado INVITADO
POST /api/v1/condominios/{id}/invitaciones            → 201   {"accountId": "...", "roles": ["OWNER"]}
```

El correo sale al Mailpit del Compose. **Se lee en http://localhost:8025**, y de ahí se copia el
token del enlace: la respuesta del alta **no lo trae**, y en la base solo hay su SHA-256. Es
deliberado —quien pudiera leerlo entraría como el invitado— así que no hay forma de recuperarlo: si
se pierde, se reenvía.

Con ese token, las dos llamadas que hace la persona invitada:

```
POST /api/v1/invitaciones/apertura    {"token": "..."}   sin autenticar; devuelve credencialEnviada
POST /api/v1/invitaciones/aceptacion  {"token": "..."}   ya con sesión; concede la membresía
```

La apertura crea el usuario en Keycloak y le pide a Keycloak que le mande el correo para poner su
contraseña —ese segundo correo también llega a Mailpit—, porque el realm tiene el registro cerrado y
COPROP no toca contraseñas. Si la cuenta ya había entrado antes, `credencialEnviada` viene en falso y
solo le toca iniciar sesión.

El enlace del correo apunta a `http://localhost:4200/invitaciones/{token}`, que es una página del
frontend y **todavía no existe**: hasta que exista, el token se copia a mano del correo.

> El token vive **siete días** y sirve **una vez**. Un token usado, revocado o vencido responde `404
> NO_ENCONTRADO`, igual que uno inventado: los cuatro casos tienen que ser indistinguibles.

### La semilla de desarrollo

Ya **no hace falta para empezar**: solo ahorra pasos y fija los identificadores con los que viene la
colección de Postman.

```bash
docker exec -i coprop-postgres psql -U coprop -d coprop < docker/postgres/semilla-de-desarrollo.sql
```

No es una migración y Flyway no la ve, así que no puede acabar en producción. Se puede repetir
cuantas veces haga falta. Deja:

| Qué | Identificador | Para qué |
|---|---|---|
| Condominio *Torre Sucre (desarrollo)* | `aaaa…0001` | Con `dev@coprop.local` como **CONDO_ADMIN y FINANCE_ADMIN** |
| Condominio *del vecino* | `bbbb…0002` | **Sin membresía**: todo lo suyo tiene que responder 404 |

La cuenta ya no la crea la semilla —la abre el arranque—, así que la membresía se concede a quien
tenga ese correo. Si la base está limpia y la aplicación no arrancó todavía, esa parte no hace nada:
se corre la semilla otra vez después de levantarla.

Los dos roles en la misma persona están permitidos y es una decisión explícita del análisis (§10.1):
en condominios pequeños, una sola persona administra y maneja el dinero. La concentración queda en
auditoría.

El segundo condominio no es relleno: es el escenario real de fuga. Un condominio que **existe** y no
es tuyo tiene que responder exactamente igual que uno inventado.

> **Vuelve a correr la semilla cada vez que recrees el contenedor de Keycloak.** En `start-dev` no
> persiste su base, así que `dev@coprop.local` nace con un `sub` nuevo mientras la cuenta de COPROP
> sigue apuntando al viejo; como una cuenta ya vinculada no se revincula a otro sujeto, el login
> responde `SIN_CUENTA` y parece que la cuenta está rota. La semilla suelta ese vínculo.

### Probar la API desde Postman

Hay una colección lista para importar en
[`docs/postman/COPROP.postman_collection.json`](docs/postman/COPROP.postman_collection.json): trae
las 31 peticiones con cuerpos de ejemplo, la autenticación ya configurada y dos que **tienen que
fallar**, para ver el aislamiento funcionando. Las altas guardan solas los identificadores que
devuelven, así que se pueden encadenar de arriba abajo sin copiar nada.

La única que hay que copiar a mano es el **token de una invitación**: no sale en ninguna respuesta
—en la base solo hay su hash— y se saca del correo, en Mailpit, a la variable `invitationToken`.

Empieza por **`GET /api/v1/me`**, que es por donde empieza también un cliente de verdad: dice quién
eres, a qué condominios entras y con qué rol en cada uno. Con la semilla corrida sale un condominio;
el del vecino no sale, y eso es lo que conviene mirar.

La web entra por el BFF con cookie, que Postman no puede recorrer —hay un formulario de login de por
medio—. El camino para una herramienta es el del cliente móvil: **Authorization Code con PKCE**
contra `coprop-movil`, que devuelve un `Bearer` y no depende de ninguna sesión.

Lo único que hace falta es que Keycloak acepte la vuelta de Postman. No se toca el realm: se
sustituye por variable de entorno, que es para lo que están los marcadores.

```bash
export COPROP_MOVIL_REDIRECT_URI=https://oauth.pstmn.io/v1/callback
docker compose up -d --force-recreate keycloak
```

En Postman, pestaña **Authorization** → tipo **OAuth 2.0**, con *Authorize using browser* desmarcado:

| Campo | Valor |
|---|---|
| Grant type | Authorization Code (With PKCE) |
| Callback URL | `https://oauth.pstmn.io/v1/callback` |
| Auth URL | `http://localhost:8081/realms/coprop/protocol/openid-connect/auth` |
| Access Token URL | `http://localhost:8081/realms/coprop/protocol/openid-connect/token` |
| Client ID | `coprop-movil` |
| Client Secret | *(vacío: es un cliente público)* |
| Code Challenge Method | SHA-256 |
| Scope | `openid email` |
| Client Authentication | Send client credentials in body |

*Get New Access Token*, se entra con `dev@coprop.local`, y el token sale con **5 minutos** de vida
(Seguridad §5.3): cuando empiece a responder 401, se pide otro.

Las variables de la colección ya apuntan a los identificadores de
[la semilla](#la-semilla-de-desarrollo), así que con la semilla corrida se puede usar de entrada.

**Si creas un condominio desde Postman, no vas a poder verlo.** El alta no cuelga de ningún
condominio, pero consultarlo exige membresía activa y responde 404 sin ella. No es un error del
montaje: es el aislamiento haciendo su trabajo, y por eso esa petición de la colección **no pisa** la
variable `condominiumId`. Para trabajar con uno propio, el de la semilla ya viene con los roles
puestos.

Para resetear el entorno por completo:

```bash
docker compose down -v && docker compose up -d
```

### Credenciales locales

Son literales en el repositorio **a propósito**, para que `docker compose up` funcione sin
configuración previa. No existen fuera de tu máquina y no se parecen a las de ningún otro
entorno.

| Dónde | Usuario | Contraseña |
|---|---|---|
| PostgreSQL | `coprop` | `coprop` |
| Consola de Keycloak | `admin` | `admin` |
| Usuario de prueba del realm | `dev@coprop.local` | `dev-local-coprop` |
| MinIO | `coprop` | `coprop-local-secret` |

La contraseña del usuario de prueba tiene 16 caracteres porque el realm exige 10: una de tres
letras ya no pasa la política.

En producción todo llega por variable de entorno (`application-prod.yml`) y los secretos viven en
KMS o Vault. Nada de eso entra al repositorio.

### El realm

Un solo realm, `coprop`, versionado en
[`docker/keycloak/coprop-realm.json`](docker/keycloak/coprop-realm.json) y reconstruible desde cero
con `docker compose down -v && docker compose up -d`. Lo que trae y por qué está en
[ARCHITECTURE.md](ARCHITECTURE.md#el-realm-de-keycloak).

**No lleva ningún secreto literal.** El import de Keycloak sustituye `${VARIABLE}` y
`${VARIABLE:defecto}` con el entorno del contenedor, así que cada secreto es un marcador con un
valor de desarrollo por defecto. Para pisarlo basta exportar la variable antes de levantar el
Compose: `docker-compose.yml` la pasa al contenedor solo si está puesta.

**Login con Google.** Viene declarado pero **apagado**, porque necesita credenciales de un proyecto
de Google que no pueden vivir en un repositorio público. Para encenderlo:

1. En Google Cloud, crear credenciales OAuth de tipo *aplicación web* con el URI de redirección
   `http://localhost:8081/realms/coprop/broker/google/endpoint`.
2. Exportar las tres variables y rehacer el contenedor:

```bash
export GOOGLE_HABILITADO=true
export GOOGLE_CLIENT_ID=...apps.googleusercontent.com
export GOOGLE_CLIENT_SECRET=...
docker compose up -d --force-recreate keycloak
```

Con eso aparece el botón de Google en el login. Quien entre con un correo que ya tiene cuenta en el
realm **cae en esa misma cuenta**, no en una segunda: lo hace el flujo `vincular por correo
verificado`, que vale porque Google verifica el correo y en COPROP nadie se registra solo.

## Comandos

| Comando | Qué hace |
|---|---|
| `./gradlew build` | Compila, verifica formato y corre los tests |
| `./gradlew test` | Solo los tests (los que usan Testcontainers necesitan Docker) |
| `./gradlew test --tests "bo.coprop.ModularityTests"` | Verifica los límites entre módulos. No necesita Docker |
| `./gradlew spotlessApply` | Aplica el formato. El build falla si no está aplicado |
| `./gradlew bootRun` | Arranca con el perfil `local` |

La cobertura la mide JaCoCo y se genera sola tras los tests, sin invocar nada aparte. El informe
legible queda en `build/reports/jacoco/test/html/index.html`.

## Integración continua

El workflow [`backend-ci.yml`](.github/workflows/backend-ci.yml) corre en cada push a `main` y en
cada pull request: `./gradlew build`, que compila, verifica el formato con Spotless y corre los
tests. Testcontainers funciona de serie porque el runner de GitHub trae Docker.

Publica la cobertura en el resumen de la corrida y, en los pull requests, como un comentario que
se reescribe en cada push en lugar de acumular uno por intento. Los informes de test y el HTML de
cobertura quedan como artefacto durante 14 días, que es lo que sirve cuando algo falla y no se
reproduce en local.

El límite de la corrida son 10 minutos, el criterio del issue #7. Está puesto como
`timeout-minutes` a propósito: si alguna vez se pasa, la corrida falla en vez de degradarse en
silencio.

## Estructura

```
src/main/java/bo/coprop/
├── shared/          tipos comunes (módulo abierto)
├── identity/        personas, membresías, roles, invitaciones
├── condominium/     condominios, configuración, unidades
├── billing/         obligaciones de pago, expensas, agua, mora
├── payments/        intents, QR, inbox, conciliación
│   └── providers/   adaptadores por proveedor (interno al módulo)
├── notifications/   correo
└── audit/           bitácora inmutable
```

Cada módulo declara en su `package-info.java` de qué otros módulos puede depender. El build falla
si alguien cruza un límite no declarado.

## Esquema de base de datos

Flyway, en `src/main/resources/db/migration`. `ddl-auto` está en `validate`: el arranque falla si
las entidades no coinciden con el esquema migrado. No se usa generación automática de DDL.
