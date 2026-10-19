package bo.coprop.identity.internal;

import bo.coprop.identity.IdentityProvider;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * La Admin API de Keycloak, con el cliente {@code coprop-admin}.
 *
 * <p>Ese cliente existe desde el issue #12 y Seguridad 9 lo acota a {@code manage-users} y
 * {@code view-users}: no administra el realm. Es la unica salida de COPROP hacia Keycloak que
 * escribe; todo lo demas es Keycloak hablandole a COPROP.
 *
 * <p><strong>No hace ninguna llamada al arrancar</strong>, y es deliberado. El issue #13 ya pago
 * esa leccion con {@code issuer-uri} del cliente OAuth2: descargar el documento de descubrimiento
 * al arrancar dejaba la aplicacion sin levantar si Keycloak estaba caido, y ningun test podia
 * arrancar el contexto. Aqui el {@link RestClient} se arma en el constructor, que no sale a la red,
 * y los extremos se derivan del emisor.
 *
 * <p><strong>El token de administracion no se cachea.</strong> Dura un minuto, y esto se llama una
 * vez por enlace de invitacion abierto: cachearlo obligaria a manejar su expiracion para ahorrar
 * una peticion que nadie nota. El dia que haya operaciones masivas, se cachea entonces.
 */
@Component
class KeycloakIdentityProvider implements IdentityProvider {

    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityProvider.class);

    /**
     * Lo que la persona tiene que hacer antes de poder usar su cuenta.
     *
     * <p>Las dos, y en este orden. {@code UPDATE_PASSWORD} es lo que hace falta para entrar, porque
     * el usuario nace sin credencial. {@code VERIFY_EMAIL} no es ceremonia: el primer login del
     * issue #13 <strong>exige {@code email_verified}</strong> para vincular la cuenta de COPROP, asi
     * que sin esto la persona pondria su contraseña y aun asi recibiria {@code SIN_CUENTA}.
     */
    private static final List<String> ACCIONES = List.of("UPDATE_PASSWORD", "VERIFY_EMAIL");

    private final RestClient http;
    private final String issuer;
    private final String admin;
    private final String clienteId;
    private final String clienteSecreto;

    KeycloakIdentityProvider(
            @Value("${coprop.oidc.emisor}") String issuer,
            @Value("${coprop.oidc.admin-cliente-id}") String clienteId,
            @Value("${coprop.oidc.admin-cliente-secreto}") String clienteSecreto) {
        this.http = clienteHttp();
        this.issuer = issuer;
        this.admin = baseDeAdministracion(issuer);
        this.clienteId = clienteId;
        this.clienteSecreto = clienteSecreto;
    }

    /**
     * El cliente HTTP, armado aqui y <strong>con tiempos de espera explicitos</strong>.
     *
     * <p>No se inyecta un {@code RestClient.Builder}: en Boot 4 ese bean lo trae la
     * autoconfiguracion del cliente REST, que no esta en el classpath de este proyecto --el starter
     * de webmvc solo trae el lado servidor-- y el contexto no arranca. Se descubrio al correr los
     * tests.
     *
     * <p>Los dos tiempos no son cosmetica: sin ellos, un Keycloak que acepta la conexion y no
     * contesta deja colgada la peticion del navegador que abrio el enlace de la invitacion, y con
     * ella un hilo y una transaccion abierta. Diez segundos es de sobra para la Admin API y deja que
     * el fallo se cuente como lo que es.
     */
    private static RestClient clienteHttp() {
        var fabrica = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        fabrica.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(fabrica).build();
    }

    /**
     * De {@code .../realms/coprop} a {@code .../admin/realms/coprop}.
     *
     * <p>Se deriva del emisor en vez de pedir una propiedad aparte para que no haya dos valores que
     * puedan quedar apuntando a realms distintos, que es un fallo silencioso: las operaciones irian
     * a un realm y los tokens vendrian de otro.
     */
    private static String baseDeAdministracion(String issuer) {
        int realms = issuer.lastIndexOf("/realms/");
        if (realms < 0) {
            throw new IllegalArgumentException(
                    "El emisor %s no tiene la forma de un realm de Keycloak (.../realms/<nombre>).".formatted(issuer));
        }
        return issuer.substring(0, realms) + "/admin" + issuer.substring(realms);
    }

    @Override
    public void prepararCredencial(String email) {
        String token = tokenDeAdministracion();
        String usuario = find(email, token);
        if (usuario == null) {
            create(email, token);
            usuario = find(email, token);
            if (usuario == null) {
                throw new FalloDelProveedorDeIdentidad(
                        "Keycloak acepto crear el usuario pero newValues no lo encuentra; revisar el realm.");
            }
            log.info("Usuario creado en el proveedor de identidad para correo={}", email);
        }
        requestEmailActions(usuario, token);
    }

    /**
     * El cierre de sesiones del issue #19.
     *
     * <p>{@code POST /users/{id}/logout} termina todas las sesiones del usuario en el realm y con
     * ellas invalida sus refresh tokens, que es lo que hace que la app movil deje de renovar. No
     * hace falta recorrer sesion por sesion: Keycloak las cierra todas de una.
     *
     * <p>Si no hay usuario en Keycloak no hay nada que cerrar y se vuelve sin tocar nada. Es el caso
     * de una cuenta invitada que nunca abrio su enlace, y tratarlo como un fallo haria que una baja
     * perfectamente normal pareciera un error de integracion.
     */
    @Override
    public void closeSessions(String email) {
        String token = tokenDeAdministracion();
        String usuario = find(email, token);
        if (usuario == null) {
            log.info("No hay usuario en el proveedor de identidad para correo={}; no hay sesiones que cerrar.", email);
            return;
        }
        pedir("cerrar las sesiones del usuario", () -> http.post()
                .uri(admin + "/users/{id}/logout", usuario)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toBodilessEntity());
        log.info("Sessions cerradas en el proveedor de identidad para correo={}", email);
    }

    /**
     * Las sesiones del usuario en el realm. Issue <strong>#22</strong>.
     *
     * <p>{@code GET /users/{id}/sessions} las devuelve todas, con su IP y sus tiempos en
     * milisegundos. Lo que no devuelve es el dispositivo: Keycloak guarda el agente de usuario por
     * sesion solo si se habilitan sus propios eventos, que es otra decision.
     *
     * <p>Lista vacia cuando no hay usuario, que es lo que el puerto promete: una cuenta invitada
     * que nunca entro no tiene sesiones, y eso no es un fallo de integracion.
     */
    @Override
    public List<ProviderSession> sessionsOf(String email) {
        String token = tokenDeAdministracion();
        String usuario = find(email, token);
        if (usuario == null) {
            return List.of();
        }
        List<Map<String, Object>> sessions = pedir("listar las sesiones del usuario", () -> http.get()
                .uri(admin + "/users/{id}/sessions", usuario)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {}));
        if (sessions == null) {
            return List.of();
        }
        return sessions.stream()
                .map(KeycloakIdentityProvider::toSession)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Una fila de Keycloak a una sesion del puerto.
     *
     * <p>Devuelve nulo --y la descarta quien llama-- si le falta el identificador o los tiempos. No
     * revienta: una sesion que no se puede describir no es motivo para dejar al usuario sin ver las
     * demas, que es justo la pantalla desde la que cerraria una que no reconoce.
     */
    private static @org.jspecify.annotations.Nullable ProviderSession toSession(Map<String, Object> fila) {
        if (!(fila.get("id") instanceof String id)) {
            return null;
        }
        Instant created = instanteDe(fila.get("start"));
        Instant lastAccess = instanteDe(fila.get("lastAccess"));
        if (created == null || lastAccess == null) {
            return null;
        }
        String ip = fila.get("ipAddress") instanceof String valor && !valor.isBlank() ? valor : null;
        return new ProviderSession(id, ip, created, lastAccess);
    }

    /**
     * Los tiempos de Keycloak son milisegundos desde la epoca, y llegan como numero de JSON.
     *
     * <p>Se acepta cualquier {@link Number} y no solo {@code Long}: el mapeador elige el tipo segun
     * el tamaño del numero, asi que el mismo campo puede llegar como {@code Integer} en una sesion
     * y como {@code Long} en otra.
     */
    private static @org.jspecify.annotations.Nullable Instant instanteDe(
            @org.jspecify.annotations.Nullable Object valor) {
        return valor instanceof Number numero ? Instant.ofEpochMilli(numero.longValue()) : null;
    }

    private String tokenDeAdministracion() {
        var credenciales = new LinkedMultiValueMap<String, String>();
        credenciales.add("grant_type", "client_credentials");
        credenciales.add("client_id", clienteId);
        credenciales.add("client_secret", clienteSecreto);

        Map<String, Object> response = pedir("obtener el token de administracion", () -> http.post()
                .uri(issuer + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(credenciales)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {}));

        Object token = response == null ? null : response.get("access_token");
        if (!(token instanceof String valor) || valor.isBlank()) {
            throw new FalloDelProveedorDeIdentidad(
                    "Keycloak no devolvio access_token al pedirle el token de administracion.");
        }
        return valor;
    }

    /** El identificador del usuario con ese correo, o nulo si no hay ninguno. */
    private @org.jspecify.annotations.Nullable String find(String email, String token) {
        List<Map<String, Object>> encontrados = pedir("buscar el usuario", () -> http.get()
                // exact: sin esto Keycloak busca por coincidencia parcial y un correo que
                // sea prefijo de otro devolveria el ajeno primero.
                .uri(admin + "/users?exact=true&briefRepresentation=true&email={email}", email)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {}));
        if (encontrados == null || encontrados.isEmpty()) {
            return null;
        }
        Object id = encontrados.get(0).get("id");
        return id instanceof String valor ? valor : null;
    }

    private void create(String email, String token) {
        // El correo es tambien el nombre de usuario: el realm tiene loginWithEmailAllowed y
        // editUsernameAllowed en falso, asi que no hay dos nombres que puedan separarse.
        //
        // emailVerified en FALSO a proposito. Lo verifica la accion que Keycloak le envia, y el
        // primer login del #13 lo exige para vincular. Darlo por verificado aqui convertiria "nos
        // dijeron ese correo" en "ese correo es suyo", que es justo lo que no se puede asumir de un
        // dato que escribio el administrador.
        Map<String, Object> usuario = Map.of(
                "username", email,
                "email", email,
                "enabled", true,
                "emailVerified", false,
                "requiredActions", ACCIONES);

        pedir("crear el usuario", () -> http.post()
                .uri(admin + "/users")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(usuario)
                .retrieve()
                .toBodilessEntity());
    }

    /**
     * Le pide a Keycloak que le escriba a la persona para que ponga su contraseña.
     *
     * <p>Es lo que hace que este flujo funcione con el realm cerrado y sin que COPROP vea la
     * contraseña: el correo con el que se pone una credencial solo puede salir de quien la guarda.
     *
     * <p>Sin {@code client_id} ni {@code redirect_uri}: pasarlos exigiria que esa direccion este
     * registrada en el cliente, y {@code coprop-web} solo admite la de su propio intercambio. Sin
     * ellos, Keycloak deja a la persona en su consola de cuenta, desde donde ya puede iniciar sesion
     * en COPROP.
     *
     * <p>Se llama tambien cuando el usuario ya existia, y por eso el puerto promete ser idempotente:
     * abrir el enlace dos veces vuelve a mandar el correo en vez de reventar.
     */
    private void requestEmailActions(String usuario, String token) {
        pedir("pedir las acciones por correo", () -> http.put()
                .uri(admin + "/users/{id}/execute-actions-email", usuario)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ACCIONES)
                .retrieve()
                .toBodilessEntity());
    }

    /**
     * Envuelve cada llamada para que un fallo diga que se estaba haciendo.
     *
     * <p>Sin esto, lo que llega al log es un {@code 401 Unauthorized} sin contexto y hay que
     * adivinar si fallo el token, la busqueda o el alta. El mensaje del proveedor no se arrastra al
     * cliente: lo que sale por HTTP es el error de dominio de quien llama.
     */
    private static <T> T pedir(String queSeHacia, java.util.function.Supplier<T> llamada) {
        try {
            return llamada.get();
        } catch (RestClientException fallo) {
            throw new FalloDelProveedorDeIdentidad("Keycloak fallo al %s.".formatted(queSeHacia), fallo);
        }
    }
}
