package bo.coprop.audit.internal;

import bo.coprop.audit.AuditEntryView;
import bo.coprop.audit.AuditTrail;
import bo.coprop.shared.AuditableEvent;
import bo.coprop.shared.LogKeys;
import bo.coprop.shared.PagedResponse;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
class AuditTrailService implements AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(AuditTrailService.class);

    private static final TypeReference<Map<String, String>> MAPA_DE_TEXTO = new TypeReference<>() {};

    /** Lo que cabe en las columnas de antes y despues. Mas que suficiente para una foto. */
    private static final int LARGO_MAXIMO = 4000;

    /**
     * Los extremos con los que se sustituye un rango de fechas sin acotar.
     *
     * <p>No llegan nulos a la consulta a proposito: un {@code <} no se puede escribir con el truco
     * del {@code coalesce} que si vale para los filtros de texto --comparar una columna consigo
     * misma con {@code <} es falso siempre, no cierto-- y un parametro nulo suelto deja a PostgreSQL
     * sin saber de que tipo es.
     *
     * <p>El año 9999 cabe de sobra en un {@code timestamptz}, que llega hasta el 294276.
     */
    private static final Instant PRINCIPIO_DE_LOS_TIEMPOS = Instant.EPOCH;

    private static final Instant FIN_DE_LOS_TIEMPOS = Instant.parse("9999-12-31T23:59:59Z");

    private final AuditLogRepository auditTrail;
    private final ObjectMapper json;

    /**
     * El {@link EntityManager} se inyecta con {@code @Autowired} y no con
     * {@code @PersistenceContext}: NullAway no reconoce la segunda como inicializadora del campo y
     * lo da por nulo. Es la misma razon que en el issue #13.
     */
    @Autowired
    private EntityManager entidades;

    AuditTrailService(AuditLogRepository auditTrail, ObjectMapper json) {
        this.auditTrail = auditTrail;
        this.json = json;
    }

    /**
     * Escucha <strong>la interfaz</strong>, no los tipos concretos, y eso es lo que mantiene a este
     * modulo sin depender de ninguno.
     *
     * <p>Spring resuelve los oyentes por el tipo del evento y acepta una interfaz, asi que todo
     * evento que implemente {@link AuditableEvent} cae aqui sin que nadie lo registre. Sumar una
     * accion a la bitacora es implementar la interfaz en su evento; quitarla, dejar de implementarla.
     *
     * <p><strong>Despues del commit y en su propia transaccion, pero en el MISMO HILO.</strong> Las
     * tres cosas importan y cada una descarta una alternativa:
     *
     * <ul>
     *   <li><em>Despues del commit</em>, no dentro de la transaccion de quien publico: un
     *       {@code @EventListener} normal correria dentro de ella, y eso es una transaccion
     *       compartida entre dos modulos, que la regla 5 de ARCHITECTURE.md prohibe para que un
     *       modulo no pueda tumbar la operacion de otro.
     *   <li><em>En su propia transaccion</em> ({@code REQUIRES_NEW}), porque despues del commit ya
     *       no hay ninguna abierta y un {@code save} sin transaccion no escribe nada.
     *   <li><em>En el mismo hilo</em>, y no con {@code @ApplicationModuleListener}, que es la forma
     *       idiomatica de Spring Modulith y fue la primera que se escribio. Es asincrona, y con eso
     *       <strong>se pierden la IP y la traza</strong>: viven en el MDC, que es un
     *       {@code ThreadLocal}, y el analisis 5.2 las pide en {@code AuditLog}. Hacerlas viajar
     *       exige un {@code TaskDecorator} que copie el MDC, y <strong>Boot 4 no aplica un bean de
     *       {@code TaskDecorator} al ejecutor que usan los {@code @Async}</strong>: se comprobo, el
     *       decorador no se llama nunca y las dos columnas quedaban nulas. Lo que se ganaba a cambio
     *       --que la publicacion quede registrada en la tabla de eventos de la migracion V2-- no
     *       reentrega nada por si solo, asi que el cambio sale perdiendo. Aqui, ademas, un test puede
     *       leer la fila justo despues de la accion en vez de sondear.
     * </ul>
     *
     * <p><strong>No relanza.</strong> Un fallo al escribir la bitacora no puede deshacer la accion
     * --ya se confirmo-- asi que lo unico util es dejarlo gritado en el log con todo lo que hacia
     * falta para reconstruir la fila a mano.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void register(AuditableEvent evento) {
        try {
            auditTrail.save(new AuditLog(
                    evento.actor(),
                    evento.action(),
                    evento.entity(),
                    evento.entityId(),
                    evento.condominiumId(),
                    aJson(evento.oldValues(), evento),
                    aJson(evento.newValues(), evento),
                    delContexto(LogKeys.IP),
                    delContexto(LogKeys.TRAZA),
                    evento.occurredAt()));
        } catch (RuntimeException fallo) {
            log.error(
                    "No se pudo registrar en la bitacora: action={}, entity={}, entityId={}, actor={}, condominio={}, fecha={}.",
                    evento.action(),
                    evento.entity(),
                    evento.entityId(),
                    evento.actor(),
                    evento.condominiumId(),
                    evento.occurredAt(),
                    fallo);
        }
    }

    /**
     * El JSON de un mapa, o nulo si no habia mapa.
     *
     * <p>Si no cabe en la columna se guarda nulo y se avisa, en vez de recortarlo: un JSON cortado a
     * la mitad no se puede volver a leer, y una fila con la accion y sin los valores sigue diciendo
     * quien hizo que y cuando. Perder la fila entera por un valor largo seria mucho peor.
     */
    private @Nullable String aJson(@Nullable Map<String, String> valores, AuditableEvent evento) {
        if (valores == null) {
            return null;
        }
        String escrito = json.writeValueAsString(valores);
        if (escrito.length() > LARGO_MAXIMO) {
            log.warn(
                    "Los valores de {} sobre {} {} ocupan {} caracteres y no caben en la bitacora; la fila se guarda sin ellos.",
                    evento.action(),
                    evento.entity(),
                    evento.entityId(),
                    escrito.length());
            return null;
        }
        return escrito;
    }

    private static @Nullable String delContexto(String key) {
        return MDC.get(key);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AuditEntryView> ofCondominium(
            UUID condominiumId,
            @Nullable String actor,
            @Nullable String entity,
            @Nullable Instant desde,
            @Nullable Instant until,
            int page,
            int tamano) {
        return PagedResponse.de(
                auditTrail.find(
                        condominiumId,
                        actor,
                        entity,
                        desde == null ? PRINCIPIO_DE_LOS_TIEMPOS : desde,
                        until == null ? FIN_DE_LOS_TIEMPOS : until,
                        PageRequest.of(page, tamano, loUltimoPrimero())),
                this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AuditEntryView> ofPlatform(
            @Nullable String actor,
            @Nullable String entity,
            @Nullable Instant desde,
            @Nullable Instant until,
            int page,
            int tamano) {
        return PagedResponse.de(
                auditTrail.findPlatformEntries(
                        actor,
                        entity,
                        desde == null ? PRINCIPIO_DE_LOS_TIEMPOS : desde,
                        until == null ? FIN_DE_LOS_TIEMPOS : until,
                        PageRequest.of(page, tamano, loUltimoPrimero())),
                this::view);
    }

    /**
     * Por fecha descendente: una bitacora se lee empezando por lo ultimo.
     *
     * <p>El segundo criterio de orden es el identificador, para que dos filas del mismo instante
     * --una operacion que publica dos eventos-- no cambien de sitio entre dos paginas.
     */
    private static Sort loUltimoPrimero() {
        return Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));
    }

    /**
     * La purga, por la funcion que declara la migracion V9.
     *
     * <p>No es un {@code DELETE} de JPA: el disparador de la base los rechaza todos, y es asi a
     * proposito. La funcion es el unico camino autorizado, y lo que hace es desactivar el disparador
     * dentro de su propia transaccion.
     */
    @Override
    @Transactional
    public long purge(Instant until) {
        Object deleted = entidades
                .createNativeQuery("select audit_log_purge(cast(:hasta as timestamptz))")
                .setParameter("hasta", until.toString())
                .getSingleResult();
        long cuantas = deleted instanceof Number numero ? numero.longValue() : 0L;
        if (cuantas > 0) {
            log.info("Purgadas {} filas de la bitacora anteriores a {}.", cuantas, until);
        }
        return cuantas;
    }

    private AuditEntryView view(AuditLog fila) {
        return new AuditEntryView(
                fila.getId(),
                fila.getActor(),
                fila.getAction(),
                fila.getEntidad(),
                fila.getEntidadId(),
                fila.getCondominiumId(),
                deJson(fila.getAntes()),
                deJson(fila.getDespues()),
                fila.getIp(),
                fila.getTraceId(),
                fila.getOccurredAt());
    }

    /**
     * Vuelve a leer el mapa.
     *
     * <p>Si el JSON de una fila no se pudiera leer --no deberia pasar nunca, lo escribio este mismo
     * codigo-- se devuelve nulo en vez de tumbar la consulta: la fila sigue diciendo quien hizo que
     * y cuando, que es lo que de verdad se va a buscar.
     */
    private @Nullable Map<String, String> deJson(@Nullable String escrito) {
        if (escrito == null) {
            return null;
        }
        try {
            return json.readValue(escrito, MAPA_DE_TEXTO);
        } catch (RuntimeException ilegible) {
            log.warn("Una fila de la bitacora tiene valores que no se pueden leer; se devuelve sin ellos.", ilegible);
            return null;
        }
    }
}
