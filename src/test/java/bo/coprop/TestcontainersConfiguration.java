package bo.coprop;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL para los tests. La version se mantiene igual a la de docker-compose.yml: una
 * diferencia de version entre desarrollo y test esconde errores de migracion.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    static final DockerImageName POSTGRES = DockerImageName.parse("postgres:16-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES);
    }
}
