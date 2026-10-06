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

## Cómo se verifica

```
./gradlew test --tests "bo.coprop.ModularityTests"
```

El test también genera los diagramas de módulos en `build/spring-modulith-docs/`.

Para cambiar un límite: se edita el `allowedDependencies` del `package-info.java` del módulo y se
anota aquí por qué. Un límite que se relaja sin explicación es el primer paso para perder la
opción de extraer `payments`.

## Esquema de base de datos

Todo cambio de esquema entra por una migración de Flyway en
`src/main/resources/db/migration`. No se usa generación automática de DDL: `ddl-auto` está en
`validate`, así que el arranque falla si las entidades no coinciden con el esquema migrado.

Convención de nombres: `V<n>__<descripcion_en_snake_case>.sql`, numeración correlativa sin
huecos.

## Referencias al análisis

El documento de análisis es la fuente de las decisiones; este archivo solo describe cómo se
materializan en el código. Cada issue del repositorio cita la sección que lo origina.
