package bo.coprop.identity;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Una cuenta que administra la plataforma. Issue <strong>#20</strong>.
 *
 * <p>No lleva estado ni vigencia, y es por la forma del hecho que representa: o administras la
 * plataforma o no, sin grados. Revocar el rol <strong>borra la fila</strong>, al contrario que la
 * baja de una membresia, que la cierra. Las dos decisiones son coherentes con lo que cada tabla
 * significa: {@code membership} describe un periodo --el tiempo que alguien pertenecio a un
 * edificio-- y {@code platform_admin} describe el presente.
 *
 * <p>La consecuencia es que <strong>la historia de este rol vive solo en la bitacora</strong>, con
 * su actor y su motivo, y se purga al año como todo lo demas (Seguridad 10.1). Es el mismo precio
 * que ya se acepto para el motivo de una baja. A cambio, no hay dos sitios donde mirar quien
 * administra hoy la plataforma: una fila cerrada aqui obligaria a filtrarla en el arranque y en el
 * filtro de identidad, que preguntan por la clave primaria, y eso es justo lo que se olvida.
 *
 * @param grantedBy quien lo concedio; nulo en el primero, que lo abre el arranque de la plataforma
 */
public record PlatformAdminView(UUID accountId, @Nullable UUID grantedBy, Instant grantedAt) {}
