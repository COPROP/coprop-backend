package bo.coprop.identity;

import bo.coprop.shared.AuditableEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Alguien cerro todas sus sesiones a la vez. Issue <strong>#22</strong>, Seguridad 5.3.
 *
 * <p>Es el "cerrar sesion en todos los dispositivos" de la tabla de 5.3, y su motivo tipico esta
 * escrito en la propia tabla: la perdida o el robo del celular. Por eso se audita y el cierre de
 * sesion normal no: uno es rutina y el otro es la reaccion a un incidente, y quien investigue
 * despues necesita saber a que hora la persona se dio cuenta.
 *
 * <p><strong>Va sin condominio.</strong> Una sesion no es de un edificio --es la del navegador o la
 * del telefono, y vale para todos aquellos en los que la cuenta participe-- asi que esta fila no
 * cabe en la bitacora de un condominio y sale por {@code GET /api/v1/auditoria}. Es el mismo
 * criterio que el rol de plataforma, y por el mismo motivo.
 *
 * <p>El actor es siempre la propia cuenta: hoy nadie cierra las sesiones de otro por esta via. El
 * administrador que retira un rol o da de baja sigue cerrandolas, pero eso ya se audita en el
 * evento de la operacion que lo provoco, donde el motivo esta a la vista.
 *
 * @param cerradas cuantas sesiones del BFF se cerraron. Las del proveedor no se cuentan: las cierra
 *     todas de una sola llamada y no dice cuantas eran
 * @param enElProveedor si tambien se cerraron las del proveedor. Falso cuando el proveedor no
 *     contesto, y entonces la app movil sigue renovando su token: es justo lo que hay que poder
 *     leer un mes despues
 */
public record SessionsSignedOut(UUID accountId, int cerradas, boolean enElProveedor, String actor, Instant occurredAt)
        implements AuditableEvent {

    @Override
    public String action() {
        return "SESSIONS_CLOSED";
    }

    /** Nulo: una sesion no pertenece a ningun condominio. */
    @Override
    public @Nullable UUID condominiumId() {
        return null;
    }

    @Override
    public String entity() {
        return "cuenta";
    }

    @Override
    public UUID entityId() {
        return accountId;
    }

    /** Nada que guardar: lo que habia era un numero de sesiones que ya esta en {@link #newValues()}. */
    @Override
    public Map<String, String> oldValues() {
        return Map.of();
    }

    @Override
    public Map<String, String> newValues() {
        return Map.of("cerradas", String.valueOf(cerradas), "enElProveedor", String.valueOf(enElProveedor));
    }
}
