package bo.coprop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * El realm que el Compose importa, comprobado como archivo.
 *
 * <p>Esto no levanta Keycloak: arrancarlo en CI costaria mas que todo el resto del build junto, y
 * lo que puede romperse aqui no es Keycloak sino el archivo. Lo que se verifica contra Keycloak de
 * verdad --que el import no falla, que el login entra, que el bloqueo muerde-- queda escrito en el
 * PR del issue #12, porque se corre a mano con {@code docker compose up}.
 *
 * <p>Lo que estos tests sostienen son los criterios del #12 y una cosa que el repositorio no puede
 * permitirse: los tres repos son publicos, asi que ningun secreto puede entrar literal. Por eso
 * todo secreto del realm tiene que ser un {@code ${VARIABLE:defecto}}, que el import sustituye con
 * el entorno del contenedor.
 */
class KeycloakRealmTests {

    private static final Path ARCHIVO = Path.of("docker", "keycloak", "coprop-realm.json");

    private static final JsonNode REALM = leer();

    private static JsonNode leer() {
        try {
            return new ObjectMapper().readTree(Files.readString(ARCHIVO, StandardCharsets.UTF_8));
        } catch (IOException fallo) {
            throw new IllegalStateException("No se pudo leer " + ARCHIVO, fallo);
        }
    }

    private static JsonNode cliente(String clientId) {
        for (JsonNode candidato : REALM.get("clients")) {
            if (clientId.equals(candidato.get("clientId").asString())) {
                return candidato;
            }
        }
        throw new AssertionError("El realm no declara el cliente " + clientId);
    }

    private static List<String> textos(JsonNode nodo, String field) {
        var valores = new ArrayList<String>();
        for (JsonNode valor : nodo.get(field)) {
            valores.add(valor.asString());
        }
        return valores;
    }

    @Test
    void theFileIsTheCopropRealm() {
        assertThat(REALM.get("realm").asString()).isEqualTo("coprop");
        assertThat(REALM.get("enabled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("la web va por el BFF: cliente confidencial y la vuelta al backend, no al navegador")
    void theWebClientIsConfidential() {
        var web = cliente("coprop-web");

        assertThat(web.get("publicClient").asBoolean()).isFalse();
        assertThat(web.get("standardFlowEnabled").asBoolean()).isTrue();
        assertThat(web.get("directAccessGrantsEnabled").asBoolean()).isFalse();
        assertThat(web.get("attributes").get("pkce.code.challenge.method").asString())
                .isEqualTo("S256");
        // Seguridad 5.1: el code vuelve al backend, que guarda los tokens en la sesion del
        // servidor. Si esto apuntara al 4200, el token acabaria en el navegador.
        assertThat(textos(web, "redirectUris"))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("/login/oauth2/code/coprop-web");
        assertThat(textos(web, "webOrigins")).isEmpty();
    }

    @Test
    @DisplayName("el unico cliente publico es el movil, que es el unico que guarda tokens")
    void theMobileClientIsPublicWithPkce() {
        var movil = cliente("coprop-movil");

        assertThat(movil.get("publicClient").asBoolean()).isTrue();
        assertThat(movil.get("attributes").get("pkce.code.challenge.method").asString())
                .isEqualTo("S256");
        assertThat(movil.get("directAccessGrantsEnabled").asBoolean()).isFalse();

        var publicos = new ArrayList<String>();
        for (JsonNode candidato : REALM.get("clients")) {
            if (candidato.path("publicClient").asBoolean(false)) {
                publicos.add(candidato.get("clientId").asString());
            }
        }
        assertThat(publicos).containsExactly("coprop-movil");
    }

    @Test
    void theApiIsBearerOnlyAndTheAdminClientIsServiceAccountOnly() {
        var api = cliente("coprop-api");
        assertThat(api.get("bearerOnly").asBoolean()).isTrue();
        assertThat(api.get("standardFlowEnabled").asBoolean()).isFalse();

        var admin = cliente("coprop-admin");
        assertThat(admin.get("serviceAccountsEnabled").asBoolean()).isTrue();
        assertThat(admin.get("standardFlowEnabled").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("el token no lleva roles: ni los del realm ni los de condominio")
    void noUserClientRequestsTheRolesScope() {
        for (String clientId : List.of("coprop-web", "coprop-movil")) {
            var cliente = cliente(clientId);

            // Los roles por condominio viven en la base de COPROP (Seguridad 4.2). Estas dos
            // lineas son lo que impide que Keycloak meta roles suyos en el token: sin el scope
            // "roles" no hay mapeador que los escriba, y sin fullScopeAllowed no hay nada que
            // mapear.
            assertThat(textos(cliente, "defaultClientScopes"))
                    .as("scopes de %s", clientId)
                    .doesNotContain("roles");
            assertThat(cliente.get("fullScopeAllowed").asBoolean())
                    .as("fullScopeAllowed de %s", clientId)
                    .isFalse();
        }
    }

    @Test
    void userTokensCarryTheApiAudience() {
        for (String clientId : List.of("coprop-web", "coprop-movil")) {
            var audiencias = new ArrayList<String>();
            for (JsonNode mapeador : cliente(clientId).get("protocolMappers")) {
                if ("oidc-audience-mapper".equals(mapeador.get("protocolMapper").asString())) {
                    audiencias.add(mapeador.get("config")
                            .get("included.client.audience")
                            .asString());
                }
            }
            assertThat(audiencias).as("audiencias de %s", clientId).containsExactly("coprop-api");
        }
    }

    @Test
    @DisplayName("Google autentica y vincula por correo, pero no reparte acceso")
    void theGoogleProviderIsDeclared() {
        var google = REALM.get("identityProviders").get(0);

        assertThat(google.get("alias").asString()).isEqualTo("google");
        assertThat(google.get("providerId").asString()).isEqualTo("google");
        // Con trustEmail y el flujo de vinculacion, quien entra por Google cae en la cuenta que
        // ya tiene ese correo en vez de abrir una segunda.
        assertThat(google.get("trustEmail").asBoolean()).isTrue();
        assertThat(google.get("firstBrokerLoginFlowAlias").asString()).isEqualTo("vincular por correo verificado");

        var alias = new ArrayList<String>();
        for (JsonNode flujo : REALM.get("authenticationFlows")) {
            alias.add(flujo.get("alias").asString());
        }
        assertThat(alias).contains("vincular por correo verificado", "vincular la cuenta existente");
    }

    @Test
    @DisplayName("ningun secreto literal: los tres repos son publicos")
    void everySecretArrivesByEnvironmentVariable() {
        var literales = new ArrayList<String>();
        recogerSecretosLiterales(REALM, "", literales);

        assertThat(literales).isEmpty();
    }

    private static void recogerSecretosLiterales(JsonNode nodo, String ruta, List<String> literales) {
        if (nodo.isObject()) {
            for (var entrada : nodo.properties()) {
                var key = entrada.getKey();
                var hijo = entrada.getValue();
                var rutaHija = ruta.isEmpty() ? key : ruta + "." + key;
                var esSecreto = key.equals("secret")
                        || key.toLowerCase(java.util.Locale.ROOT).endsWith("secret");
                if (esSecreto && hijo.isString() && !hijo.asString().startsWith("${")) {
                    literales.add(rutaHija + " = " + hijo.asString());
                }
                recogerSecretosLiterales(hijo, rutaHija, literales);
            }
        } else if (nodo.isArray()) {
            var indice = 0;
            for (JsonNode hijo : nodo) {
                recogerSecretosLiterales(hijo, ruta + "[" + indice++ + "]", literales);
            }
        }
    }

    @Test
    void thePasswordPolicyAndBruteForceLockoutAreSet() {
        assertThat(REALM.get("passwordPolicy").asString())
                .contains("length(10)")
                .contains("notUsername")
                .contains("notEmail");

        assertThat(REALM.get("bruteForceProtected").asBoolean()).isTrue();
        assertThat(REALM.get("failureFactor").asInt()).isEqualTo(5);
        // Bloqueo temporal, no permanente: si no, cualquiera deja fuera al vecino a proposito.
        assertThat(REALM.get("permanentLockout").asBoolean()).isFalse();
    }

    @Test
    void nobodyRegistersThemselves() {
        // El acceso lo da la invitacion (Seguridad 6.1 y 10.1), no un formulario de alta.
        assertThat(REALM.get("registrationAllowed").asBoolean()).isFalse();
        assertThat(REALM.get("resetPasswordAllowed").asBoolean()).isTrue();
        assertThat(REALM.get("duplicateEmailsAllowed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("el usuario de desarrollo lleva el rol por defecto del realm")
    void theDevelopmentUserCarriesTheDefaultRole() {
        var rolesDelDev = new ArrayList<String>();
        for (JsonNode usuario : REALM.get("users")) {
            if ("dev@coprop.local".equals(usuario.path("username").asString(""))) {
                rolesDelDev.addAll(textos(usuario, "realmRoles"));
            }
        }

        // A un usuario que entra por el archivo de import, Keycloak NO le asigna el rol por
        // defecto: solo lo hace cuando la cuenta se crea por la Admin API o por el formulario de
        // alta. Sin el, la consola de cuenta responde "Algo salio mal" y el usuario no puede ni
        // configurar su segundo factor.
        assertThat(rolesDelDev).contains("default-roles-coprop");
    }
}
