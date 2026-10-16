package bo.coprop.api;

import bo.coprop.identity.Sesiones;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * El cierre de sesiones del BFF, sobre Spring Session. Issue <strong>#19</strong>.
 *
 * <p>Implementa un puerto de {@code identity} desde {@code api}, que es la direccion que permite la
 * regla 6 de ARCHITECTURE.md. Vive aqui y no alli porque la sesion es de la cadena de seguridad:
 * quien la crea es {@code oauth2Login} y donde se guarda lo decide la configuracion de Spring
 * Session, no el dominio.
 *
 * <p>Borra la fila de la sesion, que es lo que Seguridad 5.1 llama "se puede revocar" al explicar
 * por que la sesion vive en la base: sin estado en servidor no habria nada que borrar y la cookie
 * seguiria valiendo hasta caducar.
 */
@Component
class SesionesDelBff implements Sesiones {

    private static final Logger log = LoggerFactory.getLogger(SesionesDelBff.class);

    /**
     * El repositorio con el indice por nombre de principal.
     *
     * <p>Se inyecta por la interfaz que tiene el indice y no por {@code JdbcIndexedSessionRepository}:
     * lo que hace falta es poder buscar por nombre, y atarse a la implementacion JDBC obligaria a
     * tocar esto el dia que las sesiones se muden a otro sitio. El comodin del tipo es porque cada
     * implementacion trae su propio tipo de sesion y aqui solo se usan identificadores.
     */
    private final FindByIndexNameSessionRepository<? extends Session> sesiones;

    SesionesDelBff(FindByIndexNameSessionRepository<? extends Session> sesiones) {
        this.sesiones = sesiones;
    }

    /**
     * Una consulta por indice por cada nombre, y el conjunto de lo encontrado.
     *
     * <p>Los identificadores se juntan antes de borrar: con dos nombres que apunten a la misma
     * sesion --que es lo que pasaria si algun dia se persistiera la identidad de COPROP ademas del
     * sujeto de Keycloak-- borrarla dos veces contaria dos y el numero que acaba en la bitacora
     * mentiria.
     */
    @Override
    public int cerrarLasDe(Set<String> nombres) {
        Set<String> encontradas = new HashSet<>();
        for (String nombre : nombres) {
            encontradas.addAll(sesiones.findByPrincipalName(nombre).keySet());
        }
        encontradas.forEach(sesiones::deleteById);
        if (!encontradas.isEmpty()) {
            log.info("Cerradas {} sesiones del BFF.", encontradas.size());
        }
        return encontradas.size();
    }

    /**
     * Lo mismo que el cierre, pero devolviendo lo que hay. Issue <strong>#22</strong>.
     *
     * <p>Se juntan en un mapa por identificador antes de ordenar, por el mismo motivo que alli: dos
     * nombres que apunten a la misma sesion la listarian dos veces y el usuario creeria tener dos
     * sesiones abiertas donde hay una.
     *
     * <p>El {@code maxInactiveInterval} que sale de aqui es el que el filtro de vigencia dejo
     * puesto en la ultima peticion de esa sesion, asi que una sesion administrativa dice una hora
     * y una ordinaria veinticuatro. Es un dato de la sesion y no una constante.
     */
    @Override
    public List<SesionDelBff> listarLasDe(Set<String> nombres) {
        Map<String, Session> encontradas = new HashMap<>();
        for (String nombre : nombres) {
            encontradas.putAll(sesiones.findByPrincipalName(nombre));
        }
        return encontradas.values().stream()
                .map(SesionesDelBff::aVista)
                .sorted(Comparator.comparing(SesionDelBff::ultimoAcceso).reversed())
                .toList();
    }

    private static SesionDelBff aVista(Session sesion) {
        return new SesionDelBff(
                sesion.getId(),
                sesion.getCreationTime(),
                sesion.getLastAccessedTime(),
                sesion.getMaxInactiveInterval());
    }
}
