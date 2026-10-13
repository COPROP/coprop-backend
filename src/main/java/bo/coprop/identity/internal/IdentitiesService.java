package bo.coprop.identity.internal;

import bo.coprop.identity.AccountActivated;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.Identities;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
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
    private final ApplicationEventPublisher eventos;
    private final Clock reloj;

    IdentitiesService(UserAccountRepository cuentas, ApplicationEventPublisher eventos, Clock reloj) {
        this.cuentas = cuentas;
        this.eventos = eventos;
        this.reloj = reloj;
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
        // la respete, el agujero no se abre solo. Rehacer el vinculo sera una operacion explicita
        // del issue #19.
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
}
