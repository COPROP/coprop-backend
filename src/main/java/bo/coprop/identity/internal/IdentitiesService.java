package bo.coprop.identity.internal;

import bo.coprop.identity.AccountActivated;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.Identities;
import bo.coprop.identity.OrigenDeSesion;
import bo.coprop.identity.PlatformAdminGranted;
import bo.coprop.identity.PlatformAdminRevoked;
import bo.coprop.identity.PlatformAdminView;
import bo.coprop.identity.ProveedorDeIdentidad;
import bo.coprop.identity.SesionActivaView;
import bo.coprop.identity.Sesiones;
import bo.coprop.identity.SessionsSignedOut;
import bo.coprop.shared.ConflictoDeEstado;
import bo.coprop.shared.RecursoNoEncontrado;
import bo.coprop.shared.ReglaDeNegocioViolada;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IdentitiesService implements Identities {

    private static final Logger log = LoggerFactory.getLogger(IdentitiesService.class);

    private final UserAccountRepository cuentas;
    private final PlatformAdminRepository superadministradores;
    private final CierreDeAcceso cierre;
    private final Sesiones sesionesDelBff;
    private final ProveedorDeIdentidad proveedor;
    private final ApplicationEventPublisher eventos;
    private final Clock reloj;

    IdentitiesService(
            UserAccountRepository cuentas,
            PlatformAdminRepository superadministradores,
            CierreDeAcceso cierre,
            Sesiones sesionesDelBff,
            ProveedorDeIdentidad proveedor,
            ApplicationEventPublisher eventos,
            Clock reloj) {
        this.cuentas = cuentas;
        this.superadministradores = superadministradores;
        this.cierre = cierre;
        this.sesionesDelBff = sesionesDelBff;
        this.proveedor = proveedor;
        this.eventos = eventos;
        this.reloj = reloj;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean esSuperadministrador(java.util.UUID accountId) {
        return superadministradores.existsById(accountId);
    }

    @Override
    @Transactional
    public PlatformAdminView grantPlatformAdmin(UUID accountId, String actor) {
        UUID quienConcede = cuentaDe(actor);
        if (accountId.equals(quienConcede)) {
            // Lo impide tambien el CHECK de la migracion V7. Comprobarlo aqui permite explicarlo:
            // un fallo de integridad saldria como un 500 sin decir nada util.
            throw new ReglaDeNegocioViolada("Nadie se concede a si mismo el rol de plataforma.");
        }
        if (!cuentas.existsById(accountId)) {
            throw RecursoNoEncontrado.de("la cuenta", accountId);
        }
        if (superadministradores.existsById(accountId)) {
            throw new ConflictoDeEstado("La cuenta %s ya administra la plataforma.".formatted(accountId));
        }

        Instant ahora = reloj.instant();
        PlatformAdmin concedido = superadministradores.save(new PlatformAdmin(accountId, quienConcede, ahora));
        eventos.publishEvent(new PlatformAdminGranted(accountId, quienConcede, ahora));

        log.warn(
                "La cuenta {} administra la plataforma desde ahora; se lo concedio {}. Crea condominios y puede abrir accesos de soporte (Seguridad 10.1).",
                accountId,
                actor);
        return vista(concedido);
    }

    @Override
    @Transactional
    public void revokePlatformAdmin(UUID accountId, String motivo, String actor) {
        if (accountId.equals(cuentaDe(actor))) {
            throw new ReglaDeNegocioViolada(
                    "Nadie se revoca a si mismo el rol de plataforma: pideselo a otro superadministrador.");
        }
        PlatformAdmin quienLoTiene = superadministradores
                .findById(accountId)
                // 404 y no un 422: quien administra la plataforma no es dato publico, asi que
                // preguntar por una cuenta que no lo administra responde lo mismo que preguntar por
                // una que no existe. Mismo criterio que el 404-no-403 del #17.
                .orElseThrow(() -> RecursoNoEncontrado.de("el superadministrador", accountId));

        Instant ahora = reloj.instant();
        superadministradores.delete(quienLoTiene);
        // Hace falta que la fila se vaya ANTES de cerrar las sesiones: el cierre pregunta por los
        // superadministradores --no le cierra las sesiones a quien lo es-- y leeria la fila vieja.
        superadministradores.flush();
        boolean sesionesCerradas = cierre.deTodasFormas(accountId);

        eventos.publishEvent(new PlatformAdminRevoked(accountId, motivo, sesionesCerradas, actor, ahora));
        log.warn("La cuenta {} ya no administra la plataforma; se lo quito {}. Motivo: {}.", accountId, actor, motivo);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlatformAdminView> platformAdmins() {
        return superadministradores.findAllByOrderByGrantedAtDescAccountIdDesc().stream()
                .map(IdentitiesService::vista)
                .toList();
    }

    @Override
    @Transactional
    public @Nullable AccountView signIn(String idpSubject, String email, boolean emailVerificado) {
        Instant ahora = reloj.instant();

        // El camino de siempre: la cuenta ya se vinculo y se reconoce por el sujeto. Va primero
        // porque es el de todos los logins menos uno, y porque el correo puede cambiar en el
        // proveedor sin que la cuenta deje de ser la misma.
        Optional<UserAccount> vinculada = cuentas.findByIdpSubject(idpSubject);
        if (vinculada.isPresent()) {
            UserAccount cuenta = vinculada.get();
            if (!cuenta.puedeEntrar()) {
                log.info("Acceso denegado: la cuenta {} esta en estado {}.", cuenta.getId(), cuenta.getStatus());
                return null;
            }
            cuenta.registrarAcceso(ahora);
            return cuenta.vista();
        }

        // Primer login. Vincular por correo es lo que hace que entrar con contraseña y entrar con
        // Google caigan en la misma cuenta, y eso solo vale si el proveedor garantiza que el correo
        // es de quien dice. Sin esa garantia, cualquiera que pudiera registrar una cuenta con el
        // correo ajeno se quedaria con la cuenta de COPROP de esa persona.
        if (!emailVerificado) {
            log.warn("Primer login rechazado: el token trae {} sin verificar.", email);
            return null;
        }

        Optional<UserAccount> porCorreo = cuentas.findByEmailIgnoreCase(email);
        if (porCorreo.isEmpty()) {
            // Autentico, pero en COPROP no es nadie. El acceso lo da la invitacion (Seguridad
            // 10.1), y crear la cuenta cuando una invitacion lo respalda es el issue #18.
            log.info("Acceso denegado: ninguna cuenta con el correo del token.");
            return null;
        }

        UserAccount cuenta = porCorreo.get();
        if (!cuenta.puedeEntrar()) {
            log.info("Acceso denegado: la cuenta {} esta en estado {}.", cuenta.getId(), cuenta.getStatus());
            return null;
        }

        // Esta cuenta ya es de otro sujeto. Llegar aqui significa que existen dos identidades
        // distintas con el mismo correo, y vincular la segunda le entregaria la cuenta de la
        // primera. El realm no deberia permitirlo --no admite correos repetidos-- asi que esto es
        // una red de seguridad: si algun dia se afloja esa opcion, o se añade un proveedor que no
        // la respete, el agujero no se abre solo. Rehacer el vinculo es una operacion explicita
        // del issue #86, que es el del ciclo de vida de la credencial.
        if (cuenta.getIdpSubject() != null) {
            log.warn(
                    "Acceso denegado: la cuenta {} ya esta vinculada a otro sujeto y llego {}.",
                    cuenta.getId(),
                    idpSubject);
            return null;
        }

        cuenta.vincular(idpSubject, ahora);
        eventos.publishEvent(new AccountActivated(cuenta.getId(), cuenta.getPersonId(), idpSubject, ahora));

        return cuenta.vista();
    }

    /**
     * Las dos mitades, juntas y ordenadas de la mas reciente a la mas antigua.
     *
     * <p>Lo del BFF se busca por <strong>los dos nombres</strong> con que una sesion puede estar
     * indexada, igual que en el cierre: el sujeto del proveedor --que es el que se guarda hoy-- y
     * el identificador de la cuenta. Depender de cual es hoy haria que la lista saliera vacia en
     * silencio el dia que Spring Security cambie.
     */
    @Override
    @Transactional(readOnly = true)
    public List<SesionActivaView> sessionsOf(UUID accountId) {
        UserAccount cuenta =
                cuentas.findById(accountId).orElseThrow(() -> RecursoNoEncontrado.de("la cuenta", accountId));

        var todas = new java.util.ArrayList<SesionActivaView>();
        for (var sesion : sesionesDelBff.listarLasDe(nombresDe(cuenta))) {
            todas.add(new SesionActivaView(
                    sesion.id(),
                    OrigenDeSesion.BROWSER,
                    null,
                    sesion.creada(),
                    sesion.ultimoAcceso(),
                    sesion.ultimoAcceso().plus(sesion.inactividad())));
        }
        try {
            for (var sesion : proveedor.sesionesDe(cuenta.getEmail())) {
                todas.add(new SesionActivaView(
                        sesion.id(),
                        OrigenDeSesion.PROVIDER,
                        sesion.ip(),
                        sesion.creada(),
                        sesion.ultimoAcceso(),
                        null));
            }
        } catch (ProveedorDeIdentidad.FalloDelProveedorDeIdentidad fallo) {
            // Media lista es mejor que ninguna: quien mira esta pantalla esta intentando averiguar
            // si le robaron la cuenta, y dejarle sin respuesta porque Keycloak no contesta no le
            // ayuda a decidir nada. Lo que no se puede hacer es callarlo en el log.
            log.error(
                    "No se pudieron listar las sesiones de la cuenta {} en el proveedor de identidad; se devuelven solo las del navegador. La lista que ve el usuario esta INCOMPLETA.",
                    accountId,
                    fallo);
        }
        todas.sort(
                java.util.Comparator.comparing(SesionActivaView::ultimoAcceso).reversed());
        return List.copyOf(todas);
    }

    @Override
    @Transactional
    public int signOutEverywhere(UUID accountId, String actor) {
        UserAccount cuenta =
                cuentas.findById(accountId).orElseThrow(() -> RecursoNoEncontrado.de("la cuenta", accountId));

        CierreDeAcceso.Cerradas cerradas = cierre.porPeticionPropia(cuenta.getId());
        eventos.publishEvent(
                new SessionsSignedOut(accountId, cerradas.delBff(), cerradas.enElProveedor(), actor, reloj.instant()));

        log.warn(
                "La cuenta {} cerro todas sus sesiones: {} del navegador, y en el proveedor {} (Seguridad 5.3).",
                accountId,
                cerradas.delBff(),
                cerradas.enElProveedor() ? "tambien" : "NO");
        return cerradas.delBff();
    }

    /**
     * Los nombres con los que una sesion de esa cuenta puede estar indexada.
     *
     * <p>Es la misma pareja que arma {@link CierreDeAcceso}, y por el mismo motivo. Se repite aqui
     * en vez de exponerla porque son tres lineas y compartirlas obligaria a sacar un metodo
     * publico de una clase que es un detalle del modulo.
     */
    private static java.util.Set<String> nombresDe(UserAccount cuenta) {
        var nombres = new java.util.HashSet<String>();
        nombres.add(cuenta.getId().toString());
        String sujeto = cuenta.getIdpSubject();
        if (sujeto != null) {
            nombres.add(sujeto);
        }
        return nombres;
    }

    private static PlatformAdminView vista(PlatformAdmin fila) {
        return new PlatformAdminView(fila.getAccountId(), fila.getGrantedBy(), fila.getGrantedAt());
    }

    /**
     * El actor como cuenta.
     *
     * <p>En toda operacion de escritura el actor es el identificador de la cuenta en texto, que es
     * lo que pone {@code IdentidadAutenticada}. Aqui hace falta como {@code UUID} porque va a la
     * columna {@code granted_by}, que tiene clave foranea. Un actor que no sea un identificador
     * --el "arranque" del primer superadministrador-- no puede llegar a estas operaciones: entran
     * por HTTP, y alli siempre hay una cuenta.
     */
    private static UUID cuentaDe(String actor) {
        return UUID.fromString(actor);
    }
}
