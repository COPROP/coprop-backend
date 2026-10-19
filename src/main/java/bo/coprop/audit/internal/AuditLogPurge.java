package bo.coprop.audit.internal;

import bo.coprop.audit.AuditTrail;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Borra la bitacora que paso del año. Cuarto punto del alcance del issue #21 y Seguridad 10.1.
 *
 * <p><strong>Purga y no archivado.</strong> El issue admite las dos --"purga o archivado a los 12
 * meses"-- y archivar exige decidir a donde, con que formato y quien lo lee despues; eso es una
 * decision de operacion que no tiene sitio donde aterrizar todavia. Borrar cumple la retencion, que
 * es lo que el analisis pide, y el dia que haga falta archivar, lo que cambia es el cuerpo de este
 * componente y no nada de lo que hay alrededor.
 *
 * <p>Corre una vez al dia, de madrugada. No cada hora: lo que se borra es de hace doce meses, asi
 * que adelantarse unas horas no le importa a nadie, y una tarea que recorre la tabla entera conviene
 * que caiga cuando nadie la esta consultando.
 *
 * <p>La fecha de corte se calcula con el {@code Clock} inyectado y se le pasa al puerto, en vez de
 * dejar que el puerto la calcule. Es la regla de ARCHITECTURE.md, y aqui tiene una consecuencia
 * concreta: un test puede comprobar que la purga respeta el limite sin esperar doce meses.
 */
@Component
class AuditLogPurge {

    private static final Logger log = LoggerFactory.getLogger(AuditLogPurge.class);

    /** Un año, que es la retencion que fija Seguridad 10.1. */
    private static final Duration RETENCION = Duration.ofDays(365);

    private final AuditTrail auditTrail;
    private final Clock clock;

    AuditLogPurge(AuditTrail auditTrail, Clock clock) {
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    /**
     * A las tres de la mañana, hora del servidor.
     *
     * <p>Con {@code cron} y no con {@code fixedDelay}: un retardo fijo corre tambien al arrancar y
     * se desplaza con cada reinicio, asi que en un despliegue movido la purga caeria a cualquier
     * hora. Y al estar fuera del horario de cualquier zona de Bolivia, no compite con nadie.
     */
    @Scheduled(cron = "0 0 3 * * *")
    void purge() {
        long deleted = auditTrail.purge(clock.instant().minus(RETENCION));
        if (deleted > 0) {
            log.info("La purga diaria quito {} filas de la bitacora.", deleted);
        }
    }
}
