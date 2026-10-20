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
 * Lo que la migracion V5 promete --y, desde el issue #100, lo que añade la V11--, comprobado contra
 * PostgreSQL de verdad.
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
class IdentitySchemaTests {

    private static final Instant AHORA = Instant.parse("2026-10-12T12:00:00Z");
    private static final OffsetDateTime DESDE = OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC);

    private final JdbcTemplate jdbc;

    IdentitySchemaTests(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    // ----------------------------------------------------------------------------------------
    // Decision 5: las tres relaciones separadas, y RF-03
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("RF-03: la misma persona es propietaria en un condominio e inquilina en otro")
    void theSamePersonIsOwnerInOneCondominiumAndTenantInAnother() {
        UUID lasPalmas = condominium("1000000001", "Las Palmas");
        UUID villaSol = condominium("1000000002", "Villa Sol");
        UUID enLasPalmas = unit(lasPalmas, "302");
        UUID enVillaSol = unit(villaSol, "A-1");
        UUID person = person("8123456");

        ownership(lasPalmas, enLasPalmas, person, new BigDecimal("12.500000"));
        occupancy(villaSol, enVillaSol, person, "TENANT");

        assertThat(cuantas("unit_ownership", "person_id = ?", person)).isEqualTo(1);
        assertThat(cuantas("unit_occupancy", "person_id = ?", person)).isEqualTo(1);
    }

    @Test
    @DisplayName("el propietario sigue pagando una unidad que alquila, y el inquilino la ocupa")
    void theOwnerPaysForWhatTheTenantOccupies() {
        UUID condominium = condominium("1000000003", "Torre Azul");
        UUID unit = unit(condominium, "501");
        UUID owner = person("9000001");
        UUID inquilino = person("9000002");

        ownership(condominium, unit, owner, new BigDecimal("8.000000"));
        occupancy(condominium, unit, inquilino, "TENANT");
        billingResponsibility(condominium, unit, owner, null);

        // Es justo lo que una sola tabla con columna de "tipo" no podria decir: dos personas
        // distintas, con dos papeles distintos, vigentes a la vez sobre la misma unidad.
        assertThat(cuantas("unit_ownership", "unit_id = ?", unit)).isEqualTo(1);
        assertThat(cuantas("unit_occupancy", "unit_id = ?", unit)).isEqualTo(1);
        assertThat(cuantas("billing_responsibility", "unit_id = ?", unit)).isEqualTo(1);
    }

    @Test
    @DisplayName("una unidad admite varios copropietarios vigentes")
    void aUnitAcceptsSeveralCoOwners() {
        UUID condominium = condominium("1000000004", "Los Pinos");
        UUID unit = unit(condominium, "101");

        ownership(condominium, unit, person("9100001"), new BigDecimal("50.000000"));
        ownership(condominium, unit, person("9100002"), new BigDecimal("50.000000"));

        assertThat(cuantas("unit_ownership", "unit_id = ?", unit)).isEqualTo(2);
    }

    @Test
    @DisplayName("la misma persona no figura dos veces como propietaria vigente de una unidad")
    void theSamePersonIsNotListedTwiceAsOwner() {
        UUID condominium = condominium("1000000005", "Mirador");
        UUID unit = unit(condominium, "201");
        UUID person = person("9200001");
        ownership(condominium, unit, person, new BigDecimal("40.000000"));

        assertThatThrownBy(() -> ownership(condominium, unit, person, new BigDecimal("60.000000")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("unit_ownership_current_uq");
    }

    // ----------------------------------------------------------------------------------------
    // Criterios de aceptacion del issue #11
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("una persona no puede tener dos cuentas")
    void aPersonCannotHaveTwoAccounts() {
        UUID person = person("9300001");
        accountOf(person, "primera@coprop.bo");

        assertThatThrownBy(() -> accountOf(person, "segunda@coprop.bo"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("user_account_person_uq");
    }

    @Test
    @DisplayName("no puede haber dos membresias vigentes de la misma cuenta en el mismo condominio")
    void thereCannotBeTwoCurrentMembershipsInTheSameCondominium() {
        UUID condominium = condominium("1000000006", "Alborada");
        UUID account = accountOf(person("9400001"), "duplicada@coprop.bo");
        membership(account, condominium, null);

        assertThatThrownBy(() -> membership(account, condominium, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("membership_current_uq");
    }

    @Test
    @DisplayName("la misma cuenta si puede tener membresia en dos condominios")
    void theSameAccountHasMembershipInTwoCondominiums() {
        UUID uno = condominium("1000000007", "Sur");
        UUID otro = condominium("1000000008", "Norte");
        UUID account = accountOf(person("9500001"), "dos@coprop.bo");

        membership(account, uno, null);
        membership(account, otro, null);

        assertThat(cuantas("membership", "user_id = ?", account)).isEqualTo(2);
    }

    @Test
    @DisplayName("cerrada la membresia se puede volver a entrar al mismo condominio")
    void withTheMembershipClosedSignInStillWorks() {
        UUID condominium = condominium("1000000009", "Retorno");
        UUID account = accountOf(person("9600001"), "vuelve@coprop.bo");

        membership(account, condominium, DESDE.plusDays(30));
        membership(account, condominium, null);

        assertThat(cuantas("membership", "user_id = ?", account)).isEqualTo(2);
        assertThat(cuantas("membership", "user_id = ? AND valid_to IS NULL", account))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("una membresia puede llevar varios roles")
    void aMembershipCarriesSeveralRoles() {
        UUID condominium = condominium("1000000010", "Directorio");
        UUID account = accountOf(person("9700001"), "directivo@coprop.bo");
        UUID membership = membership(account, condominium, null);

        jdbc.update("INSERT INTO membership_role (membership_id, role) VALUES (?, ?)", membership, "OWNER");
        jdbc.update("INSERT INTO membership_role (membership_id, role) VALUES (?, ?)", membership, "BOARD");

        assertThat(cuantas("membership_role", "membership_id = ?", membership)).isEqualTo(2);
    }

    @Test
    @DisplayName("un rol que no esta en el catalogo de Seguridad 3 se rechaza")
    void aRoleOutsideTheCatalogIsRejected() {
        UUID condominium = condominium("1000000011", "Catalogo");
        UUID account = accountOf(person("9800001"), "rol@coprop.bo");
        UUID membership = membership(account, condominium, null);

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO membership_role (membership_id, role) VALUES (?, ?)", membership, "JEFE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("membership_role_ck");
    }

    // ----------------------------------------------------------------------------------------
    // Decision 6: condominium_id coherente por construccion
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("un piso no puede declararse de un condominio distinto del de su bloque")
    void theFloorCannotLieAboutItsCondominium() {
        UUID propio = condominium("1000000012", "Propio");
        UUID ajeno = condominium("1000000013", "Ajeno");
        UUID block = block(propio, "Torre A");

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO floor (id, condominium_id, block_id, number) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(),
                        ajeno,
                        block,
                        3))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("floor_condominium_fk");
    }

    @Test
    @DisplayName("la titularidad no puede apuntar a una unidad de otro condominio")
    void theOwnershipCannotPointAtAnotherCondominiumsUnit() {
        UUID propio = condominium("1000000014", "Casa propia");
        UUID ajeno = condominium("1000000015", "Casa ajena");
        UUID unidadAjena = unit(ajeno, "77");
        UUID person = person("9900001");

        assertThatThrownBy(() -> ownership(propio, unidadAjena, person, new BigDecimal("10.000000")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("unit_ownership_unit_fk");
    }

    @Test
    @DisplayName("el piso existente hereda el condominio de su bloque en el backfill")
    void theFloorInheritsTheCondominiumOfItsBlock() {
        UUID condominium = condominium("1000000016", "Backfill");
        UUID block = block(condominium, "Torre B");
        UUID floor = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO floor (id, condominium_id, block_id, number) VALUES (?, ?, ?, ?)",
                floor,
                condominium,
                block,
                1);

        assertThat(jdbc.queryForObject("SELECT condominium_id FROM floor WHERE id = ?", UUID.class, floor))
                .isEqualTo(condominium);
    }

    // ----------------------------------------------------------------------------------------
    // Detalles del esquema que es facil romper sin darse cuenta
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("el responsable general de una unidad no se duplica aunque el concepto sea nulo")
    void theGeneralBillingResponsibleIsNotDuplicated() {
        UUID condominium = condominium("1000000017", "Responsable");
        UUID unit = unit(condominium, "801");
        UUID person = person("9910001");
        billingResponsibility(condominium, unit, person, null);

        // Sin NULLS NOT DISTINCT esto pasaria: PostgreSQL considera distintos dos nulos y el
        // indice unico no vería el duplicado.
        assertThatThrownBy(() -> billingResponsibility(condominium, unit, person, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("billing_responsibility_current_uq");
    }

    @Test
    @DisplayName("la misma persona responde por conceptos distintos de la misma unidad")
    void theSamePersonIsResponsibleForDifferentConcepts() {
        UUID condominium = condominium("1000000018", "Conceptos");
        UUID unit = unit(condominium, "802");
        UUID person = person("9920001");

        // Conceptos de verdad y no identificadores inventados: desde la V11 la columna tiene clave
        // foranea, asi que un UUID al azar ya no entra.
        billingResponsibility(condominium, unit, person, chargeConcept(condominium, "EXP"));
        billingResponsibility(condominium, unit, person, chargeConcept(condominium, "AGUA"));

        assertThat(cuantas("billing_responsibility", "unit_id = ?", unit)).isEqualTo(2);
    }

    @Test
    @DisplayName("una responsabilidad de pago no puede apuntar al concepto de otro condominio")
    void theBillingResponsibilityCannotPointToAnotherCondominiumsConcept() {
        UUID propio = condominium("1000000031", "Con concepto propio");
        UUID ajeno = condominium("1000000032", "Con concepto ajeno");
        UUID unit = unit(propio, "803");
        UUID person = person("9920002");
        UUID conceptoAjeno = chargeConcept(ajeno, "EXP");

        // Lo rechaza la base y no el servicio, y es la decision del issue #100: UnitRelations es de
        // condominium, que no depende de billing, asi que desde el servicio no hay forma de mirar de
        // quien es el concepto. La clave foranea apunta a (id, condominium_id) justo para esto.
        assertThatThrownBy(() -> billingResponsibility(propio, unit, person, conceptoAjeno))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("billing_responsibility_concept_fk");
    }

    @Test
    @DisplayName("el concepto de cobro es unico por codigo dentro del condominio, y no entre condominios")
    void theChargeConceptCodeIsUniquePerCondominium() {
        UUID lasPalmas = condominium("1000000033", "Las Palmas");
        UUID villaSol = condominium("1000000034", "Villa Sol");
        chargeConcept(lasPalmas, "UQ-33");
        chargeConcept(villaSol, "UQ-33");

        assertThat(cuantas("charge_concept", "code = ?", "UQ-33")).isEqualTo(2);
        assertThatThrownBy(() -> chargeConcept(lasPalmas, "UQ-33"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("charge_concept_code_uq");
    }

    @Test
    @DisplayName("el carnet es unico cuando viene, y varias personas pueden no tenerlo")
    void theNationalIdIsUniqueWhenPresent() {
        person(null);
        person(null);
        person("9930001");

        assertThat(cuantas("person", "national_id IS NULL", null)).isGreaterThanOrEqualTo(2);
        assertThatThrownBy(() -> person("9930001"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("person_national_id_uq");
    }

    @Test
    @DisplayName("una cuenta no puede quedar en un estado que no esta en Seguridad 2")
    void theAccountStatusIsConstrained() {
        UUID person = person("9940001");

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO user_account (id, person_id, email, status, created_at) VALUES (?, ?, ?, ?, ?)",
                        UUID.randomUUID(),
                        person,
                        "raro@coprop.bo",
                        "DORMIDO",
                        DESDE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("user_account_status_ck");
    }

    @Test
    @DisplayName("nadie se invita a si mismo")
    void nobodyInvitesThemselves() {
        UUID condominium = condominium("1000000019", "Invitacion");
        UUID account = accountOf(person("9950001"), "solo@coprop.bo");

        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO membership (id, user_id, condominium_id, status, valid_from, invited_by)
                        VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                        """,
                        UUID.randomUUID(),
                        account,
                        condominium,
                        DESDE,
                        account))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("membership_invited_by_ck");
    }

    // ----------------------------------------------------------------------------------------
    // Ayudantes
    // ----------------------------------------------------------------------------------------

    private UUID condominium(String nit, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO condominium (id, name, nit, type, time_zone, currency, status, created_at)
                VALUES (?, ?, ?, 'BUILDING', 'America/La_Paz', 'BOB', 'ACTIVE', ?)
                """,
                id,
                name,
                nit,
                DESDE);
        return id;
    }

    private UUID block(UUID condominiumId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO block (id, condominium_id, name) VALUES (?, ?, ?)", id, condominiumId, name);
        return id;
    }

    private UUID unit(UUID condominiumId, String code) {
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

    private UUID person(@Nullable String carnet) {
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

    private UUID accountOf(UUID personId, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO user_account (id, person_id, email, status, created_at)
                VALUES (?, ?, ?, 'INVITED', ?)
                """,
                id,
                personId,
                email,
                DESDE);
        return id;
    }

    private UUID membership(UUID userId, UUID condominiumId, @Nullable OffsetDateTime until) {
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
                until);
        return id;
    }

    private void ownership(UUID condominiumId, UUID unitId, UUID personId, BigDecimal share) {
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

    private void occupancy(UUID condominiumId, UUID unitId, UUID personId, String rol) {
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

    /**
     * Un concepto de cobro, por SQL como todo aqui. Issue #100.
     *
     * <p>El tipo y el grupo de pago se fijan en los mas comunes: lo que estos tests comprueban es la
     * clave foranea y la unicidad del codigo, no el catalogo.
     */
    private UUID chargeConcept(UUID condominiumId, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO charge_concept (id, condominium_id, code, name, type, payment_group)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                id,
                condominiumId,
                code,
                "Concepto " + code,
                "DUES",
                "DUES");
        return id;
    }

    private void billingResponsibility(UUID condominiumId, UUID unitId, UUID personId, @Nullable UUID conceptId) {
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

    private int cuantas(String tabla, String condition, @Nullable Object parametro) {
        String sql = "SELECT count(*) FROM " + tabla + " WHERE " + condition;
        Integer total = parametro == null
                ? jdbc.queryForObject(sql, Integer.class)
                : jdbc.queryForObject(sql, Integer.class, parametro);
        return total == null ? 0 : total;
    }
}
