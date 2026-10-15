package bo.coprop.identity.internal;

import bo.coprop.identity.MembershipStatus;
import bo.coprop.identity.ProveedorDeIdentidad;
import bo.coprop.identity.Sesiones;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cuando una cuenta se queda sin puerta, se le cierran las sesiones. Issue <strong>#19</strong>.
 *
 * <p>El alcance del issue pide "cierre de sesiones activas al dar de baja", y la regla de cuando
 * esta aqui porque es una sola y conviene que haya un solo sitio donde leerla: <strong>se cierran
 * las sesiones de una cuenta cuando pierde su ultima membresia que da acceso y no administra la
 * plataforma</strong>.
 *
 * <p>Esa condicion no es un adorno. Una sesion de COPROP no es de un condominio: es la del
 * navegador o la de la app, y vale para todos los edificios en los que alguien participe. Cerrarla
 * en cada baja echaria de un edificio a quien sigue perteneciendo a otro, y Seguridad 6.3 dice de
 * la baja justo lo contrario: "afecta solo a ese condominio". Donde la cuenta no tiene ya ningun
 * sitio al que entrar, cerrarla no le quita nada a nadie y hace cierto lo que §10.1 pide: que no
 * queden sesiones activas de quien ya no pertenece.
 *
 * <p><strong>Lo que de verdad corta el acceso no es esto</strong>, y conviene no confundirse: es el
 * filtro de condominio, que relee la membresia en cada peticion y responde 404 en la siguiente.
 * Esto cierra ademas la puerta de la casa, que es lo que hace que el refresh token de la app movil
 * deje de servir --Seguridad 5.3-- y que la cookie del navegador no sobreviva hasta su caducidad.
 *
 * <p><strong>Un fallo no deshace la baja.</strong> Es la decision contraria a la del correo de la
 * invitacion (#18), que si la deshace, y el motivo es que ahi el correo <em>era</em> la operacion
 * --una invitacion que no llega no esta emitida-- mientras que aqui el acceso ya esta cortado por el
 * filtro. Deshacer la baja porque Keycloak no contesta dejaria a la persona dentro del condominio,
 * que es mucho peor que dejarle una sesion abierta de la que no vera nada.
 */
@Component
class CierreDeAcceso {

    private static final Logger log = LoggerFactory.getLogger(CierreDeAcceso.class);

    private final MembershipRepository membresias;
    private final PlatformAdminRepository superadministradores;
    private final UserAccountRepository cuentas;
    private final Sesiones sesionesDelBff;
    private final ProveedorDeIdentidad proveedor;

    CierreDeAcceso(
            MembershipRepository membresias,
            PlatformAdminRepository superadministradores,
            UserAccountRepository cuentas,
            Sesiones sesionesDelBff,
            ProveedorDeIdentidad proveedor) {
        this.membresias = membresias;
        this.superadministradores = superadministradores;
        this.cuentas = cuentas;
        this.sesionesDelBff = sesionesDelBff;
        this.proveedor = proveedor;
    }

    /**
     * Cierra las sesiones de esa cuenta si ya no le queda ningun condominio al que entrar.
     *
     * <p>Se llama <strong>despues</strong> de haber cerrado o suspendido la membresia y dentro de la
     * misma transaccion: la consulta de las que quedan tiene que ver el cambio, y lo ve porque
     * Hibernate vuelca los cambios pendientes antes de consultar.
     *
     * @return si se cerraron. Falso tanto cuando no hacia falta como cuando se intento y fallo, y
     *     por eso lo que se guarda en la bitacora es esto y no "se intento": lo que importa dentro
     *     de un año es si las sesiones quedaron cerradas
     */
    boolean siSeQuedaSinCondominios(UUID accountId) {
        if (superadministradores.existsById(accountId)) {
            // Entra a cualquier condominio sin membresia (Seguridad 3), asi que perder una no le
            // deja sin sitio al que entrar.
            return false;
        }
        boolean leQuedaAlguna = membresias.findByAccountIdAndValidToIsNull(accountId).stream()
                .anyMatch(membresia -> membresia.getStatus() == MembershipStatus.ACTIVA);
        if (leQuedaAlguna) {
            return false;
        }
        return cerrar(accountId);
    }

    /**
     * Los dos cierres, y ninguno impide al otro.
     *
     * <p>El del BFF primero porque no sale a la red: si el de Keycloak falla, al menos la cookie ya
     * no vale. Al reves, un Keycloak caido dejaria las dos puertas abiertas.
     */
    private boolean cerrar(UUID accountId) {
        UserAccount cuenta = cuentas.findById(accountId).orElse(null);
        if (cuenta == null) {
            // No puede pasar: la membresia tiene clave foranea a la cuenta. Si pasara, no hay nada
            // que cerrar y tampoco hay nada que reparar aqui.
            log.warn("Se pidio cerrar las sesiones de la cuenta {}, que no existe.", accountId);
            return false;
        }

        Set<String> nombres = new HashSet<>();
        nombres.add(accountId.toString());
        String sujeto = cuenta.getIdpSubject();
        if (sujeto != null) {
            nombres.add(sujeto);
        }

        int delBff = sesionesDelBff.cerrarLasDe(nombres);
        try {
            proveedor.cerrarSesiones(cuenta.getEmail());
        } catch (ProveedorDeIdentidad.FalloDelProveedorDeIdentidad fallo) {
            log.error(
                    "No se pudieron cerrar las sesiones de la cuenta {} en el proveedor de identidad; se cerraron {} del BFF. La baja NO se deshace: el filtro de condominio ya le niega el acceso en la siguiente peticion. Si tiene la app movil instalada, su refresh token sigue sirviendo hasta que caduque.",
                    accountId,
                    delBff,
                    fallo);
            return false;
        }
        log.info("Cerrado el acceso de la cuenta {}: {} sesiones del BFF y las del proveedor.", accountId, delBff);
        return true;
    }
}
