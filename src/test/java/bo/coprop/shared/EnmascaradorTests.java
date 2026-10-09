package bo.coprop.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Las reglas de enmascarado, una a una. El criterio del issue #6 se prueba en {@link FormatoJsonDeLogTests}. */
class EnmascaradorTests {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"client_secret\":\"s3cr3t-del-banco\"}",
                "{\"password\":\"s3cr3t-del-banco\"}",
                "{\"apiKey\":\"s3cr3t-del-banco\"}",
                "credencial=s3cr3t-del-banco",
                "cuenta: s3cr3t-del-banco",
            })
    @DisplayName("tapa el valor de una clave sensible, venga en JSON o como pareja suelta")
    void tapaValoresDeClavesSensibles(String texto) {
        assertThat(Enmascarador.enmascarar(texto))
                .doesNotContain("s3cr3t-del-banco")
                .contains(Enmascarador.TAPADO);
    }

    @Test
    @DisplayName("conserva el nombre de la clave: el log sigue diciendo que habia un secreto")
    void conservaElNombreDeLaClave() {
        assertThat(Enmascarador.enmascarar("{\"client_secret\":\"abc\"}")).isEqualTo("{\"client_secret\":\"***\"}");
    }

    @Test
    @DisplayName("tapa un Bearer y un JWT suelto, que se reconocen por su forma")
    void tapaTokens() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.firma-que-no-debe-salir";
        assertThat(Enmascarador.enmascarar("Authorization: Bearer " + jwt)).doesNotContain("firma-que-no-debe-salir");
        assertThat(Enmascarador.enmascarar("el token es " + jwt)).doesNotContain("firma-que-no-debe-salir");
    }

    @Test
    @DisplayName("no toca lo que no es un secreto: un log enmascarado de mas no sirve para depurar")
    void noTocaElRestoDelMensaje() {
        String mensaje = "Pago 42 aplicado a la unidad 7B del condominio Las Palmas en 120 ms";
        assertThat(Enmascarador.enmascarar(mensaje)).isEqualTo(mensaje);
    }

    @Test
    @DisplayName("con la clave aparte, tapa por el nombre de la clave")
    void tapaPorNombreDeClave() {
        assertThat(Enmascarador.valorDe("token", "loquesea")).isEqualTo(Enmascarador.TAPADO);
        assertThat(Enmascarador.valorDe("Authorization", "loquesea")).isEqualTo(Enmascarador.TAPADO);
        assertThat(Enmascarador.valorDe("traceId", "abc-123")).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("un texto nulo o vacio sale como cadena vacia, sin reventar")
    void toleraNulos() {
        assertThat(Enmascarador.enmascarar(null)).isEmpty();
        assertThat(Enmascarador.enmascarar("")).isEmpty();
    }
}
