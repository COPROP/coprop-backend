package bo.coprop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.shared.BusinessRuleViolated;
import bo.coprop.shared.ResourceNotFound;
import bo.coprop.shared.StateConflict;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conceder y revocar el rol de plataforma. Issue <strong>#20</strong>.
 *
 * <p>Hasta este issue el rol solo lo abria el arranque, y solo cuando no habia ninguno --eso es
 * {@link PlatformBootstrapTests}--: con eso la plataforma podia empezar pero no crecer, y el
 * segundo superadministrador salia de un {@code INSERT} a mano. Aqui se prueba la operacion.
 *
 * <p>Las dos reglas que mas importan son simetricas y las dos son de recuperacion, no de permisos:
 * <strong>nadie se lo concede a si mismo</strong> --un superadministrador que pudiera no necesitaria
 * que nadie se lo diera-- y <strong>nadie se lo revoca a si mismo</strong>, porque quien lo hiciera
 * se quedaria sin la unica pantalla desde la que podria devolverselo.
 */
@Import({TestcontainersConfiguration.class, AccessDoubles.class, PlatformAdminsTests.RelojFijo.class})
@ApplicationModuleTest(BootstrapMode.DIRECT_DEPENDENCIES)
@Transactional
class PlatformAdminsTests {

    private static final Instant AHORA = Instant.parse("2026-10-15T12:00:00Z");

    private final People personas;
    private final Identities identidades;
    private final AccessDoubles.SesionesDePrueba sesiones;
    private final AccessDoubles.ProveedorDeIdentidadDePrueba proveedor;

    PlatformAdminsTests(
            @Autowired People personas,
            @Autowired Identities identidades,
            @Autowired AccessDoubles.SesionesDePrueba sesiones,
            @Autowired AccessDoubles.ProveedorDeIdentidadDePrueba proveedor) {
        this.personas = personas;
        this.identidades = identidades;
        this.sesiones = sesiones;
        this.proveedor = proveedor;
    }

    /** Los dobles son del contexto y el contexto se comparte: lo mismo que en MembershipsTests. */
    @BeforeEach
    void dejarLosDoblesLimpios() {
        sesiones.reiniciar();
        proveedor.reiniciar();
    }

    @Test
    @DisplayName("se le concede el rol a otra cuenta, y queda quien se lo concedio")
    void seConcedeAOtraCuenta() {
        UUID quienConcede = cuentaDe("concede@coprop.bo");
        UUID quienRecibe = cuentaDe("recibe@coprop.bo");

        PlatformAdminView concedido = identidades.grantPlatformAdmin(quienRecibe, quienConcede.toString());

        assertThat(concedido).isEqualTo(new PlatformAdminView(quienRecibe, quienConcede, AHORA));
        assertThat(identidades.esSuperadministrador(quienRecibe)).isTrue();
        // El listado es lo que hace posible revocar: sin el habria que conocer de antemano el
        // identificador de la cuenta.
        assertThat(identidades.platformAdmins()).contains(concedido);
    }

    @Test
    @DisplayName("nadie se concede a si mismo el rol de plataforma")
    void nadieSeLoConcedeASiMismo() {
        UUID cuenta = cuentaDe("solo@coprop.bo");

        // Lo impide tambien el CHECK de la migracion V7; aqui se comprueba antes para poder
        // explicarlo, porque un fallo de integridad saldria como un 500 sin decir nada util.
        assertThatThrownBy(() -> identidades.grantPlatformAdmin(cuenta, cuenta.toString()))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("si mismo");
    }

    @Test
    @DisplayName("concederselo a quien ya lo tiene es un conflicto de estado, no un alta repetida")
    void aQuienYaLoTieneEsConflicto() {
        UUID quienConcede = cuentaDe("concede2@coprop.bo");
        UUID quienRecibe = cuentaDe("recibe2@coprop.bo");
        identidades.grantPlatformAdmin(quienRecibe, quienConcede.toString());

        // 409 y no 201: la fila existe y no hay grados. Un 201 silencioso cambiaria granted_by y
        // granted_at, y con eso se perderia quien se lo dio de verdad.
        assertThatThrownBy(() -> identidades.grantPlatformAdmin(quienRecibe, quienConcede.toString()))
                .isInstanceOf(StateConflict.class)
                .hasMessageContaining("ya administra la plataforma");
    }

    @Test
    @DisplayName("no se le concede a una cuenta que no existe")
    void noSeConcedeAUnaCuentaQueNoExiste() {
        UUID quienConcede = cuentaDe("concede3@coprop.bo");

        assertThatThrownBy(() -> identidades.grantPlatformAdmin(UUID.randomUUID(), quienConcede.toString()))
                .isInstanceOf(ResourceNotFound.class);
    }

    @Test
    @DisplayName("revocar le quita el rol y le cierra las sesiones, las dos")
    void revocarQuitaElRolYCierraLasSesiones() {
        UUID quienRevoca = cuentaDe("revoca@coprop.bo");
        UUID quienLoPierde = cuentaDe("pierde@coprop.bo");
        identidades.grantPlatformAdmin(quienLoPierde, quienRevoca.toString());

        identidades.revokePlatformAdmin(quienLoPierde, "dejo la empresa", quienRevoca.toString());

        assertThat(identidades.esSuperadministrador(quienLoPierde)).isFalse();
        // Seguridad 8 pide revocar las sesiones al retirar un rol administrativo, y no hay rol mas
        // administrativo que este. Sin condicion ninguna: no hay membresias que contar.
        assertThat(sesiones.cerradas()).containsExactly(Set.of(quienLoPierde.toString()));
        assertThat(proveedor.aQuienSeLeCerraronSesiones()).containsExactly("pierde@coprop.bo");
    }

    @Test
    @DisplayName("revocarle el rol a quien no lo tiene responde como si no existiera")
    void aQuienNoLoTieneResponde404() {
        UUID quienRevoca = cuentaDe("revoca2@coprop.bo");
        UUID cualquiera = cuentaDe("cualquiera@coprop.bo");

        // 404 y no 422: quien administra la plataforma no es dato publico, asi que preguntar por una
        // cuenta que no lo administra tiene que responder lo mismo que preguntar por una que no
        // existe. Mismo criterio que el 404-no-403 del #17.
        assertThatThrownBy(() -> identidades.revokePlatformAdmin(cualquiera, "por si acaso", quienRevoca.toString()))
                .isInstanceOf(ResourceNotFound.class);
    }

    @Test
    @DisplayName("nadie se revoca a si mismo: se quedaria sin la pantalla para volver")
    void nadieSeLoRevocaASiMismo() {
        UUID quienConcede = cuentaDe("concede4@coprop.bo");
        UUID quienLoTiene = cuentaDe("tiene@coprop.bo");
        identidades.grantPlatformAdmin(quienLoTiene, quienConcede.toString());

        assertThatThrownBy(() -> identidades.revokePlatformAdmin(quienLoTiene, "me retiro", quienLoTiene.toString()))
                .isInstanceOf(BusinessRuleViolated.class)
                .hasMessageContaining("si mismo");
    }

    @Test
    @DisplayName("la concesion y la revocacion publican sus eventos, los dos sin condominio")
    void losDosEventosVanSinCondominio(PublishedEvents eventos) {
        UUID quienConcede = cuentaDe("concede5@coprop.bo");
        UUID elOtro = cuentaDe("elotro@coprop.bo");

        identidades.grantPlatformAdmin(elOtro, quienConcede.toString());
        identidades.revokePlatformAdmin(elOtro, "se equivocaron de cuenta", quienConcede.toString());

        assertThat(eventos.ofType(PlatformAdminGranted.class))
                .containsExactly(new PlatformAdminGranted(elOtro, quienConcede, AHORA));
        assertThat(eventos.ofType(PlatformAdminRevoked.class))
                .containsExactly(new PlatformAdminRevoked(
                        elOtro, "se equivocaron de cuenta", true, quienConcede.toString(), AHORA));

        // El alcance de este rol es la plataforma, asi que sus dos eventos no cuelgan de ningun
        // condominio. Es lo que hace que no salgan en la bitacora de un edificio.
        assertThat(eventos.ofType(PlatformAdminGranted.class))
                .allSatisfy(evento -> assertThat(evento.condominiumId()).isNull());
        assertThat(eventos.ofType(PlatformAdminRevoked.class))
                .allSatisfy(evento -> assertThat(evento.condominiumId()).isNull());
    }

    @Test
    @DisplayName("el actor del evento de concesion es quien lo concedio, no el arranque")
    void elActorEsQuienLoConcedio() {
        UUID quienConcede = cuentaDe("concede6@coprop.bo");
        UUID quienRecibe = cuentaDe("recibe6@coprop.bo");

        identidades.grantPlatformAdmin(quienRecibe, quienConcede.toString());

        // Hasta este issue el evento siempre decia "arranque", porque el rol no tenia operacion:
        // desde aqui, grantedBy es una cuenta de verdad y el actor de la bitacora es esa.
        assertThat(new PlatformAdminGranted(quienRecibe, quienConcede, AHORA).actor())
                .isEqualTo(quienConcede.toString());
    }

    private UUID cuentaDe(String correo) {
        PersonView persona = personas.register(new NewPerson("Emma", "Tola", null), "admin");
        return personas.openAccount(persona.id(), correo, null, "admin").id();
    }

    /** Reloj parado: asi los eventos se pueden comparar contra una fecha concreta. */
    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {

        @Bean
        @Primary
        Clock relojDePrueba() {
            return Clock.fixed(AHORA, ZoneOffset.UTC);
        }
    }
}
