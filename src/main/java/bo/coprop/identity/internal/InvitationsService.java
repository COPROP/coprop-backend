package bo.coprop.identity.internal;

import bo.coprop.identity.IdentityProvider;
import bo.coprop.identity.InvitationAccepted;
import bo.coprop.identity.InvitationIssued;
import bo.coprop.identity.InvitationRevoked;
import bo.coprop.identity.InvitationView;
import bo.coprop.identity.Invitations;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.MembershipView;
import bo.coprop.identity.Memberships;
import bo.coprop.notifications.Mail;
import bo.coprop.shared.BusinessRuleViolated;
import bo.coprop.shared.ResourceNotFound;
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

    private final InvitationRepository invitations;
    private final UserAccountRepository accounts;
    private final Memberships memberships;
    private final IdentityProvider proveedor;
    private final Mail email;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;
    private final String urlDeLaWeb;

    InvitationsService(
            InvitationRepository invitations,
            UserAccountRepository accounts,
            Memberships memberships,
            IdentityProvider proveedor,
            Mail email,
            ApplicationEventPublisher eventos,
            Clock clock,
            @Value("${coprop.web.url}") String urlDeLaWeb) {
        this.invitations = invitations;
        this.accounts = accounts;
        this.memberships = memberships;
        this.proveedor = proveedor;
        this.email = email;
        this.eventos = eventos;
        this.clock = clock;
        this.urlDeLaWeb = urlDeLaWeb;
    }

    @Override
    @Transactional
    public InvitationView issue(UUID condominiumId, UUID accountId, Set<MembershipRole> roles, String actor) {
        UserAccount account =
                accounts.findById(accountId).orElseThrow(() -> ResourceNotFound.de("la cuenta", accountId));
        requireInvitableRoles(roles);

        // Ya esta dentro: no hay a que invitarla. Sin esta comprobacion la invitacion se emitiria,
        // el correo saldria, y al aceptarla reventaria el alta de membresia con un error que no
        // explica nada a quien pulso el enlace.
        if (memberships.of(condominiumId, accountId) != null) {
            throw new BusinessRuleViolated(
                    "La cuenta %s ya tiene membresia vigente en el condominio %s.".formatted(accountId, condominiumId));
        }
        if (liveInvitation(condominiumId, accountId) != null) {
            throw new BusinessRuleViolated(
                    "La cuenta %s ya tiene una invitacion abierta en el condominio %s; para mandar otra, reenviala."
                            .formatted(accountId, condominiumId));
        }
        if (accountId.equals(actorAsAccount(actor))) {
            throw new BusinessRuleViolated("Nadie se invita a si mismo.");
        }

        return issue(condominiumId, account, roles, actor, false);
    }

    @Override
    @Transactional
    public InvitationView resend(UUID condominiumId, UUID invitationId, String actor) {
        Invitation anterior = requireOfCondominium(condominiumId, invitationId);
        requireNotAccepted(anterior);

        Instant ahora = clock.instant();
        anterior.revoke(ahora);
        publicarRevocacion(anterior, true, actor, ahora);

        // Hay que forzar el flush entre cerrar la anterior y crear la nueva. Hibernate ordena todos
        // los INSERT antes que los UPDATE dentro de un mismo flush, asi que sin esto el indice
        // parcial invitation_viva_uq ve dos filas abiertas y rechaza la nueva. Es la misma trampa
        // que la vigencia de la configuracion del condominio, en el issue #9.
        invitations.flush();

        UUID cuentaId = anterior.getAccountId();
        UserAccount account = accounts.findById(cuentaId).orElseThrow(() -> ResourceNotFound.de("la cuenta", cuentaId));
        return issue(condominiumId, account, anterior.getRoles(), actor, true);
    }

    @Override
    @Transactional
    public InvitationView revoke(UUID condominiumId, UUID invitationId, String actor) {
        Invitation invitation = requireOfCondominium(condominiumId, invitationId);
        requireNotAccepted(invitation);

        Instant ahora = clock.instant();
        invitation.revoke(ahora);
        publicarRevocacion(invitation, false, actor, ahora);

        return view(invitation);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvitationView> ofCondominium(UUID condominiumId) {
        return invitations.findByCondominiumIdOrderByCreatedAtDesc(condominiumId).stream()
                .map(InvitationsService::view)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public InvitationView find(UUID condominiumId, UUID invitationId) {
        return view(requireOfCondominium(condominiumId, invitationId));
    }

    @Override
    @Transactional
    public Opening open(String token) {
        Invitation invitation = requireUsableToken(token);
        UUID cuentaId = invitation.getAccountId();
        UserAccount account = accounts.findById(cuentaId).orElseThrow(() -> ResourceNotFound.de("la cuenta", cuentaId));

        // Los dos casos de Seguridad 6.1. Que la cuenta tenga sujeto del proveedor significa que ya
        // entro alguna vez --participa en otro condominio-- y entonces solo le toca iniciar sesion.
        // Si no lo tiene, no puede ni eso: el realm tiene el registro cerrado, asi que hay que
        // crearle el usuario y pedirle al proveedor que le escriba para poner su contraseña.
        boolean canAlreadySignIn = account.getIdpSubject() != null;
        if (!canAlreadySignIn) {
            proveedor.prepararCredencial(account.getEmail());
        }

        return new Opening(invitation.getCondominiumId(), !canAlreadySignIn);
    }

    @Override
    @Transactional
    public InvitationView accept(String token, UUID accountId) {
        Invitation invitation = requireUsableToken(token);

        // El enlace de otro responde lo mismo que un token inventado. Distinguirlos le diria a
        // quien tiene un enlace ajeno que ese enlace es bueno, solo que no para el.
        if (!invitation.getAccountId().equals(accountId)) {
            log.info(
                    "La cuenta {} intento aceptar la invitacion {}, que es de otra cuenta; se responde 404.",
                    accountId,
                    invitation.getId());
            throw ResourceNotFound.de("la invitacion", "pedida");
        }

        Instant ahora = clock.instant();
        UUID condominium = invitation.getCondominiumId();

        // La membresia nace aqui, no al emitir. El actor es la propia cuenta invitada: aceptar es un
        // acto de quien entra. Y invitedBy es quien la emitio, que es lo que la bitacora necesita
        // para reconstruir por que esta persona esta dentro.
        MembershipView membership = memberships.grant(
                condominium, accountId, invitation.getRoles(), invitation.getInvitedBy(), accountId.toString());

        invitation.accept(ahora);

        eventos.publishEvent(new InvitationAccepted(
                condominium, invitation.getId(), accountId, membership.id(), accountId.toString(), ahora));

        log.info("La cuenta {} acepto la invitacion {} al condominio {}.", accountId, invitation.getId(), condominium);
        return view(invitation);
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
    private InvitationView issue(
            UUID condominiumId, UserAccount account, Set<MembershipRole> roles, String actor, boolean reenviada) {
        Instant ahora = clock.instant();
        String token = InvitationToken.body();

        Invitation invitation = invitations.save(new Invitation(
                condominiumId,
                account.getId(),
                InvitationToken.hashDe(token),
                roles,
                requiredActorAsAccount(actor),
                ahora,
                ahora.plus(VIGENCIA)));

        email.enviar(account.getEmail(), "Te invitaron a COPROP", emailBody(token));

        eventos.publishEvent(new InvitationIssued(
                condominiumId,
                invitation.getId(),
                account.getId(),
                invitation.getRoles(),
                invitation.getExpiresAt(),
                reenviada,
                actor,
                ahora));

        log.info(
                "Invitacion {} emitida para la cuenta {} en el condominio {}; reenviada={}.",
                invitation.getId(),
                account.getId(),
                condominiumId,
                reenviada);
        return view(invitation);
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
    private String emailBody(String token) {
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
    private Invitation requireUsableToken(String token) {
        Invitation invitation = invitations
                .findByTokenHash(InvitationToken.hashDe(token))
                .orElseThrow(() -> {
                    log.info("Llego un token de invitacion que no existe; se responde 404.");
                    return ResourceNotFound.de("la invitacion", "pedida");
                });
        if (!invitation.isLive(clock.instant())) {
            log.info(
                    "La invitacion {} ya no sirve: aceptada={}, revocada={}, vence={}. Se responde 404.",
                    invitation.getId(),
                    invitation.getAcceptedAt(),
                    invitation.getRevokedAt(),
                    invitation.getExpiresAt());
            throw ResourceNotFound.de("la invitacion", "pedida");
        }
        return invitation;
    }

    private Invitation requireOfCondominium(UUID condominiumId, UUID invitationId) {
        return invitations
                .findById(invitationId)
                .filter(invitation -> invitation.getCondominiumId().equals(condominiumId))
                .orElseThrow(() -> ResourceNotFound.de("la invitacion", invitationId));
    }

    private static void requireNotAccepted(Invitation invitation) {
        if (invitation.getAcceptedAt() != null) {
            throw new BusinessRuleViolated(
                    "La invitacion %s ya se acepto: la membresia existe y hay que darla de baja, no revocar la invitacion."
                            .formatted(invitation.getId()));
        }
    }

    /**
     * Los roles que se pueden invitar.
     *
     * <p>Se comprueba aqui y no solo al conceder la membresia porque entre emitir y aceptar pasan
     * dias: una invitacion con un rol inadmisible se descubriria cuando la persona pulsa el enlace,
     * y el error le saldria a ella en vez de a quien la emitio.
     */
    private static void requireInvitableRoles(Set<MembershipRole> roles) {
        if (roles.isEmpty()) {
            throw new BusinessRuleViolated("Una invitacion necesita al menos un rol.");
        }
        if (roles.contains(MembershipRole.PLATFORM_ADMIN)) {
            throw new BusinessRuleViolated(
                    "PLATFORM_ADMIN no se invita: su alcance es la plataforma, no un condominio.");
        }
    }

    private @org.jspecify.annotations.Nullable Invitation liveInvitation(UUID condominiumId, UUID accountId) {
        return invitations
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
    private static @org.jspecify.annotations.Nullable UUID actorAsAccount(String actor) {
        try {
            return UUID.fromString(actor);
        } catch (IllegalArgumentException noEsUnIdentificador) {
            return null;
        }
    }

    private static UUID requiredActorAsAccount(String actor) {
        UUID account = actorAsAccount(actor);
        if (account == null) {
            // Una invitacion siempre la emite alguien que entro al sistema, al contrario que la
            // primera membresia de un condominio. Si el actor no es una cuenta, quien llama se
            // salto la cadena de identidad y eso es un error de montaje, no una peticion rara.
            throw new IllegalStateException(
                    "El actor de una invitacion tiene que ser una cuenta y llego %s.".formatted(actor));
        }
        return account;
    }

    private static InvitationView view(Invitation invitation) {
        return new InvitationView(
                invitation.getId(),
                invitation.getCondominiumId(),
                invitation.getAccountId(),
                invitation.getRoles(),
                invitation.getExpiresAt(),
                invitation.getCreatedAt(),
                invitation.getAcceptedAt(),
                invitation.getRevokedAt(),
                invitation.getInvitedBy());
    }

    private void publicarRevocacion(Invitation invitation, boolean porReenvio, String actor, Instant ahora) {
        eventos.publishEvent(new InvitationRevoked(
                invitation.getCondominiumId(),
                invitation.getId(),
                invitation.getAccountId(),
                porReenvio,
                actor,
                ahora));
    }
}
