package bo.coprop.condominium;

import bo.coprop.shared.AuditableEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Se corrigieron el nombre, el NIT o la direccion de un condominio. Issue #90.
 *
 * <p>Lo que de verdad hay que tener auditado aqui es el <strong>NIT</strong>: identifica legalmente
 * al condominio, y dos con el mismo serian el mismo --a partir de ahi los pagos de uno podrian
 * aplicarse al otro--. Un cambio de NIT sin rastro dejaria sin explicar por que el condominio que
 * facturaba a un contribuyente ahora factura a otro.
 *
 * <p>Los tres campos viajan en {@code oldValues} y en {@code newValues} aunque solo haya cambiado uno. Es
 * a proposito: una bitacora que solo guarda lo que cambio obliga a reconstruir el resto leyendo
 * hacia atras, y la pregunta que se hace un año despues no es "que cambio" sino "como estaba".
 *
 * @param actor quien lo corrigio
 * @param occurredAt cuando
 */
public record CondominiumEdited(
        UUID condominiumId,
        String previousName,
        String previousNit,
        @Nullable String previousAddress,
        String name,
        String nit,
        @Nullable String address,
        String actor,
        Instant occurredAt)
        implements AuditableEvent {

    /** La direccion puede ser nula y {@code Map.of} no admite nulos. */
    private static Map<String, String> fila(String name, String nit, @Nullable String address) {
        var campos = new LinkedHashMap<String, String>();
        campos.put("name", name);
        campos.put("nit", nit);
        campos.put("address", address == null ? "" : address);
        return campos;
    }

    @Override
    public String action() {
        return "CONDOMINIUM_EDITED";
    }

    @Override
    public String entity() {
        return "condominio";
    }

    @Override
    public UUID entityId() {
        return condominiumId;
    }

    @Override
    public Map<String, String> oldValues() {
        return fila(previousName, previousNit, previousAddress);
    }

    @Override
    public Map<String, String> newValues() {
        return fila(name, nit, address);
    }
}
