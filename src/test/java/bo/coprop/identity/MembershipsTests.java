package bo.coprop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.shared.ConflictoDeEstado;
import bo.coprop.shared.RecursoNoEncontrado;
import bo.coprop.shared.ReglaDeNegocioViolada;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * El segundo criterio de aceptacion del issue #11: una membresia vigente por cuenta y condominio.
 *
 * <p>El condominio se inserta con SQL y no por su modulo a proposito: {@code identity} no depende
 * de {@code condominium} (regla 2 de ARCHITECTURE.md), asi que desde aqui no hay forma de crear
 * uno, y este test arranca solo este modulo. Que haga falta ese apaño es la prueba de que el
 * limite esta puesto.
 */
@Import({TestcontainersConfiguration.class, DoblesDeAcceso.class, MembershipsTests.RelojFijo.class})
@ApplicationModuleTest(BootstrapMode.DIRECT_DEPENDENCIES)
@Transactional
class MembershipsTests {

    private static final Instant AHORA = Instant.parse("2026-10-12T12:00:00Z");

    private final JdbcTemplate jdbc;
    private final People personas;
    private final Memberships membresias;
    private final DoblesDeAcceso.SesionesDePrueba sesiones;
    private final DoblesDeAcceso.ProveedorDeIdentidadDePrueba proveedor;

    MembershipsTests(
            @Autowired DataSource dataSource,
            @Autowired People personas,
            @Autowired Memberships membresias,
            @Autowired DoblesDeAcceso.SesionesDePrueba sesiones,
            @Autowired DoblesDeAcceso.ProveedorDeIdentidadDePrueba proveedor) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.personas = personas;
        this.membresias = membresias;
        this.sesiones = sesiones;
        this.proveedor = proveedor;
    }

    /**
     * Los dobles son del contexto, no del test, y el contexto se comparte entre todos.
     *
     * <p>Sin esto, lo que un test anotara lo veria el siguiente y las comprobaciones de "no se
     * cerro ninguna sesion" pasarian o fallarian segun el orden en que JUnit corriera la clase.
     */
    @BeforeEach
    void dejarLosDoblesLimpios() {
        sesiones.reiniciar();
        proveedor.reiniciar();
    }

    @Test
    @DisplayName("una membresia se concede activa y con sus roles")
    void seConcedeActivaYConSusRoles() {
        UUID condominio = condominio("2000000001");
        UUID cuenta = cuentaDe("uno@coprop.bo", "7000001");

        MembershipView creada = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        assertThat(creada.accountId()).isEqualTo(cuenta);
        assertThat(creada.condominiumId()).isEqualTo(condominio);
        assertThat(creada.status()).isEqualTo(MembershipStatus.ACTIVA);
        assertThat(creada.roles()).containsExactly(MembershipRole.OWNER);

        assertThat(membresias.find(condominio, creada.id())).isEqualTo(creada);
    }

    @Test
    @DisplayName("no se pueden crear dos membresias vigentes de la misma cuenta en el mismo condominio")
    void noDosMembresiasVigentesEnElMismoCondominio() {
        UUID condominio = condominio("2000000002");
        UUID cuenta = cuentaDe("dos@coprop.bo", "7000002");
        membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        assertThatThrownBy(() -> membresias.grant(condominio, cuenta, Set.of(MembershipRole.TENANT), null, "admin"))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("ya tiene membresia vigente");
    }

    @Test
    @DisplayName("la misma cuenta si puede tener membresia en dos condominios")
    void laMismaCuentaEnDosCondominios() {
        UUID uno = condominio("2000000003");
        UUID otro = condominio("2000000004");
        UUID cuenta = cuentaDe("tres@coprop.bo", "7000003");

        // RF-03 al nivel de la membresia: propietaria en uno, inquilina en otro.
        membresias.grant(uno, cuenta, Set.of(MembershipRole.OWNER), null, "admin");
        membresias.grant(otro, cuenta, Set.of(MembershipRole.TENANT), null, "admin");

        assertThat(membresias.of(uno, cuenta)).isNotNull();
        assertThat(membresias.of(otro, cuenta)).isNotNull();
    }

    @Test
    @DisplayName("una membresia puede llevar varios roles: propietario que tambien es directivo")
    void unaMembresiaLlevaVariosRoles() {
        UUID condominio = condominio("2000000005");
        UUID cuenta = cuentaDe("cuatro@coprop.bo", "7000004");

        MembershipView creada =
                membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER, MembershipRole.BOARD), null, "admin");

        assertThat(creada.roles()).containsExactlyInAnyOrder(MembershipRole.OWNER, MembershipRole.BOARD);
    }

    @Test
    @DisplayName("una membresia sin roles se rechaza: no autorizaria nada")
    void unaMembresiaSinRolesSeRechaza() {
        UUID condominio = condominio("2000000006");
        UUID cuenta = cuentaDe("cinco@coprop.bo", "7000005");

        assertThatThrownBy(() -> membresias.grant(condominio, cuenta, Set.of(), null, "admin"))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("al menos un rol");
    }

    @Test
    @DisplayName("nadie se invita a si mismo")
    void nadieSeInvitaASiMismo() {
        UUID condominio = condominio("2000000007");
        UUID cuenta = cuentaDe("seis@coprop.bo", "7000006");

        assertThatThrownBy(() -> membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), cuenta, "admin"))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("si mismo");
    }

    @Test
    @DisplayName("no se concede membresia a una cuenta que no existe")
    void noSeConcedeACuentaInexistente() {
        UUID condominio = condominio("2000000008");

        assertThatThrownBy(() ->
                        membresias.grant(condominio, UUID.randomUUID(), Set.of(MembershipRole.OWNER), null, "admin"))
                .isInstanceOf(RecursoNoEncontrado.class);
    }

    @Test
    @DisplayName("la membresia de otro condominio no se encuentra: 404 y no 403")
    void laMembresiaDeOtroCondominioNoSeEncuentra() {
        UUID propio = condominio("2000000009");
        UUID ajeno = condominio("2000000010");
        UUID cuenta = cuentaDe("siete@coprop.bo", "7000007");
        MembershipView enElAjeno = membresias.grant(ajeno, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        assertThatThrownBy(() -> membresias.find(propio, enElAjeno.id())).isInstanceOf(RecursoNoEncontrado.class);
    }

    @Test
    @DisplayName("sin membresia vigente, consultarla devuelve nulo en vez de fallar")
    void sinMembresiaDevuelveNulo() {
        UUID condominio = condominio("2000000011");
        UUID cuenta = cuentaDe("ocho@coprop.bo", "7000008");

        // Es lo que leera el filtro del issue #17: la ausencia de membresia es una respuesta, no
        // un error.
        assertThat(membresias.of(condominio, cuenta)).isNull();
    }

    @Test
    @DisplayName("se listan solo las membresias vigentes del condominio")
    void seListanLasVigentesDelCondominio() {
        UUID condominio = condominio("2000000012");
        UUID otro = condominio("2000000013");
        UUID una = cuentaDe("nueve@coprop.bo", "7000009");
        UUID otraCuenta = cuentaDe("diez@coprop.bo", "7000010");
        membresias.grant(condominio, una, Set.of(MembershipRole.OWNER), null, "admin");
        membresias.grant(condominio, otraCuenta, Set.of(MembershipRole.TENANT), null, "admin");
        membresias.grant(otro, una, Set.of(MembershipRole.OWNER), null, "admin");

        assertThat(membresias.ofCondominium(condominio))
                .extracting(MembershipView::accountId)
                .containsExactlyInAnyOrder(una, otraCuenta);
    }

    @Test
    @DisplayName("conceder una membresia publica su evento con los roles de ese momento")
    void concederPublicaSuEvento(PublishedEvents eventos) {
        UUID condominio = condominio("2000000014");
        UUID cuenta = cuentaDe("once@coprop.bo", "7000011");

        MembershipView creada = membresias.grant(condominio, cuenta, Set.of(MembershipRole.CONDO_ADMIN), null, "emma");

        assertThat(eventos.ofType(MembershipGranted.class))
                .containsExactly(new MembershipGranted(
                        condominio, creada.id(), cuenta, Set.of(MembershipRole.CONDO_ADMIN), "emma", AHORA));
    }

    // --- La baja, la suspension y la reactivacion: issue #19 ---

    @Test
    @DisplayName("la baja cierra la fila y no la borra: lo historico se conserva")
    void laBajaCierraLaFilaYNoLaBorra() {
        UUID condominio = condominio("2000000015");
        UUID cuenta = cuentaDe("doce@coprop.bo", "7000012");
        MembershipView concedida = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        MembershipView dada = membresias.revoke(condominio, concedida.id(), "se mudo de edificio", "admin");

        assertThat(dada.validTo()).isEqualTo(AHORA);
        assertThat(dada.daAcceso()).isFalse();
        // El estado no se toca: la fila sigue diciendo como estaba cuando se la dio de baja.
        assertThat(dada.status()).isEqualTo(MembershipStatus.ACTIVA);
        // Ya no es vigente, pero la fila esta: el segundo criterio del issue.
        assertThat(membresias.ofCondominium(condominio)).isEmpty();
        assertThat(membresias.allOfCondominium(condominio))
                .extracting(MembershipView::id)
                .containsExactly(concedida.id());
        assertThat(membresias.of(condominio, cuenta)).isNull();
    }

    @Test
    @DisplayName("tras la baja se puede volver a entrar: el indice parcial lo deja pasar")
    void trasLaBajaSePuedeVolverAEntrar() {
        UUID condominio = condominio("2000000016");
        UUID cuenta = cuentaDe("trece@coprop.bo", "7000013");
        MembershipView primera = membresias.grant(condominio, cuenta, Set.of(MembershipRole.TENANT), null, "admin");
        membresias.revoke(condominio, primera.id(), "fin de contrato", "admin");

        MembershipView segunda = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        assertThat(segunda.id()).isNotEqualTo(primera.id());
        assertThat(membresias.of(condominio, cuenta)).isNotNull();
        // Las dos filas, la de antes y la de ahora. Por eso el padron completo puede repetir cuenta.
        assertThat(membresias.allOfCondominium(condominio)).hasSize(2);
    }

    @Test
    @DisplayName("dar de baja dos veces es un conflicto de estado, no un recurso que no existe")
    void laBajaDosVecesEsConflicto() {
        UUID condominio = condominio("2000000017");
        UUID cuenta = cuentaDe("catorce@coprop.bo", "7000014");
        MembershipView concedida = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");
        membresias.revoke(condominio, concedida.id(), "se mudo", "admin");

        assertThatThrownBy(() -> membresias.revoke(condominio, concedida.id(), "otra vez", "admin"))
                .isInstanceOf(ConflictoDeEstado.class)
                .hasMessageContaining("dada de baja");
    }

    @Test
    @DisplayName("nadie se da de baja a si mismo: se quedaria sin la pantalla para volver")
    void nadieSeDaDeBajaASiMismo() {
        UUID condominio = condominio("2000000018");
        UUID cuenta = cuentaDe("quince@coprop.bo", "7000015");
        MembershipView suya = membresias.grant(condominio, cuenta, Set.of(MembershipRole.CONDO_ADMIN), null, "admin");

        assertThatThrownBy(() -> membresias.revoke(condominio, suya.id(), "me voy", cuenta.toString()))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("si mismo");
    }

    @Test
    @DisplayName("un condominio no se queda sin su ultimo administrador")
    void noSeDaDeBajaAlUltimoAdministrador() {
        UUID condominio = condominio("2000000019");
        UUID admin = cuentaDe("dieciseis@coprop.bo", "7000016");
        MembershipView unica = membresias.grant(condominio, admin, Set.of(MembershipRole.CONDO_ADMIN), null, "emma");

        assertThatThrownBy(() -> membresias.revoke(condominio, unica.id(), "renuncio", "emma"))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("ultima membresia con CONDO_ADMIN");

        assertThatThrownBy(() -> membresias.suspend(condominio, unica.id(), "de viaje", "emma"))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("ultima membresia con CONDO_ADMIN");
    }

    @Test
    @DisplayName("con dos administradores si se puede dar de baja a uno")
    void conDosAdministradoresSiSePuedeDarDeBajaAUno() {
        UUID condominio = condominio("2000000020");
        UUID uno = cuentaDe("diecisiete@coprop.bo", "7000017");
        UUID otro = cuentaDe("dieciocho@coprop.bo", "7000018");
        MembershipView delUno = membresias.grant(condominio, uno, Set.of(MembershipRole.CONDO_ADMIN), null, "emma");
        membresias.grant(condominio, otro, Set.of(MembershipRole.CONDO_ADMIN), null, "emma");

        MembershipView dada = membresias.revoke(condominio, delUno.id(), "cambio de administracion", "emma");

        assertThat(dada.validTo()).isEqualTo(AHORA);
    }

    @Test
    @DisplayName("una membresia suspendida sigue abierta y deja de dar acceso")
    void laSuspensionNoCierraLaFila() {
        UUID condominio = condominio("2000000021");
        UUID cuenta = cuentaDe("diecinueve@coprop.bo", "7000019");
        MembershipView concedida = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        MembershipView suspendida = membresias.suspend(condominio, concedida.id(), "cuota impaga", "admin");

        assertThat(suspendida.status()).isEqualTo(MembershipStatus.SUSPENDIDA);
        assertThat(suspendida.validTo()).isNull();
        assertThat(suspendida.daAcceso()).isFalse();
        // Sigue siendo del condominio: es lo que la distingue de una baja. El filtro de aislamiento
        // es quien la rechaza, mirando el estado.
        assertThat(membresias.ofCondominium(condominio)).hasSize(1);
        assertThat(membresias.of(condominio, cuenta)).isNotNull();
    }

    @Test
    @DisplayName("la reactivacion devuelve el acceso y los roles siguen intactos")
    void laReactivacionDevuelveElAcceso() {
        UUID condominio = condominio("2000000022");
        UUID cuenta = cuentaDe("veinte@coprop.bo", "7000020");
        MembershipView concedida =
                membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER, MembershipRole.BOARD), null, "admin");
        membresias.suspend(condominio, concedida.id(), "cuota impaga", "admin");

        MembershipView reactivada = membresias.reinstate(condominio, concedida.id(), "pago al dia", "admin");

        assertThat(reactivada.daAcceso()).isTrue();
        assertThat(reactivada.roles()).containsExactlyInAnyOrder(MembershipRole.OWNER, MembershipRole.BOARD);
    }

    @Test
    @DisplayName("no se suspende dos veces ni se reactiva algo que no esta suspendido")
    void lasTransicionesImposiblesSonConflictos() {
        UUID condominio = condominio("2000000023");
        UUID cuenta = cuentaDe("veintiuno@coprop.bo", "7000021");
        MembershipView concedida = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        assertThatThrownBy(() -> membresias.reinstate(condominio, concedida.id(), "por si acaso", "admin"))
                .isInstanceOf(ConflictoDeEstado.class)
                .hasMessageContaining("no esta suspendida");

        membresias.suspend(condominio, concedida.id(), "cuota impaga", "admin");

        assertThatThrownBy(() -> membresias.suspend(condominio, concedida.id(), "otra vez", "admin"))
                .isInstanceOf(ConflictoDeEstado.class)
                .hasMessageContaining("ya esta suspendida");
    }

    @Test
    @DisplayName("una baja no se deshace: una membresia cerrada no se reactiva")
    void unaBajaNoSeReactiva() {
        UUID condominio = condominio("2000000024");
        UUID cuenta = cuentaDe("veintidos@coprop.bo", "7000022");
        MembershipView concedida = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");
        membresias.revoke(condominio, concedida.id(), "se mudo", "admin");

        assertThatThrownBy(() -> membresias.reinstate(condominio, concedida.id(), "que vuelva", "admin"))
                .isInstanceOf(ConflictoDeEstado.class)
                .hasMessageContaining("membresia nueva");
    }

    @Test
    @DisplayName("la membresia de otro condominio no se da de baja desde aqui: 404 y no 403")
    void noSeDaDeBajaLaDeOtroCondominio() {
        UUID propio = condominio("2000000025");
        UUID ajeno = condominio("2000000026");
        UUID cuenta = cuentaDe("veintitres@coprop.bo", "7000023");
        MembershipView enElAjeno = membresias.grant(ajeno, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        assertThatThrownBy(() -> membresias.revoke(propio, enElAjeno.id(), "no es mia", "admin"))
                .isInstanceOf(RecursoNoEncontrado.class);
    }

    @Test
    @DisplayName("la baja de la ultima membresia cierra las sesiones de esa cuenta")
    void laBajaDeLaUltimaCierraLasSesiones() {
        UUID condominio = condominio("2000000027");
        UUID cuenta = cuentaDe("veinticuatro@coprop.bo", "7000024");
        MembershipView unica = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        membresias.revoke(condominio, unica.id(), "vendio su departamento", "admin");

        // La cuenta nunca entro, asi que no tiene sujeto del proveedor y el unico nombre con el que
        // puede haber sesion es su identificador.
        assertThat(sesiones.cerradas()).containsExactly(Set.of(cuenta.toString()));
        assertThat(proveedor.aQuienSeLeCerraronSesiones()).containsExactly("veinticuatro@coprop.bo");
    }

    @Test
    @DisplayName("una baja no cierra sesiones si le queda otro condominio: la sesion no es de uno")
    void laBajaNoCierraSesionesSiLeQuedaOtroCondominio() {
        UUID uno = condominio("2000000028");
        UUID otro = condominio("2000000029");
        UUID cuenta = cuentaDe("veinticinco@coprop.bo", "7000025");
        MembershipView enUno = membresias.grant(uno, cuenta, Set.of(MembershipRole.OWNER), null, "admin");
        membresias.grant(otro, cuenta, Set.of(MembershipRole.TENANT), null, "admin");

        membresias.revoke(uno, enUno.id(), "vendio ese departamento", "admin");

        // Seguridad 6.3: la baja afecta solo a ese condominio. Cerrarle la sesion lo echaria del otro.
        assertThat(sesiones.cerradas()).isEmpty();
        assertThat(proveedor.aQuienSeLeCerraronSesiones()).isEmpty();
    }

    @Test
    @DisplayName("una suspension que la deja sin condominios tambien le cierra las sesiones")
    void laSuspensionDeLaUltimaCierraLasSesiones() {
        UUID condominio = condominio("2000000030");
        UUID cuenta = cuentaDe("veintiseis@coprop.bo", "7000026");
        MembershipView unica = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        membresias.suspend(condominio, unica.id(), "cuota impaga", "admin");

        assertThat(sesiones.cerradas()).containsExactly(Set.of(cuenta.toString()));
    }

    @Test
    @DisplayName("un proveedor de identidad caido NO deshace la baja")
    void unProveedorCaidoNoDeshaceLaBaja() {
        UUID condominio = condominio("2000000031");
        UUID cuenta = cuentaDe("veintisiete@coprop.bo", "7000027");
        MembershipView unica = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");
        proveedor.queFalle();

        MembershipView dada = membresias.revoke(condominio, unica.id(), "se mudo", "admin");

        // Es la decision contraria a la del correo de la invitacion (#18), y por eso se prueba: ahi
        // el correo era la operacion, y aqui el acceso ya lo corta el filtro en la siguiente
        // peticion. Deshacer la baja dejaria a la persona dentro del condominio.
        assertThat(dada.validTo()).isEqualTo(AHORA);
        assertThat(membresias.of(condominio, cuenta)).isNull();
    }

    @Test
    @DisplayName("la baja publica su evento con el motivo, los roles de ese momento y si se cerraron sesiones")
    void laBajaPublicaSuEvento(PublishedEvents eventos) {
        UUID condominio = condominio("2000000032");
        UUID cuenta = cuentaDe("veintiocho@coprop.bo", "7000028");
        MembershipView unica = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "emma");

        membresias.revoke(condominio, unica.id(), "vendio su departamento", "emma");

        assertThat(eventos.ofType(MembershipRevoked.class))
                .containsExactly(new MembershipRevoked(
                        condominio,
                        unica.id(),
                        cuenta,
                        Set.of(MembershipRole.OWNER),
                        "vendio su departamento",
                        true,
                        "emma",
                        AHORA));
    }

    @Test
    @DisplayName("la suspension y la reactivacion publican eventos distintos")
    void laSuspensionYLaReactivacionPublicanEventosDistintos(PublishedEvents eventos) {
        UUID condominio = condominio("2000000033");
        UUID cuenta = cuentaDe("veintinueve@coprop.bo", "7000029");
        MembershipView unica = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "emma");

        membresias.suspend(condominio, unica.id(), "cuota impaga", "emma");
        membresias.reinstate(condominio, unica.id(), "pago al dia", "emma");

        assertThat(eventos.ofType(MembershipSuspended.class))
                .containsExactly(
                        new MembershipSuspended(condominio, unica.id(), cuenta, "cuota impaga", true, "emma", AHORA));
        assertThat(eventos.ofType(MembershipReinstated.class))
                .containsExactly(
                        new MembershipReinstated(condominio, unica.id(), cuenta, "pago al dia", "emma", AHORA));
    }

    // --- El cambio de roles (issue #20) -----------------------------------------------------

    @Test
    @DisplayName("el cambio sustituye los roles por los que llegan, sin cerrar la membresia")
    void elCambioSustituyeLosRoles() {
        UUID condominio = condominio("2000000034");
        UUID cuenta = cuentaDe("treinta@coprop.bo", "7000030");
        MembershipView suya = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        MembershipView cambiada = membresias.changeRoles(
                condominio,
                suya.id(),
                Set.of(MembershipRole.BOARD, MembershipRole.TENANT),
                "entro al directorio",
                "admin");

        assertThat(cambiada.roles()).containsExactlyInAnyOrder(MembershipRole.BOARD, MembershipRole.TENANT);
        // La fila es la MISMA y sigue abierta: un cambio de rol no es una baja. Si cerrara y abriera
        // otra, el padron del #19 mostraria una baja que nadie dio.
        assertThat(cambiada.id()).isEqualTo(suya.id());
        assertThat(cambiada.validTo()).isNull();
        assertThat(cambiada.validFrom()).isEqualTo(suya.validFrom());
        assertThat(membresias.of(condominio, cuenta)).isEqualTo(cambiada);
    }

    @Test
    @DisplayName("repetir los roles que ya hay no es un error y no publica ningun evento")
    void repetirLosMismosRolesNoEsUnCambio(PublishedEvents eventos) {
        UUID condominio = condominio("2000000035");
        UUID cuenta = cuentaDe("treintayuno@coprop.bo", "7000031");
        MembershipView suya = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        MembershipView igual =
                membresias.changeRoles(condominio, suya.id(), Set.of(MembershipRole.OWNER), "sin cambios", "admin");

        // Un PUT es idempotente, asi que repetirlo no puede fallar; pero publicar el evento dejaria
        // en la bitacora una fila diciendo que alguien cambio algo cuando no cambio nada.
        assertThat(igual.roles()).containsExactly(MembershipRole.OWNER);
        assertThat(eventos.ofType(MembershipRolesChanged.class)).isEmpty();
    }

    @Test
    @DisplayName("no se le quita CONDO_ADMIN al ultimo administrador del condominio")
    void noSeQuitaElUltimoAdministrador() {
        UUID condominio = condominio("2000000036");
        UUID cuenta = cuentaDe("treintaydos@coprop.bo", "7000032");
        MembershipView unica = membresias.grant(condominio, cuenta, Set.of(MembershipRole.CONDO_ADMIN), null, "admin");

        // Misma regla que la baja y por lo mismo: el edificio quedaria sin nadie que pueda invitar
        // ni repartir roles, y recuperarlo exigiria al superadministrador.
        assertThatThrownBy(() -> membresias.changeRoles(
                        condominio, unica.id(), Set.of(MembershipRole.OWNER), "ya no administra", "admin"))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("ultima membresia con CONDO_ADMIN");
    }

    @Test
    @DisplayName("si queda otro administrador, quitarle CONDO_ADMIN si se puede")
    void conOtroAdministradorSiSePuede() {
        UUID condominio = condominio("2000000037");
        UUID unaCuenta = cuentaDe("treintaytres@coprop.bo", "7000033");
        UUID otraCuenta = cuentaDe("treintaycuatro@coprop.bo", "7000034");
        MembershipView una = membresias.grant(condominio, unaCuenta, Set.of(MembershipRole.CONDO_ADMIN), null, "admin");
        membresias.grant(condominio, otraCuenta, Set.of(MembershipRole.CONDO_ADMIN), null, "admin");

        MembershipView sinAdministrar =
                membresias.changeRoles(condominio, una.id(), Set.of(MembershipRole.OWNER), "paso el cargo", "admin");

        assertThat(sinAdministrar.roles()).containsExactly(MembershipRole.OWNER);
    }

    @Test
    @DisplayName("nadie se cambia los roles a si mismo")
    void nadieSeCambiaLosRolesASiMismo() {
        UUID condominio = condominio("2000000038");
        UUID cuenta = cuentaDe("treintaycinco@coprop.bo", "7000035");
        MembershipView suya = membresias.grant(
                condominio, cuenta, Set.of(MembershipRole.CONDO_ADMIN, MembershipRole.FINANCE_ADMIN), null, "admin");

        // Es la regla de recuperacion de la baja, heredada: quien se quitara CONDO_ADMIN se dejaria
        // fuera de la unica pantalla desde la que podria devolverselo. El precio conocido es que
        // para quitarse FINANCE_ADMIN --y que haya separacion de funciones-- hay que pedirselo a
        // otro administrador.
        assertThatThrownBy(() -> membresias.changeRoles(
                        condominio,
                        suya.id(),
                        Set.of(MembershipRole.CONDO_ADMIN),
                        "me quito el dinero",
                        cuenta.toString()))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("si mismo");
    }

    @Test
    @DisplayName("el cambio no es la puerta de atras de PLATFORM_ADMIN, ni deja una membresia sin roles")
    void elCambioNoAdmiteNiPlataformaNiVacio() {
        UUID condominio = condominio("2000000039");
        UUID cuenta = cuentaDe("treintayseis@coprop.bo", "7000036");
        MembershipView suya = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        // Las dos reglas son las del alta, compartidas a proposito: un alta que rechaza
        // PLATFORM_ADMIN y un cambio que lo admitiera dejarian entrar por la segunda puerta justo lo
        // que la primera prohibe.
        assertThatThrownBy(() -> membresias.changeRoles(
                        condominio, suya.id(), Set.of(MembershipRole.PLATFORM_ADMIN), "que administre todo", "admin"))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("PLATFORM_ADMIN");

        assertThatThrownBy(() -> membresias.changeRoles(condominio, suya.id(), Set.of(), "sin nada", "admin"))
                .isInstanceOf(ReglaDeNegocioViolada.class)
                .hasMessageContaining("al menos un rol");
    }

    @Test
    @DisplayName("a una membresia dada de baja no se le cambian los roles")
    void unaMembresiaCerradaNoCambiaDeRoles() {
        UUID condominio = condominio("2000000040");
        UUID cuenta = cuentaDe("treintaysiete@coprop.bo", "7000037");
        MembershipView suya = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");
        membresias.revoke(condominio, suya.id(), "se mudo", "admin");

        // Conflicto de estado y no 404: la fila existe y se puede leer. Lo que se hace para que
        // vuelva es conceder una membresia nueva.
        assertThatThrownBy(() ->
                        membresias.changeRoles(condominio, suya.id(), Set.of(MembershipRole.TENANT), "volvio", "admin"))
                .isInstanceOf(ConflictoDeEstado.class);
    }

    @Test
    @DisplayName("la membresia de otro condominio no cambia de roles desde aqui: 404 y no 403")
    void laDeOtroCondominioNoCambia() {
        UUID propio = condominio("2000000041");
        UUID ajeno = condominio("2000000042");
        UUID cuenta = cuentaDe("treintayocho@coprop.bo", "7000038");
        MembershipView enElAjeno = membresias.grant(ajeno, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        assertThatThrownBy(() -> membresias.changeRoles(
                        propio, enElAjeno.id(), Set.of(MembershipRole.TENANT), "no es mia", "admin"))
                .isInstanceOf(RecursoNoEncontrado.class);
    }

    @Test
    @DisplayName("retirar un rol administrativo le cierra las sesiones, aunque siga en el condominio")
    void retirarUnRolAdministrativoCierraLasSesiones() {
        UUID condominio = condominio("2000000043");
        UUID cuenta = cuentaDe("treintaynueve@coprop.bo", "7000039");
        MembershipView suya = membresias.grant(
                condominio, cuenta, Set.of(MembershipRole.CONDO_ADMIN, MembershipRole.FINANCE_ADMIN), null, "admin");

        membresias.changeRoles(
                condominio, suya.id(), Set.of(MembershipRole.CONDO_ADMIN), "separacion de funciones", "admin");

        // Seguridad 8 pide revocar las sesiones al retirar un rol administrativo, y la regla es
        // distinta de la de la baja: aqui la cuenta SIGUE teniendo a donde entrar y se le cierran
        // igual. Lo que caduco no es el acceso, es el nivel de confianza de esa sesion.
        assertThat(sesiones.cerradas()).containsExactly(Set.of(cuenta.toString()));
        assertThat(proveedor.aQuienSeLeCerraronSesiones()).containsExactly("treintaynueve@coprop.bo");
    }

    @Test
    @DisplayName("anadir un rol administrativo no cierra ninguna sesion")
    void anadirUnRolNoCierraSesiones() {
        UUID condominio = condominio("2000000044");
        UUID cuenta = cuentaDe("cuarenta@coprop.bo", "7000040");
        MembershipView suya = membresias.grant(condominio, cuenta, Set.of(MembershipRole.CONDO_ADMIN), null, "admin");

        membresias.changeRoles(
                condominio,
                suya.id(),
                Set.of(MembershipRole.CONDO_ADMIN, MembershipRole.FINANCE_ADMIN),
                "tambien lleva el dinero",
                "admin");

        // Solo cuando se QUITA. Cerrarle la sesion a quien acaba de recibir un rol seria echarle de
        // la pantalla en la que esta trabajando sin que haya perdido nada.
        assertThat(sesiones.cerradas()).isEmpty();
    }

    @Test
    @DisplayName("cambiar entre roles que no son administrativos no cierra ninguna sesion")
    void cambiarEntreRolesNoAdministrativosNoCierraSesiones() {
        UUID condominio = condominio("2000000045");
        UUID cuenta = cuentaDe("cuarentayuno@coprop.bo", "7000041");
        MembershipView suya = membresias.grant(condominio, cuenta, Set.of(MembershipRole.OWNER), null, "admin");

        membresias.changeRoles(condominio, suya.id(), Set.of(MembershipRole.TENANT), "ahora alquila", "admin");

        assertThat(sesiones.cerradas()).isEmpty();
    }

    @Test
    @DisplayName("el cambio publica su evento con las dos mitades, el motivo y la concentracion")
    void elCambioPublicaSuEvento(PublishedEvents eventos) {
        UUID condominio = condominio("2000000046");
        UUID cuenta = cuentaDe("cuarentaydos@coprop.bo", "7000042");
        MembershipView suya = membresias.grant(condominio, cuenta, Set.of(MembershipRole.CONDO_ADMIN), null, "emma");

        membresias.changeRoles(
                condominio,
                suya.id(),
                Set.of(MembershipRole.CONDO_ADMIN, MembershipRole.FINANCE_ADMIN),
                "en este edificio administra una sola persona",
                "emma");

        // Las dos mitades van dentro porque lo que se pregunta despues no es que roles tiene --eso
        // lo dice la fila-- sino cual le quitaron y quien. Y la concentracion viaja como dato: 10.1
        // la permite a condicion de que quede registrada.
        assertThat(eventos.ofType(MembershipRolesChanged.class))
                .containsExactly(new MembershipRolesChanged(
                        condominio,
                        suya.id(),
                        cuenta,
                        Set.of(MembershipRole.CONDO_ADMIN),
                        Set.of(MembershipRole.CONDO_ADMIN, MembershipRole.FINANCE_ADMIN),
                        "en este edificio administra una sola persona",
                        true,
                        false,
                        "emma",
                        AHORA));
    }

    /**
     * El condominio por SQL: este modulo no puede crear uno, y es justo lo que la regla 2 quiere.
     */
    private UUID condominio(String nit) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO condominium (id, name, nit, type, time_zone, currency, status, created_at)
                VALUES (?, 'Condominio de prueba', ?, 'EDIFICIO', 'America/La_Paz', 'BOB', 'ACTIVO', ?)
                """,
                id,
                nit,
                OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC));
        return id;
    }

    private UUID cuentaDe(String correo, String carnet) {
        PersonView persona = personas.register(new NewPerson("Emma", "Tola", carnet), "admin");
        return personas.openAccount(persona.id(), correo, null, "admin").id();
    }

    /** Reloj parado: asi el evento se puede comparar contra una fecha concreta. */
    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {

        @Bean
        @Primary
        Clock relojDePrueba() {
            return Clock.fixed(AHORA, ZoneOffset.UTC);
        }
    }
}
