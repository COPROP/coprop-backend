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
la salida de `bootRun`.

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
