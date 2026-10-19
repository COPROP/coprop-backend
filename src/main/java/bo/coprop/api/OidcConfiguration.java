package bo.coprop.api;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

/**
 * El registro del cliente confidencial de la web, armado a mano.
 *
 * <p><strong>Por que no se deja en configuracion.</strong> Con
 * {@code spring.security.oauth2.client.provider.*.issuer-uri}, Spring Boot resuelve el registro con
 * {@code ClientRegistrations.fromIssuerLocation}, que <strong>descarga el documento de
 * descubrimiento al arrancar</strong>. Dos consecuencias, y ninguna aceptable: la aplicacion no
 * levanta si Keycloak esta caido --un reinicio durante una caida del proveedor dejaria tambien sin
 * servicio a la app movil, que solo necesita las claves y las pide cuando le hacen falta--, y todo
 * test que arranque el contexto necesitaria un Keycloak en pie. Escribir los extremos a mano en el
 * YAML <strong>no basta</strong>: con {@code issuer-uri} presente, Boot descubre igual y los
 * ignora. Costo una corrida de CI en rojo descubrirlo, porque en local Keycloak estaba arriba y la
 * descarga funcionaba.
 *
 * <p>Armarlo aqui da las dos cosas: ninguna llamada de red al arrancar y el emisor declarado, que
 * es lo que Spring compara contra el {@code iss} del id token. Si se quitara el emisor para evitar
 * la descarga, esa comprobacion se perderia en silencio.
 *
 * <p>Los extremos son los de Keycloak y se derivan del emisor. Si algun dia el proveedor deja de
 * ser Keycloak, es el unico sitio que hay que tocar.
 */
@Configuration(proxyBeanMethods = false)
class OidcConfiguration {

    private final String issuer;
    private final String clienteId;
    private final String clienteSecreto;

    OidcConfiguration(
            @Value("${coprop.oidc.emisor}") String issuer,
            @Value("${coprop.oidc.cliente-id}") String clienteId,
            @Value("${coprop.oidc.cliente-secreto}") String clienteSecreto) {
        this.issuer = issuer;
        this.clienteId = clienteId;
        this.clienteSecreto = clienteSecreto;
    }

    @Bean
    ClientRegistrationRepository registrosDeCliente() {
        return new InMemoryClientRegistrationRepository(List.of(clienteWeb()));
    }

    private ClientRegistration clienteWeb() {
        return ClientRegistration.withRegistrationId(SecurityConfiguration.REGISTRO_WEB)
                .clientId(clienteId)
                .clientSecret(clienteSecreto)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                // La ruta la fija Spring Security y el realm solo admite esa; estan atadas por el
                // identificador del registro.
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                // Sin "profile": el nombre no sale del token, sale de la persona. De Keycloak solo
                // hace falta a quien identifica y con que correo.
                .scope("openid", "email")
                .authorizationUri(issuer + "/protocol/openid-connect/auth")
                .tokenUri(issuer + "/protocol/openid-connect/token")
                .jwkSetUri(issuer + "/protocol/openid-connect/certs")
                .userInfoUri(issuer + "/protocol/openid-connect/userinfo")
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .issuerUri(issuer)
                .clientName("COPROP web")
                .build();
    }
}
