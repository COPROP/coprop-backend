package bo.coprop.identity;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import bo.coprop.TestcontainersConfiguration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * El arranque de la plataforma: que exista un superadministrador sin que nadie lo siembre.
 *
 * <p>Es lo que quita el andamio. Hasta el issue #16, empezar a usar COPROP en una base limpia
 * pedia tres INSERT a mano, porque toda la API exige identidad y la identidad exige una cuenta que
 * alguien dio de alta. Ahora basta una propiedad, como en el propio Keycloak.
 *
 * <p>El perfil {@code local} trae {@code coprop.superadministrador-inicial: dev@coprop.local}, asi
 * que el contexto de cualquier test ya arranca con uno: lo que se comprueba aqui es justamente eso.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PlatformBootstrapTests {

    private static final String CORREO = "dev@coprop.local";

    @Autowired
    private Identities identities;

    @Autowired
    private DataSource dataSource;

    private UUID cuentaDelCorreoInicial() {
        return requireNonNull(
                new JdbcTemplate(dataSource)
                        .queryForObject(
                                "select id from user_account where lower(email) = lower(?)", UUID.class, CORREO),
                "el arranque tenia que haber creado la cuenta de " + CORREO);
    }

    @Test
    @DisplayName("al arrancar sin superadministradores se abre el primero, con su persona y su cuenta")
    void onStartupTheFirstIsOpened() {
        assertThat(identities.esSuperadministrador(cuentaDelCorreoInicial())).isTrue();
    }

    @Test
    @DisplayName("la cuenta nace sin vincular: la propiedad abre la puerta, no entra por ti")
    void theAccountIsBornUnlinked() {
        // No crea credenciales ni toca el proveedor de identidad. Alguien tiene que entrar con ese
        // correo para que la cuenta se vincule, que es lo que ya hace el issue #13.
        var jdbc = new JdbcTemplate(dataSource);
        String status = jdbc.queryForObject(
                "select status from user_account where lower(email) = lower(?)", String.class, CORREO);

        assertThat(status).isEqualTo(AccountStatus.INVITED.name());
    }

    @Test
    @DisplayName("no abre un segundo: en cuanto hay uno, la propiedad deja de hacer nada")
    void itDoesNotOpenASecondOne() {
        // Es lo que impide que la propiedad sea una puerta trasera permanente. Dejarla puesta en
        // produccion por descuido no abre nada mas, y quitarla no cierra lo ya abierto.
        var jdbc = new JdbcTemplate(dataSource);
        Integer cuantos = jdbc.queryForObject(
                "select count(*) from platform_admin p join user_account c on c.id = p.user_id"
                        + " where lower(c.email) = lower(?)",
                Integer.class,
                CORREO);

        assertThat(cuantos).isEqualTo(1);
    }

    @Test
    @DisplayName("el primero no lo concede nadie, y eso queda escrito")
    void nobodyGrantsTheFirstOne() {
        // granted_by nulo es la unica forma de distinguir despues el que abrio el arranque de los
        // que concedio una persona. La bitacora del issue #21 lo va a querer.
        var jdbc = new JdbcTemplate(dataSource);
        Integer withoutAGrantor = jdbc.queryForObject(
                "select count(*) from platform_admin p join user_account c on c.id = p.user_id"
                        + " where lower(c.email) = lower(?) and p.granted_by is null",
                Integer.class,
                CORREO);

        assertThat(withoutAGrantor).isEqualTo(1);
    }
}
