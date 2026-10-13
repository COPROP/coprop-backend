package bo.coprop.api;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Tokens firmados de verdad para los tests que llegan por HTTP.
 *
 * <p>Se firman con una clave generada aqui en vez de usar el {@code jwt()} de spring-security-test,
 * que salta el decodificador: con el no se podria comprobar que un token expirado, mal firmado o
 * con otra audiencia se rechaza. Lo unico fingido es de donde sale la clave publica; los
 * validadores --emisor, expiracion y audiencia-- son los mismos que corren en produccion.
 *
 * <p>Cada token lleva <strong>su propio sujeto</strong>: estos tests no van en una transaccion
 * --los filtros escriben por su cuenta y un rollback del test no los alcanza-- asi que comparten
 * base, y reutilizar el sujeto hace que un caso entre con el vinculo que dejo otro.
 */
@TestConfiguration(proxyBeanMethods = false)
class ProveedorDeTokensDePrueba {

    static final String EMISOR = "http://localhost:8081/realms/coprop";
    static final String AUDIENCIA = "coprop-api";

    static final RSAKey CLAVE = generar();

    private static RSAKey generar() {
        try {
            return new RSAKeyGenerator(2048).keyID("prueba").generate();
        } catch (Exception fallo) {
            throw new IllegalStateException("No se pudo generar la clave de prueba", fallo);
        }
    }

    static String sujetoNuevo() {
        return UUID.randomUUID().toString();
    }

    static String correoNuevo() {
        return "emma-" + UUID.randomUUID() + "@coprop.bo";
    }

    /** Un token valido, con el correo verificado y cinco minutos por delante. */
    static String valido(String sujeto, String correo) {
        return token(sujeto, correo, true, Instant.now().plus(5, ChronoUnit.MINUTES), AUDIENCIA, CLAVE);
    }

    static String token(
            String sujeto, String correo, boolean correoVerificado, Instant expira, String audiencia, RSAKey clave) {
        try {
            var afirmaciones = new JWTClaimsSet.Builder()
                    .subject(sujeto)
                    .issuer(EMISOR)
                    .audience(audiencia)
                    .issueTime(Date.from(expira.minus(5, ChronoUnit.MINUTES)))
                    .expirationTime(Date.from(expira))
                    .claim("email", correo)
                    .claim("email_verified", correoVerificado)
                    .build();
            var firmado = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .type(JOSEObjectType.JWT)
                            .keyID(clave.getKeyID())
                            .build(),
                    afirmaciones);
            firmado.sign(new RSASSASigner(clave.toPrivateKey()));
            return firmado.serialize();
        } catch (Exception fallo) {
            throw new IllegalStateException("No se pudo firmar el token de prueba", fallo);
        }
    }

    /** Sustituye al decodificador de produccion, que descargaria el JWKS de Keycloak. */
    @Bean
    @Primary
    JwtDecoder decodificadorDePrueba() throws Exception {
        var decodificador =
                NimbusJwtDecoder.withPublicKey(CLAVE.toRSAPublicKey()).build();
        decodificador.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(EMISOR),
                new JwtClaimValidator<List<String>>(
                        JwtClaimNames.AUD, audiencias -> audiencias != null && audiencias.contains(AUDIENCIA))));
        return decodificador;
    }
}
