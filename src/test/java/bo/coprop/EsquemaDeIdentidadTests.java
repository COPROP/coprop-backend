package bo.coprop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que la migracion V5 promete, comprobado contra PostgreSQL de verdad.
 *
 * <p>Va sobre SQL y no sobre el dominio a proposito: lo que se prueba aqui son las garantias del
 * esquema --indices parciales, claves foraneas compuestas, {@code NULLS NOT DISTINCT}--, que son
 * las que siguen en pie el dia que alguien escriba por otra via. Las reglas de negocio que se
 * apoyan en ellas se prueban en el modulo.
 *
 * <p>Cada test vive en su propia transaccion porque una violacion de restriccion aborta la
 * transaccion en PostgreSQL: tras el primer fallo esperado no se puede seguir consultando.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class EsquemaDeIdentidadTests {

    private static final Instant AHORA = Instant.parse("2026-10-12T12:00:00Z");
    private static final OffsetDateTime DESDE = OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC);

    private final JdbcTemplate jdbc;

    EsquemaDeIdentidadTests(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    // ----------------------------------------------------------------------------------------
    // Decision 5: las tres relaciones separadas, y RF-03
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("RF-03: la misma persona es propietaria en un condominio e inquilina en otro")
    void laMismaPersonaEsPropietariaEnUnCondominioEInquilinaEnOtro() {
        UUID lasPalmas = condominio("1000000001", "Las Palmas");
        UUID villaSol = condominio("1000000002", "Villa Sol");
        UUID enLasPalmas = unidad(lasPalmas, "302");
        UUID enVillaSol = unidad(villaSol, "A-1");
        UUID persona = persona("8123456");

        titularidad(lasPalmas, enLasPalmas, persona, new BigDecimal("12.500000"));
        ocupacion(villaSol, enVillaSol, persona, "TENANT");

        assertThat(cuantas("unit_ownership", "person_id = ?", persona)).isEqualTo(1);
        assertThat(cuantas("unit_occupancy", "person_id = ?", persona)).isEqualTo(1);
    }

    @Test
    @DisplayName("el propietario sigue pagando una unidad que alquila, y el inquilino la ocupa")
    void elPropietarioPagaLoQueElInquilinoOcupa() {
        UUID condominio = condominio("1000000003", "Torre Azul");
        UUID unidad = unidad(condominio, "501");
        UUID propietaria = persona("9000001");
        UUID inquilino = persona("9000002");

        titularidad(condominio, unidad, propietaria, new BigDecimal("8.000000"));
        ocupacion(condominio, unidad, inquilino, "TENANT");
        responsabilidad(condominio, unidad, propietaria, null);

        // Es justo lo que una sola tabla con columna de "tipo" no podria decir: dos personas
        // distintas, con dos papeles distintos, vigentes a la vez sobre la misma unidad.
        assertThat(cuantas("unit_ownership", "unit_id = ?", unidad)).isEqualTo(1);
        assertThat(cuantas("unit_occupancy", "unit_id = ?", unidad)).isEqualTo(1);
        assertThat(cuantas("billing_responsibility", "unit_id = ?", unidad)).isEqualTo(1);
    }

    @Test
    @DisplayName("una unidad admite varios copropietarios vigentes")
    void unaUnidadAdmiteVariosCopropietarios() {
        UUID condominio = condominio("1000000004", "Los Pinos");
        UUID unidad = unidad(condominio, "101");

        titularidad(condominio, unidad, persona("9100001"), new BigDecimal("50.000000"));
        titularidad(condominio, unidad, persona("9100002"), new BigDecimal("50.000000"));

        assertThat(cuantas("unit_ownership", "unit_id = ?", unidad)).isEqualTo(2);
    }

    @Test
    @DisplayName("la misma persona no figura dos veces como propietaria vigente de una unidad")
    void laMismaPersonaNoFiguraDosVecesComoPropietaria() {
        UUID condominio = condominio("1000000005", "Mirador");
        UUID unidad = unidad(condominio, "201");
        UUID persona = persona("9200001");
        titularidad(condominio, unidad, persona, new BigDecimal("40.000000"));

        assertThatThrownBy(() -> titularidad(condominio, unidad, persona, new BigDecimal("60.000000")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("unit_ownership_current_uq");
    }

    // ----------------------------------------------------------------------------------------
    // Criterios de aceptacion del issue #11
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("una persona no puede tener dos cuentas")
    void unaPersonaNoPuedeTenerDosCuentas() {
        UUID persona = persona("9300001");
        cuentaDe(persona, "primera@coprop.bo");

        assertThatThrownBy(() -> cuentaDe(persona, "segunda@coprop.bo"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("user_account_person_uq");
    }

    @Test
    @DisplayName("no puede haber dos membresias vigentes de la misma cuenta en el mismo condominio")
    void noPuedeHaberDosMembresiasVigentesEnElMismoCondominio() {
        UUID condominio = condominio("1000000006", "Alborada");
        UUID cuenta = cuentaDe(persona("9400001"), "duplicada@coprop.bo");
        membresia(cuenta, condominio, null);

        assertThatThrownBy(() -> membresia(cuenta, condominio, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("membership_current_uq");
    }

    @Test
    @DisplayName("la misma cuenta si puede tener membresia en dos condominios")
    void laMismaCuentaTieneMembresiaEnDosCondominios() {
        UUID uno = condominio("1000000007", "Sur");
        UUID otro = condominio("1000000008", "Norte");
        UUID cuenta = cuentaDe(persona("9500001"), "dos@coprop.bo");

        membresia(cuenta, uno, null);
        membresia(cuenta, otro, null);

        assertThat(cuantas("membership", "user_id = ?", cuenta)).isEqualTo(2);
    }

    @Test
    @DisplayName("cerrada la membresia se puede volver a entrar al mismo condominio")
    void cerradaLaMembresiaSePuedeVolverAEntrar() {
        UUID condominio = condominio("1000000009", "Retorno");
        UUID cuenta = cuentaDe(persona("9600001"), "vuelve@coprop.bo");

        membresia(cuenta, condominio, DESDE.plusDays(30));
        membresia(cuenta, condominio, null);

        assertThat(cuantas("membership", "user_id = ?", cuenta)).isEqualTo(2);
        assertThat(cuantas("membership", "user_id = ? AND valid_to IS NULL", cuenta))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("una membresia puede llevar varios roles")
    void unaMembresiaLlevaVariosRoles() {
        UUID condominio = condominio("1000000010", "Directorio");
        UUID cuenta = cuentaDe(persona("9700001"), "directivo@coprop.bo");
        UUID membresia = membresia(cuenta, condominio, null);

        jdbc.update("INSERT INTO membership_role (membership_id, role) VALUES (?, ?)", membresia, "OWNER");
        jdbc.update("INSERT INTO membership_role (membership_id, role) VALUES (?, ?)", membresia, "BOARD");

        assertThat(cuantas("membership_role", "membership_id = ?", membresia)).isEqualTo(2);
    }

    @Test
    @DisplayName("un rol que no esta en el catalogo de Seguridad 3 se rechaza")
    void unRolFueraDelCatalogoSeRechaza() {
        UUID condominio = condominio("1000000011", "Catalogo");
        UUID cuenta = cuentaDe(persona("9800001"), "rol@coprop.bo");
        UUID membresia = membresia(cuenta, condominio, null);

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO membership_role (membership_id, role) VALUES (?, ?)", membresia, "JEFE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("membership_role_ck");
    }

    // ----------------------------------------------------------------------------------------
    // Decision 6: condominium_id coherente por construccion
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("un piso no puede declararse de un condominio distinto del de su bloque")
    void elPisoNoPuedeMentirSobreSuCondominio() {
        UUID propio = condominio("1000000012", "Propio");
        UUID ajeno = condominio("1000000013", "Ajeno");
        UUID bloque = bloque(propio, "Torre A");

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO floor (id, condominium_id, block_id, number) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(),
                        ajeno,
                        bloque,
                        3))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("floor_condominium_fk");
    }

    @Test
    @DisplayName("la titularidad no puede apuntar a una unidad de otro condominio")
    void laTitularidadNoPuedeApuntarAUnaUnidadAjena() {
        UUID propio = condominio("1000000014", "Casa propia");
        UUID ajeno = condominio("1000000015", "Casa ajena");
        UUID unidadAjena = unidad(ajeno, "77");
        UUID persona = persona("9900001");

        assertThatThrownBy(() -> titularidad(propio, unidadAjena, persona, new BigDecimal("10.000000")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("unit_ownership_unit_fk");
    }

    @Test
    @DisplayName("el piso existente hereda el condominio de su bloque en el backfill")
    void elPisoHeredaElCondominioDeSuBloque() {
        UUID condominio = condominio("1000000016", "Backfill");
        UUID bloque = bloque(condominio, "Torre B");
        UUID piso = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO floor (id, condominium_id, block_id, number) VALUES (?, ?, ?, ?)",
                piso,
                condominio,
                bloque,
                1);

        assertThat(jdbc.queryForObject("SELECT condominium_id FROM floor WHERE id = ?", UUID.class, piso))
                .isEqualTo(condominio);
    }

    // ----------------------------------------------------------------------------------------
    // Detalles del esquema que es facil romper sin darse cuenta
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("el responsable general de una unidad no se duplica aunque el concepto sea nulo")
    void elResponsableGeneralNoSeDuplica() {
        UUID condominio = condominio("1000000017", "Responsable");
        UUID unidad = unidad(condominio, "801");
        UUID persona = persona("9910001");
        responsabilidad(condominio, unidad, persona, null);

        // Sin NULLS NOT DISTINCT esto pasaria: PostgreSQL considera distintos dos nulos y el
        // indice unico no vería el duplicado.
        assertThatThrownBy(() -> responsabilidad(condominio, unidad, persona, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("billing_responsibility_current_uq");
    }

    @Test
    @DisplayName("la misma persona responde por conceptos distintos de la misma unidad")
    void laMismaPersonaRespondePorConceptosDistintos() {
        UUID condominio = condominio("1000000018", "Conceptos");
        UUID unidad = unidad(condominio, "802");
        UUID persona = persona("9920001");

        responsabilidad(condominio, unidad, persona, UUID.randomUUID());
        responsabilidad(condominio, unidad, persona, UUID.randomUUID());

        assertThat(cuantas("billing_responsibility", "unit_id = ?", unidad)).isEqualTo(2);
    }

    @Test
    @DisplayName("el carnet es unico cuando viene, y varias personas pueden no tenerlo")
    void elCarnetEsUnicoCuandoViene() {
        persona(null);
        persona(null);
        persona("9930001");

        assertThat(cuantas("person", "national_id IS NULL", null)).isGreaterThanOrEqualTo(2);
        assertThatThrownBy(() -> persona("9930001"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("person_national_id_uq");
    }

    @Test
    @DisplayName("una cuenta no puede quedar en un estado que no esta en Seguridad 2")
    void elEstadoDeLaCuentaEstaAcotado() {
        UUID persona = persona("9940001");

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO user_account (id, person_id, email, status, created_at) VALUES (?, ?, ?, ?, ?)",
                        UUID.randomUUID(),
                        persona,
                        "raro@coprop.bo",
                        "DORMIDO",
                        DESDE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("user_account_status_ck");
    }

    @Test
    @DisplayName("nadie se invita a si mismo")
    void nadieSeInvitaASiMismo() {
        UUID condominio = condominio("1000000019", "Invitacion");
        UUID cuenta = cuentaDe(persona("9950001"), "solo@coprop.bo");

        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO membership (id, user_id, condominium_id, status, valid_from, invited_by)
                        VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                        """,
                        UUID.randomUUID(),
                        cuenta,
                        condominio,
                        DESDE,
                        cuenta))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("membership_invited_by_ck");
    }

    // ----------------------------------------------------------------------------------------
    // Ayudantes
    // ----------------------------------------------------------------------------------------

    private UUID condominio(String nit, String nombre) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO condominium (id, name, nit, type, time_zone, currency, status, created_at)
                VALUES (?, ?, ?, 'BUILDING', 'America/La_Paz', 'BOB', 'ACTIVE', ?)
                """,
                id,
                nombre,
                nit,
                DESDE);
        return id;
    }

    private UUID bloque(UUID condominiumId, String nombre) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO block (id, condominium_id, name) VALUES (?, ?, ?)", id, condominiumId, nombre);
        return id;
    }

    private UUID unidad(UUID condominiumId, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO unit (id, condominium_id, code, type, aliquot, status)
                VALUES (?, ?, ?, 'APARTMENT', 1.000000, 'ACTIVE')
                """,
                id,
                condominiumId,
                code);
        return id;
    }

    private UUID persona(@Nullable String carnet) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO person (id, first_names, last_names, national_id, created_at)
                VALUES (?, 'Emma', 'Tola', ?, ?)
                """,
                id,
                carnet,
                DESDE);
        return id;
    }

    private UUID cuentaDe(UUID personId, String correo) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO user_account (id, person_id, email, status, created_at)
                VALUES (?, ?, ?, 'INVITED', ?)
                """,
                id,
                personId,
                correo,
                DESDE);
        return id;
    }

    private UUID membresia(UUID userId, UUID condominiumId, @Nullable OffsetDateTime hasta) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO membership (id, user_id, condominium_id, status, valid_from, valid_to)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                """,
                id,
                userId,
                condominiumId,
                DESDE,
                hasta);
        return id;
    }

    private void titularidad(UUID condominiumId, UUID unitId, UUID personId, BigDecimal share) {
        jdbc.update(
                """
                INSERT INTO unit_ownership (id, condominium_id, unit_id, person_id, share, valid_from)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                condominiumId,
                unitId,
                personId,
                share,
                DESDE);
    }

    private void ocupacion(UUID condominiumId, UUID unitId, UUID personId, String rol) {
        jdbc.update(
                """
                INSERT INTO unit_occupancy (id, condominium_id, unit_id, person_id, role, valid_from)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                condominiumId,
                unitId,
                personId,
                rol,
                DESDE);
    }

    private void responsabilidad(UUID condominiumId, UUID unitId, UUID personId, @Nullable UUID conceptId) {
        jdbc.update(
                """
                INSERT INTO billing_responsibility
                    (id, condominium_id, unit_id, person_id, concept_id, valid_from)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                condominiumId,
                unitId,
                personId,
                conceptId,
                DESDE);
    }

    private int cuantas(String tabla, String condicion, @Nullable Object parametro) {
        String sql = "SELECT count(*) FROM " + tabla + " WHERE " + condicion;
        Integer total = parametro == null
                ? jdbc.queryForObject(sql, Integer.class)
                : jdbc.queryForObject(sql, Integer.class, parametro);
        return total == null ? 0 : total;
    }
}
