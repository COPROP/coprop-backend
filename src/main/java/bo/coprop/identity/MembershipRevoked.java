package bo.coprop.identity;

import bo.coprop.shared.EventoAuditable;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Se dio de baja a alguien de un condominio. Issue <strong>#19</strong>.
 *
 * <p>Seguridad 8 pide los cambios de rol en la bitacora, y esto es el mayor de todos: le quita
 * todos los que tenia. Es tambien el evento que explica por que una cuenta dejo de ver un edificio,
 * que es la primera pregunta de cualquiera que mire la bitacora despues de una queja.
 *
 * <p>Lleva el <strong>motivo</strong> dentro, y es el unico sitio donde queda: la fila cerrada dice
 * cuando y la bitacora por que. El issue lo pide --"baja de membresia por el administrador, con
 * motivo"-- y no se guarda ademas en la membresia para no tener el mismo dato en dos lados, que es
 * como acaban discrepando. La consecuencia conocida es la retencion: la bitacora se purga al año
 * (Seguridad 10.1) y la fila cerrada se queda, asi que pasado un año se sabra que la baja existio y
 * no por que.
 *
 * @param roles los que tenia al darsele de baja. Van dentro porque para entonces la fila esta
 *     cerrada y nadie la va a volver a leer para reconstruir la bitacora
 * @param sesionesCerradas si se le cerraron las sesiones, que solo pasa cuando esta era su ultima
 *     membresia activa y no administra la plataforma
 */
public record MembershipRevoked(
        UUID condominiumId,
        UUID membershipId,
        UUID accountId,
        Set<MembershipRole> roles,
        String motivo,
        boolean sesionesCerradas,
        String actor,
        Instant occurredAt)
        implements EventoAuditable {

    @Override
    public String accion() {
        return "MEMBRESIA_DADA_DE_BAJA";
    }

    @Override
    public String entidad() {
        return "membresia";
    }

    @Override
    public UUID entidadId() {
        return membershipId;
    }

    /** Lo que habia: la cuenta y sus roles. Ordenados, para que dos bajas iguales se vean iguales. */
    @Override
    public Map<String, String> antes() {
        return Map.of(
                "accountId",
                accountId.toString(),
                "roles",
                roles.stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
    }

    /**
     * Despues no queda membresia, asi que lo que se guarda es por que y con que consecuencia.
     *
     * <p>No es nulo --que significaria "no quedan valores que mirar"-- porque el motivo es
     * precisamente lo que el issue exige registrar.
     */
    @Override
    public Map<String, String> despues() {
        return Map.of("motivo", motivo, "sesionesCerradas", String.valueOf(sesionesCerradas));
    }
}
