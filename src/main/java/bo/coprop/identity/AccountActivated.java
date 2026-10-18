package bo.coprop.identity;

import bo.coprop.shared.EventoAuditable;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Alguien entro por primera vez con una cuenta: queda vinculada al proveedor de identidad y pasa
 * de {@link AccountStatus#INVITED} a {@link AccountStatus#ACTIVE}.
 *
 * <p>Ocurre una sola vez por cuenta. Los accesos siguientes no publican nada: lo que interesa de
 * ellos es la fecha del ultimo, que se guarda en la propia cuenta, no un evento por request.
 *
 * <p>El actor es el sujeto que entra, asi que no viaja aparte: es {@code idpSubject}.
 */
public record AccountActivated(UUID accountId, UUID personId, String idpSubject, Instant occurredAt)
        implements EventoAuditable {

    @Override
    public String action() {
        return "ACCOUNT_ACTIVATED";
    }

    /** Nulo: la cuenta es global (Seguridad 2) y activarla no pasa en ningun condominio. */
    @Override
    public @Nullable UUID condominiumId() {
        return null;
    }

    /** El actor es quien entra: su propia cuenta. El sujeto del proveedor no sale de aqui. */
    @Override
    public String actor() {
        return accountId.toString();
    }

    @Override
    public String entity() {
        return "cuenta";
    }

    @Override
    public UUID entityId() {
        return accountId;
    }

    /**
     * El sujeto del proveedor <strong>no</strong> se guarda en la bitacora.
     *
     * <p>Es el puente interno hacia Keycloak, igual que no sale en {@code AccountView}, y para
     * reconstruir que paso no aporta nada que no diga la cuenta. Lo que se guarda es que esa cuenta
     * quedo activa, que es el hecho.
     */
    @Override
    public java.util.Map<String, String> newValues() {
        return java.util.Map.of("status", AccountStatus.ACTIVE.name());
    }
}
