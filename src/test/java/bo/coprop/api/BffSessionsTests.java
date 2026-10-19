package bo.coprop.api;

import static org.assertj.core.api.Assertions.assertThat;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.identity.Sessions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * El cierre de sesiones del BFF del issue <strong>#19</strong>, contra Spring Session de verdad.
 *
 * <p>Lo que se prueba aqui no es el borrado --eso es un {@code delete} y no tiene misterio-- sino
 * <strong>con que nombre queda indexada una sesion del BFF</strong>. Es la pieza donde el cierre se
 * puede romper en silencio: si el indice no es el que el puerto recibe, {@code cerrarLasDe} no
 * encuentra nada, devuelve cero y la baja parece haber cerrado sesiones que siguen abiertas.
 *
 * <p>La sesion se arma como la deja un login OIDC --un {@code OAuth2AuthenticationToken} dentro del
 * contexto de seguridad, bajo la clave que usa {@code HttpSessionSecurityContextRepository}-- y de
 * ahi sale que el nombre sea el {@code sub} del token. Es lo que justifica que el puerto reciba un
 * conjunto y no un identificador: hoy el indice es el sujeto del proveedor, y pasar tambien el de
 * la cuenta cuesta una consulta y deja el cierre a salvo del dia que Spring Security empiece a
 * persistir la identidad que el filtro del #13 pone en cada peticion.
 *
 * <p>Se usa {@link JdbcIndexedSessionRepository} y no la interfaz con comodin porque hay que
 * <em>escribir</em> una sesion, y {@code save} no se puede invocar sobre un
 * {@code FindByIndexNameSessionRepository<? extends Session>}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BffSessionsTests {

    @Autowired
    private JdbcIndexedSessionRepository repositorio;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> porNombre;

    @Autowired
    private Sessions sessions;

    @Test
    @DisplayName("una sesion del BFF queda indexada por el sub, y el puerto la cierra")
    void laSesionQuedaIndexadaPorElSubYSeCierra() {
        String tokenSubject = UUID.randomUUID().toString();
        String id = sesionDeAlguien(tokenSubject);

        // Lo que de verdad se comprueba: el nombre con el que Spring Session la tiene guardada.
        assertThat(porNombre.findByPrincipalName(tokenSubject)).containsKey(id);

        assertThat(sessions.closeThoseOf(Set.of(tokenSubject))).isEqualTo(1);

        assertThat(repositorio.findById(id)).isNull();
        assertThat(porNombre.findByPrincipalName(tokenSubject)).isEmpty();
    }

    @Test
    @DisplayName("los dos nombres se cierran: el segundo del conjunto no es decorativo")
    void losDosNombresSeCierran() {
        String tokenSubject = UUID.randomUUID().toString();
        String account = UUID.randomUUID().toString();
        String porElSujeto = sesionDeAlguien(tokenSubject);
        // Una sesion indexada por el identificador de la cuenta: es la forma que tendria si algun
        // dia se persistiera la identidad que el filtro del #13 pone en cada peticion. Hoy no pasa,
        // y el puerto manda los dos nombres justo para que ese cambio no rompa el cierre en
        // silencio. Sin este caso, la mitad de ese conjunto no estaria probada.
        String porLaCuenta = sesionDeAlguien(account);

        assertThat(sessions.closeThoseOf(Set.of(tokenSubject, account))).isEqualTo(2);

        assertThat(repositorio.findById(porElSujeto)).isNull();
        assertThat(repositorio.findById(porLaCuenta)).isNull();
    }

    @Test
    @DisplayName("cerrar las sesiones de quien no tiene ninguna no es un error")
    void sinSesionesNoEsUnError() {
        // Es el caso de una cuenta que nunca entro por la web, y pasa en toda baja de alguien que
        // solo usa la app movil. Tratarlo como un fallo haria que una baja normal pareciera roto.
        assertThat(sessions.closeThoseOf(Set.of(UUID.randomUUID().toString()))).isZero();
    }

    @Test
    @DisplayName("el listado devuelve la sesion con sus tiempos, y no la repite por los dos nombres")
    void elListadoDevuelveLaSesionSinRepetirla() {
        String tokenSubject = UUID.randomUUID().toString();
        String id = sesionDeAlguien(tokenSubject);

        // Con los dos nombres, igual que el cierre: hoy solo uno de los dos encuentra algo, y el
        // dia que los dos encuentren la misma sesion no se puede listar dos veces o el usuario
        // creeria tener dos navegadores abiertos donde hay uno.
        var encontradas =
                sessions.listThoseOf(Set.of(tokenSubject, UUID.randomUUID().toString()));

        assertThat(encontradas).singleElement().satisfies(session -> {
            assertThat(session.id()).isEqualTo(id);
            assertThat(session.created()).isNotNull();
            assertThat(session.lastAccess()).isNotNull();
            // El tiempo sale de la sesion y no de una constante: es el que el filtro de vigencia
            // le dejo puesto, y en una sesion recien creada es el de la configuracion.
            assertThat(session.inactividad()).isPositive();
        });
    }

    @Test
    @DisplayName("quien no tiene ninguna recibe una lista vacia, no un error")
    void sinSesionesLaListaEstaVacia() {
        assertThat(sessions.listThoseOf(Set.of(UUID.randomUUID().toString()))).isEmpty();
    }

    @Test
    @DisplayName("una sesion cerrada deja de aparecer en el listado")
    void laCerradaYaNoSeLista() {
        String tokenSubject = UUID.randomUUID().toString();
        sesionDeAlguien(tokenSubject);

        sessions.closeThoseOf(Set.of(tokenSubject));

        // Es el control de las dos mitades juntas: sin esto, un listado que leyera de otro sitio
        // --o un cierre que no borrara-- pasaria igual.
        assertThat(sessions.listThoseOf(Set.of(tokenSubject))).isEmpty();
    }

    /** Una sesion como la que deja un login por el BFF, y devuelve su identificador. */
    private String sesionDeAlguien(String tokenSubject) {
        var idToken = new OidcIdToken(
                "no-se-mira",
                Instant.now(),
                Instant.now().plus(1, ChronoUnit.HOURS),
                Map.of(IdTokenClaimNames.SUB, tokenSubject));
        var usuario = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken);
        var autenticacion =
                new OAuth2AuthenticationToken(usuario, usuario.getAuthorities(), SecurityConfiguration.REGISTRO_WEB);

        SecurityContext contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(autenticacion);
        return guardar(repositorio, contexto);
    }

    /**
     * Crea y guarda una sesion con ese contexto de seguridad dentro.
     *
     * <p>Generico porque el tipo de sesion del repositorio JDBC <strong>no es publico</strong>: no
     * se puede nombrar ni castear desde aqui, pero el compilador si lo infiere a traves del
     * parametro de tipo. Es la unica forma de escribir una sesion de verdad en este test.
     */
    private static <S extends Session> String guardar(SessionRepository<S> repositorio, SecurityContext contexto) {
        S session = repositorio.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, contexto);
        repositorio.save(session);
        return session.getId();
    }
}
