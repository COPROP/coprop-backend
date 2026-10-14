package bo.coprop.identity.internal;

import bo.coprop.identity.InvitationAccepted;
import bo.coprop.identity.InvitationIssued;
import bo.coprop.identity.InvitationRevoked;
import bo.coprop.identity.InvitationView;
import bo.coprop.identity.Invitations;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.MembershipView;
import bo.coprop.identity.Memberships;
import bo.coprop.identity.ProveedorDeIdentidad;
import bo.coprop.notifications.Correo;
import bo.coprop.shared.RecursoNoEncontrado;
import bo.coprop.shared.ReglaDeNegocioViolada;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InvitationsService implements Invitations {

    private static final Logger log = LoggerFactory.getLogger(InvitationsService.class);

    /** Siete dias, que es lo que fija Seguridad 6.1. */
    private static final Duration VIGENCIA = Duration.ofDays(7);

    private final InvitationRepository invitaciones;
    private final UserAccountRepository cuentas;
    private final Memberships membresias;
    private final ProveedorDeIdentidad proveedor;
    private final Correo correo;
    private final ApplicationEventPublisher eventos;
    private final Clock reloj;
    private final String urlDeLaWeb;

    InvitationsService(
            InvitationRepository invitaciones,
            UserAccountRepository cuentas,
            Memberships membresias,
            ProveedorDeIdentidad proveedor,
            Correo correo,
            ApplicationEventPublisher eventos,
            Clock reloj,
            @Value("${coprop.web.url}") String urlDeLaWeb) {
        this.invitaciones = invitaciones;
        this.cuentas = cuentas;
        this.membresias = membresias;
        this.proveedor = proveedor;
        this.correo = correo;
        this.eventos = eventos;
        this.reloj = reloj;
        this.urlDeLaWeb = urlDeLaWeb;
    }

    @Override
    @Transactional
    public InvitationView issue(UUID condominiumId, UUID accountId, Set<MembershipRole> roles, String actor) {
        UserAccount cuenta =
                cuentas.findById(accountId).orElseThrow(() -> RecursoNoEncontrado.de("la cuenta", accountId));
        exigirRolesInvitables(roles);

        // Ya esta dentro: no hay a que invitarla. Sin esta comprobacion la invitacion se emitiria,
        // el correo saldria, y al aceptarla reventaria el alta de membresia con un error que no
        // explica nada a quien pulso el enlace.
        if (membresias.of(condominiumId, accountId) != null) {
            throw new ReglaDeNegocioViolada(
                    "La cuenta %s ya tiene membresia vigente en el condominio %s.".formatted(accountId, condominiumId));
        }
        if (invitacionViva(condominiumId, accountId) != null) {
            throw new ReglaDeNegocioViolada(
                    "La cuenta %s ya tiene una invitacion abierta en el condominio %s; para mandar otra, reenviala."
                            .formatted(accountId, condominiumId));
        }
        if (accountId.equals(actorComoCuenta(actor))) {
            throw new ReglaDeNegocioViolada("Nadie se invita a si mismo.");
        }

        return emitir(condominiumId, cuenta, roles, actor, false);
    }

    @Override
    @Transactional
    public InvitationView resend(UUID condominiumId, UUID invitationId, String actor) {
        Invitation anterior = exigirDelCondominio(condominiumId, invitationId);
        exigirQueNoEsteAceptada(anterior);

        Instant ahora = reloj.instant();
        anterior.revocar(ahora);
        publicarRevocacion(anterior, true, actor, ahora);

        // Hay que forzar el flush entre cerrar la anterior y crear la nueva. Hibernate ordena todos
        // los INSERT antes que los UPDATE dentro de un mismo flush, asi que sin esto el indice
        // parcial invitation_viva_uq ve dos filas abiertas y rechaza la nueva. Es la misma trampa
        // que la vigencia de la configuracion del condominio, en el issue #9.
        invitaciones.flush();

        UUID cuentaId = anterior.getAccountId();
        UserAccount cuenta =
                cuentas.findById(cuentaId).orElseThrow(() -> RecursoNoEncontrado.de("la cuenta", cuentaId));
        return emitir(condominiumId, cuenta, anterior.getRoles(), actor, true);
    }

    @Override
    @Transactional
    public InvitationView revoke(UUID condominiumId, UUID invitationId, String actor) {
        Invitation invitacion = exigirDelCondominio(condominiumId, invitationId);
        exigirQueNoEsteAceptada(invitacion);

        Instant ahora = reloj.instant();
        invitacion.revocar(ahora);
        publicarRevocacion(invitacion, false, actor, ahora);

        return vista(invitacion);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvitationView> ofCondominium(UUID condominiumId) {
        return invitaciones.findByCondominiumIdOrderByCreatedAtDesc(condominiumId).stream()
                .map(InvitationsService::vista)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public InvitationView find(UUID condominiumId, UUID invitationId) {
        return vista(exigirDelCondominio(condominiumId, invitationId));
    }

    @Override
    @Transactional
    public Apertura open(String token) {
        Invitation invitacion = exigirQueElTokenSirva(token);
        UUID cuentaId = invitacion.getAccountId();
        UserAccount cuenta =
                cuentas.findById(cuentaId).orElseThrow(() -> RecursoNoEncontrado.de("la cuenta", cuentaId));

        // Los dos casos de Seguridad 6.1. Que la cuenta tenga sujeto del proveedor significa que ya
        // entro alguna vez --participa en otro condominio-- y entonces solo le toca iniciar sesion.
        // Si no lo tiene, no puede ni eso: el realm tiene el registro cerrado, asi que hay que
        // crearle el usuario y pedirle al proveedor que le escriba para poner su contraseña.
        boolean yaPuedeEntrar = cuenta.getIdpSubject() != null;
        if (!yaPuedeEntrar) {
            proveedor.prepararCredencial(cuenta.getEmail());
        }

        return new Apertura(invitacion.getCondominiumId(), !yaPuedeEntrar);
    }

    @Override
    @Transactional
    public InvitationView accept(String token, UUID accountId) {
        Invitation invitacion = exigirQueElTokenSirva(token);

        // El enlace de otro responde lo mismo que un token inventado. Distinguirlos le diria a
        // quien tiene un enlace ajeno que ese enlace es bueno, solo que no para el.
        if (!invitacion.getAccountId().equals(accountId)) {
            log.info(
                    "La cuenta {} intento aceptar la invitacion {}, que es de otra cuenta; se responde 404.",
                    accountId,
                    invitacion.getId());
            throw RecursoNoEncontrado.de("la invitacion", "pedida");
        }

        Instant ahora = reloj.instant();
        UUID condominio = invitacion.getCondominiumId();

        // La membresia nace aqui, no al emitir. El actor es la propia cuenta invitada: aceptar es un
        // acto de quien entra. Y invitedBy es quien la emitio, que es lo que la bitacora necesita
        // para reconstruir por que esta persona esta dentro.
        MembershipView membresia = membresias.grant(
                condominio, accountId, invitacion.getRoles(), invitacion.getInvitedBy(), accountId.toString());

        invitacion.aceptar(ahora);

        eventos.publishEvent(new InvitationAccepted(
                condominio, invitacion.getId(), accountId, membresia.id(), accountId.toString(), ahora));

        log.info("La cuenta {} acepto la invitacion {} al condominio {}.", accountId, invitacion.getId(), condominio);
        return vista(invitacion);
    }

    /**
     * Crea la fila, manda el correo y publica el evento.
     *
     * <p>El correo va <strong>dentro de la transaccion</strong> y es el ultimo paso: si el servidor
     * de correo falla, la emision entera se deshace. Una invitacion que nadie recibio no sirve de
     * nada, y es mejor que el administrador vea el error que una fila viva con un enlace que no
     * llego. Queda una ventana --correo entregado y transaccion que no confirma-- y su arreglo es
     * un reenvio.
     */
    private InvitationView emitir(
            UUID condominiumId, UserAccount cuenta, Set<MembershipRole> roles, String actor, boolean reenviada) {
        Instant ahora = reloj.instant();
        String token = TokenDeInvitacion.nuevo();

        Invitation invitacion = invitaciones.save(new Invitation(
                condominiumId,
                cuenta.getId(),
                TokenDeInvitacion.hashDe(token),
                roles,
                actorComoCuentaObligatoria(actor),
                ahora,
                ahora.plus(VIGENCIA)));

        correo.enviar(cuenta.getEmail(), "Te invitaron a COPROP", cuerpoDelCorreo(token));

        eventos.publishEvent(new InvitationIssued(
                condominiumId,
                invitacion.getId(),
                cuenta.getId(),
                invitacion.getRoles(),
                invitacion.getExpiresAt(),
                reenviada,
                actor,
                ahora));

        log.info(
                "Invitacion {} emitida para la cuenta {} en el condominio {}; reenviada={}.",
                invitacion.getId(),
                cuenta.getId(),
                condominiumId,
                reenviada);
        return vista(invitacion);
    }

    /**
     * El cuerpo del correo, en texto plano.
     *
     * <p>El enlace apunta a la <strong>web</strong> y no a la API. La pagina es la que sabe que
     * mostrar en cada uno de los dos casos de 6.1 --"revisa tu correo para poner tu contraseña" o
     * "inicia sesion"-- y la que manda al login; poner aqui una ruta de la API obligaria al backend
     * a devolver redirecciones y a conocer las rutas de la SPA.
     *
     * <p>No dice de que condominio es ni quien invita. Es deliberado: este correo llega a una
     * direccion que el administrador escribio, y si se equivoco de letra, el nombre del edificio y
     * el de quien invita son datos de un condominio que acaban en un desconocido. Lo que hace falta
     * para decidir si pulsar es que se entienda de que va; el resto lo dice la pagina, ya detras del
     * token.
     */
    private String cuerpoDelCorreo(String token) {
        return """
                Te invitaron a COPROP, el sistema de administracion de tu condominio.

                Para entrar, abri este enlace:

                %s/invitaciones/%s

                El enlace sirve una sola vez y vence en 7 dias. Si no lo pediste, ignora este correo.
                """
                .formatted(urlDeLaWeb, token);
    }

    /**
     * La invitacion que corresponde a ese token, si todavia sirve.
     *
     * <p>Los cuatro motivos --no existe, ya se uso, se revoco, vencio-- responden <strong>lo
     * mismo</strong>. Es el primer criterio del issue, y distinguirlos le diria a quien prueba
     * tokens cuales existieron. El log si los distingue, porque lo lee quien opera.
     */
    private Invitation exigirQueElTokenSirva(String token) {
        Invitation invitacion = invitaciones
                .findByTokenHash(TokenDeInvitacion.hashDe(token))
                .orElseThrow(() -> {
                    log.info("Llego un token de invitacion que no existe; se responde 404.");
                    return RecursoNoEncontrado.de("la invitacion", "pedida");
                });
        if (!invitacion.estaViva(reloj.instant())) {
            log.info(
                    "La invitacion {} ya no sirve: aceptada={}, revocada={}, vence={}. Se responde 404.",
                    invitacion.getId(),
                    invitacion.getAcceptedAt(),
                    invitacion.getRevokedAt(),
                    invitacion.getExpiresAt());
            throw RecursoNoEncontrado.de("la invitacion", "pedida");
        }
        return invitacion;
    }

    private Invitation exigirDelCondominio(UUID condominiumId, UUID invitationId) {
        return invitaciones
                .findById(invitationId)
                .filter(invitacion -> invitacion.getCondominiumId().equals(condominiumId))
                .orElseThrow(() -> RecursoNoEncontrado.de("la invitacion", invitationId));
    }

    private static void exigirQueNoEsteAceptada(Invitation invitacion) {
        if (invitacion.getAcceptedAt() != null) {
            throw new ReglaDeNegocioViolada(
                    "La invitacion %s ya se acepto: la membresia existe y hay que darla de baja, no revocar la invitacion."
                            .formatted(invitacion.getId()));
        }
    }

    /**
     * Los roles que se pueden invitar.
     *
     * <p>Se comprueba aqui y no solo al conceder la membresia porque entre emitir y aceptar pasan
     * dias: una invitacion con un rol inadmisible se descubriria cuando la persona pulsa el enlace,
     * y el error le saldria a ella en vez de a quien la emitio.
     */
    private static void exigirRolesInvitables(Set<MembershipRole> roles) {
        if (roles.isEmpty()) {
            throw new ReglaDeNegocioViolada("Una invitacion necesita al menos un rol.");
        }
        if (roles.contains(MembershipRole.PLATFORM_ADMIN)) {
            throw new ReglaDeNegocioViolada(
                    "PLATFORM_ADMIN no se invita: su alcance es la plataforma, no un condominio.");
        }
    }

    private @org.jspecify.annotations.Nullable Invitation invitacionViva(UUID condominiumId, UUID accountId) {
        return invitaciones
                .findByCondominiumIdAndAccountIdAndAcceptedAtIsNullAndRevokedAtIsNull(condominiumId, accountId)
                .orElse(null);
    }

    /**
     * El actor como identificador de cuenta.
     *
     * <p>El actor llega como cadena porque es lo que acaba en la bitacora y no siempre es una cuenta
     * --en los tests y en el arranque es un nombre--. Aqui hace falta como identificador, porque
     * {@code invited_by} es una clave foranea.
     */
    private static @org.jspecify.annotations.Nullable UUID actorComoCuenta(String actor) {
        try {
            return UUID.fromString(actor);
        } catch (IllegalArgumentException noEsUnIdentificador) {
            return null;
        }
    }

    private static UUID actorComoCuentaObligatoria(String actor) {
        UUID cuenta = actorComoCuenta(actor);
        if (cuenta == null) {
            // Una invitacion siempre la emite alguien que entro al sistema, al contrario que la
            // primera membresia de un condominio. Si el actor no es una cuenta, quien llama se
            // salto la cadena de identidad y eso es un error de montaje, no una peticion rara.
            throw new IllegalStateException(
                    "El actor de una invitacion tiene que ser una cuenta y llego %s.".formatted(actor));
        }
        return cuenta;
    }

    private static InvitationView vista(Invitation invitacion) {
        return new InvitationView(
                invitacion.getId(),
                invitacion.getCondominiumId(),
                invitacion.getAccountId(),
                invitacion.getRoles(),
                invitacion.getExpiresAt(),
                invitacion.getCreatedAt(),
                invitacion.getAcceptedAt(),
                invitacion.getRevokedAt(),
                invitacion.getInvitedBy());
    }

    private void publicarRevocacion(Invitation invitacion, boolean porReenvio, String actor, Instant ahora) {
        eventos.publishEvent(new InvitationRevoked(
                invitacion.getCondominiumId(),
                invitacion.getId(),
                invitacion.getAccountId(),
                porReenvio,
                actor,
                ahora));
    }
}
