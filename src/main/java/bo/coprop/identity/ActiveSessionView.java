package bo.coprop.identity;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Una sesion abierta de una cuenta, venga de donde venga. Issue <strong>#22</strong>, Seguridad 8.
 *
 * <p>Junta las dos mitades --la del BFF y la del proveedor de identidad-- en una sola forma, porque
 * lo que el usuario mira cuando sospecha de una sesion ajena no es la arquitectura: es una lista de
 * sitios desde los que alguien esta dentro. El {@link #origen} dice de cual es cada una, y no es un
 * detalle de implementacion que se pueda esconder: cerrar una cosa u otra tiene consecuencias
 * distintas, y la lista con las dos es la unica que no miente.
 *
 * <p><strong>No se puede cerrar una sola.</strong> El identificador viaja para que el cliente
 * pueda señalar la actual y para que dos sesiones del mismo sitio no se confundan, pero la unica
 * operacion que hay es cerrarlas todas: cerrar la de Keycloak y dejar la del BFF deja entrando a
 * quien tiene la cookie, y al reves deja a la app movil renovando. Mientras no se pueda emparejar
 * una con otra, "cerrar esta" no se puede cumplir.
 *
 * @param id el identificador de la sesion en su origen
 * @param origen de cual de las dos mitades sale
 * @param ip desde donde se abrio; <strong>solo la del proveedor la tiene</strong>, la tabla de
 *     sesiones del BFF no guarda de donde vino la peticion
 * @param creada cuando se abrio
 * @param ultimoAcceso la ultima vez que se uso
 * @param expira cuando caduca por inactividad si no se vuelve a usar; nula en las del proveedor,
 *     cuyos tiempos los gobierna el realm y no esta aplicacion
 */
public record ActiveSessionView(
        String id,
        SessionOrigin origin,
        @Nullable String ip,
        Instant created,
        Instant lastAccess,
        @Nullable Instant expiresAt) {}
