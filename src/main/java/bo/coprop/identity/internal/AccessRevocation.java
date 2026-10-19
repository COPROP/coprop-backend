package bo.coprop.identity.internal;

import bo.coprop.identity.IdentityProvider;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.MembershipStatus;
import bo.coprop.identity.PermissionMatrix;
import bo.coprop.identity.Sessions;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cuando una cuenta pierde una puerta, se le cierran las sesiones. Issues <strong>#19</strong> y
 * <strong>#20</strong>.
 *
 * <p>Las <strong>cuatro entradas</strong> estan aqui y no repartidas por los servicios porque las
 * cuatro acaban en el mismo par de cierres, y conviene que haya un solo sitio donde leer cuando se
 * cierra y por que:
 *
 * <ul>
 *   <li>{@link #siSeQuedaSinCondominios}: la baja y la suspension del #19, que cierran
 *       <em>solo</em> si a la cuenta no le queda ningun condominio al que entrar.
 *   <li>{@link #siPierdeAlgunRolAdministrativo}: el cambio de roles del #20, que cierra
 *       <em>aunque</em> le queden, porque lo que caduca es el nivel de confianza de la sesion y no
 *       el acceso (Seguridad 8).
 *   <li>{@link #deTodasFormas}: la revocacion del rol de plataforma, donde no hay nada que
 *       condicionar.
 *   <li>{@link #porPeticionPropia}: el "cerrar sesion en todos los dispositivos" del #22, la unica
 *       que no la provoca una perdida sino la propia persona.
 * </ul>
 *
 * <p>La condicion de la primera no es un adorno. Una sesion de COPROP no es de un condominio: es la del
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
class AccessRevocation {

    private static final Logger log = LoggerFactory.getLogger(AccessRevocation.class);

    private final MembershipRepository membresias;
    private final PlatformAdminRepository superadministradores;
    private final UserAccountRepository cuentas;
    private final Sessions sesionesDelBff;
    private final IdentityProvider proveedor;

    AccessRevocation(
            MembershipRepository membresias,
            PlatformAdminRepository superadministradores,
            UserAccountRepository cuentas,
            Sessions sesionesDelBff,
            IdentityProvider proveedor) {
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
                .anyMatch(membresia -> membresia.getStatus() == MembershipStatus.ACTIVE);
        if (leQuedaAlguna) {
            return false;
        }
        return cerrar(accountId).enElProveedor();
    }

    /**
     * Cierra las sesiones de esa cuenta si el cambio le <strong>retira</strong> un rol
     * administrativo. Issue <strong>#20</strong>.
     *
     * <p>Es la segunda entrada de esta clase, y la regla es distinta de la primera a proposito.
     * {@link #siSeQuedaSinCondominios} cierra cuando a la cuenta no le queda ningun sitio al que
     * entrar; esto cierra <strong>aunque le queden</strong>, porque lo que caduco no es el acceso
     * sino el nivel de confianza con el que se abrio esa sesion. Lo pide Seguridad 8, que nombra la
     * revocacion de sesiones "al retirar un rol administrativo" junto a la de la baja.
     *
     * <p>No hace falta para cortar lo que ese rol permitia --los roles se releen en cada peticion,
     * asi que la siguiente ya va con los nuevos-- y por eso podria parecer de adorno. No lo es: una
     * sesion administrativa es una sesion de mas valor, y la politica que el issue <strong>#22</strong>
     * le pondra encima --una hora de inactividad para los roles administrativos y veinticuatro para
     * el resto, Seguridad 5.3-- se decide por peticion justamente porque la sesion no sabe con que
     * roles nacio. Mientras eso no exista, cerrarla es la unica forma de que un rol retirado no deje
     * detras una sesion que se comporta como la de un administrador.
     *
     * <p>Solo cuando se <strong>quita</strong>: añadir {@code FINANCE_ADMIN} a quien ya administra
     * no cierra nada, y cambiar entre roles que no son administrativos tampoco.
     *
     * @param retirados los roles que la membresia tenia y ya no tiene
     * @return si se cerraron. Falso tanto cuando no hacia falta como cuando se intento y fallo, por
     *     lo mismo que en la otra entrada
     */
    boolean siPierdeAlgunRolAdministrativo(UUID accountId, Set<MembershipRole> retirados) {
        if (retirados.stream().noneMatch(PermissionMatrix::esAdministrativo)) {
            return false;
        }
        log.info(
                "A la cuenta {} se le retiraron los roles administrativos {}; se le cierran las sesiones (Seguridad 8).",
                accountId,
                retirados);
        return cerrar(accountId).enElProveedor();
    }

    /**
     * Cierra las sesiones de esa cuenta sin condicion ninguna. Issue <strong>#20</strong>.
     *
     * <p>La usa la revocacion del rol de plataforma, que es el mayor de los roles administrativos y
     * no cuelga de ninguna membresia: no hay roles que comparar ni condominios que contar, solo un
     * rol que se fue.
     */
    boolean deTodasFormas(UUID accountId) {
        return cerrar(accountId).enElProveedor();
    }

    /**
     * Cierra las sesiones porque lo pide su propio dueño. Issue <strong>#22</strong>.
     *
     * <p>Es la cuarta entrada, y la unica que no la provoca una perdida: aqui no se retiro nada, es
     * la persona la que decide cerrarlas --tipicamente porque perdio el telefono, que es el motivo
     * que Seguridad 5.3 pone en la tabla--. Por eso no hay nada que condicionar, como en
     * {@link #deTodasFormas}, y por eso devuelve el detalle: lo que se audita es cuantas habia.
     */
    Cerradas porPeticionPropia(UUID accountId) {
        return cerrar(accountId);
    }

    /**
     * Que quedo cerrado.
     *
     * @param delBff cuantas sesiones de servidor se borraron
     * @param enElProveedor si el proveedor tambien cerro las suyas. Falso cuando no contesto, y
     *     entonces la app movil sigue renovando su refresh token
     */
    record Cerradas(int delBff, boolean enElProveedor) {}

    /**
     * Los dos cierres, y ninguno impide al otro.
     *
     * <p>El del BFF primero porque no sale a la red: si el de Keycloak falla, al menos la cookie ya
     * no vale. Al reves, un Keycloak caido dejaria las dos puertas abiertas.
     */
    private Cerradas cerrar(UUID accountId) {
        UserAccount cuenta = cuentas.findById(accountId).orElse(null);
        if (cuenta == null) {
            // No puede pasar: la membresia tiene clave foranea a la cuenta. Si pasara, no hay nada
            // que cerrar y tampoco hay nada que reparar aqui.
            log.warn("Se pidio cerrar las sesiones de la cuenta {}, que no existe.", accountId);
            return new Cerradas(0, false);
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
        } catch (IdentityProvider.FalloDelProveedorDeIdentidad fallo) {
            log.error(
                    "No se pudieron cerrar las sesiones de la cuenta {} en el proveedor de identidad; se cerraron {} del BFF. La baja NO se deshace: el filtro de condominio ya le niega el acceso en la siguiente peticion. Si tiene la app movil instalada, su refresh token sigue sirviendo hasta que caduque.",
                    accountId,
                    delBff,
                    fallo);
            return new Cerradas(delBff, false);
        }
        log.info("Cerrado el acceso de la cuenta {}: {} sesiones del BFF y las del proveedor.", accountId, delBff);
        return new Cerradas(delBff, true);
    }
}
