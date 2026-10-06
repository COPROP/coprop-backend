package bo.coprop;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Las migraciones corren en limpio sobre una base vacia y dejan el esquema que las entidades
 * esperan. Si esto pasa, {@code ddl-auto: validate} no va a tumbar el arranque en produccion.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MigracionesTests {

    @Autowired
    DataSource dataSource;

    @Autowired
    Flyway flyway;

    @Test
    void lasMigracionesCorrenEnLimpio() {
        var info = flyway.info();

        assertThat(info.applied()).isNotEmpty();
        assertThat(info.pending()).isEmpty();
    }

    @Test
    void lasExtensionesQuedanInstaladas() {
        var jdbc = new JdbcTemplate(dataSource);

        var extensiones = jdbc.queryForList(
                "select extname from pg_extension where extname in ('pgcrypto', 'btree_gist', 'unaccent')",
                String.class);

        assertThat(extensiones).containsExactlyInAnyOrder("pgcrypto", "btree_gist", "unaccent");
    }

    @Test
    void volverACorrerLasMigracionesNoCambiaNada() {
        var antes = flyway.info().applied().length;

        flyway.migrate();

        assertThat(flyway.info().applied()).hasSize(antes);
    }
}
