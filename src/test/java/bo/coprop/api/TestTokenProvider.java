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
import org.jspecify.annotations.Nullable;
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
class TestTokenProvider {

    static final String EMISOR = "http://localhost:8081/realms/coprop";
    static final String AUDIENCIA = "coprop-api";

    static final RSAKey CLAVE = generar();

    private static RSAKey generar() {
        try {
            return new RSAKeyGenerator(2048).keyID("prueba").generate();
        } catch (Exception failure) {
            throw new IllegalStateException("No se pudo generar la clave de prueba", failure);
        }
    }

    static String sujetoNuevo() {
        return UUID.randomUUID().toString();
    }

    static String correoNuevo() {
        return "emma-" + UUID.randomUUID() + "@coprop.bo";
    }

    /**
     * Un token valido, con el correo verificado y cinco minutos por delante.
     *
     * <p><strong>Lleva segundo factor recien puesto</strong> desde el issue #22. Es lo que hace que
     * las suites que prueban <em>autorizacion</em> --la rejilla de endpoints por rol, la de fuga
     * entre condominios-- sigan probando eso y no la reautenticacion: sin {@code amr} y
     * {@code auth_time}, los endpoints sensibles responderian 401 a todo el mundo y las celdas
     * pasarian sin comprobar nada. Lo que no trae segundo factor se pide a mano, con
     * {@link #sinSegundoFactor} y {@link #conSegundoFactorEn}.
     */
    static String valid(String tokenSubject, String email) {
        return token(tokenSubject, email, true, Instant.now().plus(5, ChronoUnit.MINUTES), AUDIENCIA, CLAVE);
    }

    /** Como {@link #valid}, pero autenticado solo con contraseña: no hay segundo factor. */
    static String sinSegundoFactor(String tokenSubject, String email) {
        return token(
                tokenSubject,
                email,
                true,
                Instant.now().plus(5, ChronoUnit.MINUTES),
                AUDIENCIA,
                CLAVE,
                List.of("pwd"),
                Instant.now());
    }

    /**
     * Con segundo factor, pero puesto en el momento que se le diga.
     *
     * <p><strong>El instante lo pasa quien llama, y tiene que salir del reloj de la aplicacion</strong>,
     * no de {@code Instant.now()}. La comprobacion de la ventana de cinco minutos usa el
     * {@code Clock} inyectado, y en los tests ese reloj esta parado en una fecha fija: un
     * {@code auth_time} sacado de la hora real cae en el futuro para ese reloj, la resta da
     * negativo y el token "viejo" pasa como recien hecho. Costo un test en verde que no probaba
     * nada.
     */
    static String conSegundoFactorEn(String tokenSubject, String email, Instant autenticadoEn) {
        return token(
                tokenSubject,
                email,
                true,
                Instant.now().plus(5, ChronoUnit.MINUTES),
                AUDIENCIA,
                CLAVE,
                List.of("pwd", "otp"),
                autenticadoEn);
    }

    /** Sin {@code amr} ni {@code auth_time}: un proveedor que no los manda. */
    static String withoutAuthenticationClaims(String tokenSubject, String email) {
        return token(
                tokenSubject, email, true, Instant.now().plus(5, ChronoUnit.MINUTES), AUDIENCIA, CLAVE, null, null);
    }

    static String token(
            String tokenSubject, String email, boolean emailVerified, Instant expiresAt, String audience, RSAKey key) {
        return token(
                tokenSubject, email, emailVerified, expiresAt, audience, key, List.of("pwd", "otp"), Instant.now());
    }

    static String token(
            String tokenSubject,
            String email,
            boolean emailVerified,
            Instant expiresAt,
            String audience,
            RSAKey key,
            @Nullable List<String> amr,
            @Nullable Instant autenticadoEn) {
        try {
            var constructor = new JWTClaimsSet.Builder()
                    .subject(tokenSubject)
                    .issuer(EMISOR)
                    .audience(audience)
                    .issueTime(Date.from(expiresAt.minus(5, ChronoUnit.MINUTES)))
                    .expirationTime(Date.from(expiresAt))
                    .claim("email", email)
                    .claim("email_verified", emailVerified);
            if (amr != null) {
                constructor.claim("amr", amr);
            }
            if (autenticadoEn != null) {
                // En segundos desde la epoca, que es como el OIDC define auth_time. Un Instant
                // serializado como texto no lo lee el decodificador y la afirmacion se pierde.
                constructor.claim("auth_time", autenticadoEn.getEpochSecond());
            }
            var claims = constructor.build();
            var signed = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .type(JOSEObjectType.JWT)
                            .keyID(key.getKeyID())
                            .build(),
                    claims);
            signed.sign(new RSASSASigner(key.toPrivateKey()));
            return signed.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException("No se pudo firmar el token de prueba", failure);
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
