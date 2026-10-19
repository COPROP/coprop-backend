package bo.coprop.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.condominium.CondominiumType;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import bo.coprop.condominium.NewUnit;
import bo.coprop.condominium.UnitRelations;
import bo.coprop.condominium.UnitType;
import bo.coprop.condominium.Units;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.InvitationDoubles;
import bo.coprop.identity.Invitations;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.Memberships;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.identity.PersonView;
import bo.coprop.shared.PagedResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * La bitacora del issue #21: que las acciones lleguen, que no se puedan tocar y que se puedan
 * buscar.
 *
 * <p><strong>No es {@code @Transactional}</strong>, y no puede serlo: el oyente corre
 * <em>despues</em> del commit. En un test transaccional ese commit no llega nunca, asi que no se
 * escribiria ni una fila y la suite entera pasaria sin comprobar nada.
 *
 * <p>Lo que si se puede, porque el oyente es sincrono, es <strong>leer la fila justo despues de la
 * accion</strong>, sin sondear ni dormir. Esa es la mitad practica de la decision que hay escrita en
 * el oyente: con un oyente asincrono, cada caso de aqui tendria que esperar a que apareciera.
 */
@Import({TestcontainersConfiguration.class, InvitationDoubles.class})
@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class AuditTrailTests {

    @Autowired
    private AuditTrail bitacora;

    @Autowired
    private Condominiums condominios;

    @Autowired
    private People personas;

    @Autowired
    private Memberships membresias;

    @Autowired
    private bo.coprop.identity.Identities identidades;

    @Autowired
    private Invitations invitaciones;

    @Autowired
    private Units unidades;

    @Autowired
    private UnitRelations relaciones;

    @Autowired
    private DataSource fuenteDeDatos;

    @Autowired
    private InvitationDoubles.RelojDePrueba reloj;

    @Autowired
    private InvitationDoubles.CorreoDePrueba buzon;

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    private UUID condominio;
    private UUID administradora;

    @BeforeEach
    void prepararUnCondominio() {
        condominio = condominios
                .register(
                        new NewCondominium(
                                "Auditoria " + UUID.randomUUID(),
                                nitNuevo(),
                                CondominiumType.BUILDING,
                                "America/La_Paz",
                                5,
                                10),
                        "admin")
                .id();
        administradora = cuentaNueva().id();
    }

    private static String nitNuevo() {
        return String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_0000L));
    }

    private AccountView cuentaNueva() {
        PersonView persona = personas.register(new NewPerson("Emma", "Tola", null), "admin");
        return personas.openAccount(persona.id(), "emma-" + UUID.randomUUID() + "@coprop.bo", null, "admin");
    }

    private List<AuditEntryView> delCondominio() {
        return bitacora.ofCondominium(condominio, null, null, null, null, 0, 100)
                .contenido();
    }

    /**
     * La bitacora de plataforma, la del issue #20.
     *
     * <p>Se piden muchas filas y se busca dentro en vez de comparar la lista: estos tests comparten
     * base, y la plataforma tiene una sola bitacora para todos --no hay condominio que los separe--
     * asi que lo que hay ahi lo escribieron tambien los demas casos.
     */
    private List<AuditEntryView> deLaPlataforma() {
        return bitacora.ofPlatform(null, null, null, null, 0, 200).contenido();
    }

    /**
     * La fila de esa accion en la bitacora del condominio.
     *
     * <p>Sin esperas: el oyente es sincrono, asi que para cuando la accion termino la fila ya esta.
     * Si no esta, el mensaje dice que si llego, que es lo que hace falta para entender por que.
     */
    private AuditEntryView laFilaDe(String action) {
        List<AuditEntryView> todas = delCondominio();
        return todas.stream()
                .filter(fila -> fila.action().equals(action))
                .findFirst()
                .orElseThrow(() -> new AssertionError("La bitacora no registro %s. Lo que si tiene: %s"
                        .formatted(
                                action,
                                todas.stream().map(AuditEntryView::action).toList())));
    }

    // --- Que las acciones lleguen -----------------------------------------------------------

    @Test
    @DisplayName("el alta de un condominio queda en la bitacora, con su nombre")
    void elAltaDelCondominioQueda() {
        // Es el primer criterio del issue #9 --"un condominio se puede crear con su configuracion
        // minima y queda auditado"-- y estuvo sin cumplirse hasta el 16-oct-2026. El evento se
        // publicaba desde el principio, pero no implementaba AuditableEvent: nadie lo escuchaba,
        // asi que el alta no se registraba. No fallaba nada, simplemente no estaba, que es la
        // forma del riesgo que tiene esa interfaz de nacimiento. De ahi este caso.
        AuditEntryView fila = laFilaDe("CONDOMINIUM_REGISTERED");

        assertThat(fila.actor()).isEqualTo("admin");
        assertThat(fila.entity()).isEqualTo("condominio");
        // La unica accion cuyo condominio y cuya entidad son la misma cosa.
        assertThat(fila.condominio()).isEqualTo(condominio);
        assertThat(fila.entityId()).isEqualTo(condominio);
        // Nulo y no vacio: no habia nada antes del alta.
        assertThat(fila.oldValues()).isNull();
        assertThat(fila.newValues())
                .hasEntrySatisfying("name", nombre -> assertThat(nombre).startsWith("Auditoria "));
    }

    @Test
    @DisplayName("conceder una membresia queda en la bitacora, con sus roles")
    void laMembresiaQueda() {
        UUID invitada = cuentaNueva().id();

        membresias.grant(
                condominio,
                invitada,
                Set.of(MembershipRole.OWNER, MembershipRole.BOARD),
                null,
                administradora.toString());

        AuditEntryView fila = laFilaDe("MEMBERSHIP_GRANTED");
        assertThat(fila.actor()).isEqualTo(administradora.toString());
        assertThat(fila.entity()).isEqualTo("membresia");
        assertThat(fila.condominio()).isEqualTo(condominio);
        // Antes es nulo porque no habia nada antes: la membresia nace aqui. Vacio querria decir
        // "existia y no tenia valores", que no es lo mismo.
        assertThat(fila.oldValues()).isNull();
        assertThat(fila.newValues()).containsEntry("accountId", invitada.toString());
        // Ordenados al escribirlos, para que dos filas con los mismos roles se vean iguales.
        assertThat(fila.newValues()).containsEntry("roles", "BOARD,OWNER");
    }

    @Test
    @DisplayName("cambiar la configuracion queda con los valores de oldValues y de newValues")
    void laConfiguracionQuedaConLasDosMitades() {
        condominios.changeConfig(condominio, 7, 20, administradora.toString());

        AuditEntryView fila = laFilaDe("CONFIGURATION_CHANGED");
        assertThat(fila.entity()).isEqualTo("configuracion");
        // La entidad es el CONDOMINIO y no la fila de configuracion: la configuracion lleva
        // vigencia, asi que su identificador cambia en cada cambio y no serviria para seguir la
        // historia.
        assertThat(fila.entityId()).isEqualTo(condominio);
        assertThat(fila.oldValues()).containsEntry("issueDay", "5").containsEntry("dueDay", "10");
        assertThat(fila.newValues()).containsEntry("issueDay", "7").containsEntry("dueDay", "20");
    }

    @Test
    @DisplayName("toda invitacion, aceptacion y revocacion queda en la bitacora")
    void lasInvitacionesQuedan() {
        // Es el tercer criterio de aceptacion del issue #18, que quedo esperando a este modulo.
        membresias.grant(condominio, administradora, Set.of(MembershipRole.CONDO_ADMIN), null, "admin");
        AccountView unaInvitada = cuentaNueva();
        AccountView otraInvitada = cuentaNueva();

        UUID aRevocar = invitaciones
                .issue(condominio, unaInvitada.id(), Set.of(MembershipRole.OWNER), administradora.toString())
                .id();
        invitaciones.revoke(condominio, aRevocar, administradora.toString());

        invitaciones.issue(condominio, otraInvitada.id(), Set.of(MembershipRole.TENANT), administradora.toString());
        invitaciones.accept(tokenDe(otraInvitada), otraInvitada.id());

        assertThat(laFilaDe("INVITATION_ISSUED").entity()).isEqualTo("invitacion");
        assertThat(laFilaDe("INVITATION_REVOKED").oldValues())
                .containsEntry("accountId", unaInvitada.id().toString());
        assertThat(laFilaDe("INVITATION_ACCEPTED").newValues()).containsKey("membershipId");
    }

    @Test
    @DisplayName("un reenvio es una action distinta de una emision")
    void elReenvioEsOtraAccion() {
        // Quien lee la bitacora busca "a quien se invito" o "a quien hubo que insistirle". Con una
        // bandera dentro del JSON eso no se filtra.
        membresias.grant(condominio, administradora, Set.of(MembershipRole.CONDO_ADMIN), null, "admin");
        UUID primera = invitaciones
                .issue(condominio, cuentaNueva().id(), Set.of(MembershipRole.OWNER), administradora.toString())
                .id();

        invitaciones.resend(condominio, primera, administradora.toString());

        laFilaDe("INVITATION_RESENT");
        laFilaDe("INVITATION_REVOKED_BY_RESEND");
        assertThat(delCondominio().stream().map(AuditEntryView::action)).doesNotContain("INVITATION_REVOKED");
    }

    private String tokenDe(AccountView cuenta) {
        return buzon.ultimoTokenPara(cuenta.email())
                .orElseThrow(() -> new AssertionError("No llego la invitacion a " + cuenta.email()));
    }

    @Test
    @DisplayName("la baja, la suspension y la reactivacion de una membresia quedan, con su motivo")
    void lasBajasYSuspensionesQuedan() {
        // Seguridad 8 pide los cambios de rol en la bitacora, y una baja es el mayor de todos: le
        // quita todos los que tenia. Es tambien la primera pregunta de quien mira la bitacora
        // despues de una queja: por que esta persona dejo de ver el edificio.
        UUID queSeVa = cuentaNueva().id();
        UUID queSeSuspende = cuentaNueva().id();
        UUID deLaQueSeVa = membresias
                .grant(condominio, queSeVa, Set.of(MembershipRole.OWNER), null, administradora.toString())
                .id();
        UUID deLaQueSeSuspende = membresias
                .grant(condominio, queSeSuspende, Set.of(MembershipRole.TENANT), null, administradora.toString())
                .id();

        membresias.revoke(condominio, deLaQueSeVa, "vendio su departamento", administradora.toString());
        membresias.suspend(condominio, deLaQueSeSuspende, "cuota impaga", administradora.toString());
        membresias.reinstate(condominio, deLaQueSeSuspende, "pago al dia", administradora.toString());

        AuditEntryView baja = laFilaDe("MEMBERSHIP_ENDED");
        assertThat(baja.entity()).isEqualTo("membresia");
        assertThat(baja.entityId()).isEqualTo(deLaQueSeVa);
        assertThat(baja.oldValues())
                .containsEntry("accountId", queSeVa.toString())
                .containsEntry("roles", "OWNER");
        // El motivo vive AQUI y solo aqui: la fila cerrada dice cuando, y la bitacora por que.
        assertThat(baja.newValues()).containsEntry("motivo", "vendio su departamento");

        assertThat(laFilaDe("MEMBERSHIP_SUSPENDED").newValues()).containsEntry("motivo", "cuota impaga");
        // Reactivar es una accion distinta y no la misma con una bandera: quien lee busca "a quien
        // se suspendio" o "a quien se le devolvio el acceso", y una bandera dentro del JSON no se
        // filtra. Mismo criterio que el reenvio de una invitacion.
        assertThat(laFilaDe("MEMBERSHIP_REACTIVATED").newValues()).containsEntry("motivo", "pago al dia");
    }

    @Test
    @DisplayName("la venta de una unidad queda como un solo hecho, con sus dos lados")
    void laVentaQuedaComoUnSoloHecho() {
        UUID unidad = unidades.register(
                        condominio,
                        new NewUnit("AUD-101", UnitType.APARTMENT, new BigDecimal("1.000000"), null, null, null),
                        administradora.toString())
                .id();
        UUID vendedor = personas.register(new NewPerson("Vende", "Perez", null), "admin")
                .id();
        UUID comprador = personas.register(new NewPerson("Compra", "Perez", null), "admin")
                .id();
        relaciones.grantOwnership(
                condominio, unidad, vendedor, new BigDecimal("100.000000"), administradora.toString());

        relaciones.transferOwnership(condominio, unidad, vendedor, comprador, administradora.toString());

        // Un evento propio y no dos --una cerrada y otra abierta-- porque lo que hay que poder
        // reconstruir dentro de un año es la venta, con sus dos lados.
        AuditEntryView venta = laFilaDe("OWNERSHIP_TRANSFERRED");
        assertThat(venta.entity()).isEqualTo("titularidad");
        // La entidad es la UNIDAD: la titularidad lleva vigencia, asi que su identificador cambia en
        // cada venta y no serviria para seguir la historia.
        assertThat(venta.entityId()).isEqualTo(unidad);
        assertThat(venta.oldValues()).containsEntry("personId", vendedor.toString());
        assertThat(venta.newValues())
                .containsEntry("personId", comprador.toString())
                .containsEntry("relacionesCerradas", "1");

        // Y el alta que la precedio tambien esta: una bitacora que registra el fin de una relacion
        // y no su principio hace creer que la relacion nacio sola.
        assertThat(laFilaDe("OWNERSHIP_GRANTED").newValues()).containsEntry("personId", vendedor.toString());
    }

    @Test
    @DisplayName("el cambio de roles queda con las dos mitades, el motivo y la concentracion")
    void elCambioDeRolesQueda() {
        // Seguridad 8 pide los cambios de rol en la bitacora, y este es el caso de todos los dias:
        // la baja del #19 era el extremo --quitarlos todos--. Importa mas que las demas filas porque
        // la tabla solo guarda los roles de HOY: el cambio edita membership_role en el sitio, asi
        // que la historia de quien tuvo cual esta aqui y en ningun otro sitio.
        UUID aQuienSeLeCambian = cuentaNueva().id();
        UUID suya = membresias
                .grant(
                        condominio,
                        aQuienSeLeCambian,
                        Set.of(MembershipRole.CONDO_ADMIN),
                        null,
                        administradora.toString())
                .id();

        membresias.changeRoles(
                condominio,
                suya,
                Set.of(MembershipRole.CONDO_ADMIN, MembershipRole.FINANCE_ADMIN),
                "en este edificio administra una sola persona",
                administradora.toString());

        AuditEntryView fila = laFilaDe("MEMBERSHIP_ROLES_CHANGED");
        assertThat(fila.entity()).isEqualTo("membresia");
        assertThat(fila.entityId()).isEqualTo(suya);
        assertThat(fila.actor()).isEqualTo(administradora.toString());
        assertThat(fila.oldValues())
                .containsEntry("accountId", aQuienSeLeCambian.toString())
                .containsEntry("roles", "CONDO_ADMIN");
        assertThat(fila.newValues())
                .containsEntry("roles", "CONDO_ADMIN,FINANCE_ADMIN")
                .containsEntry("motivo", "en este edificio administra una sola persona")
                // El aviso del alcance del issue #20. Seguridad 10.1 permite la concentracion a
                // condicion de que quede registrada, y esta es la forma de registrarla.
                .containsEntry("concentraFunciones", "true");
    }

    @Test
    @DisplayName("las acciones de plataforma no salen en la bitacora de un condominio, y si en la suya")
    void loDePlataformaNoEsDeNingunCondominio() {
        // El rol de superadministrador no cuelga de ningun condominio, asi que su fila va sin
        // condominio y la consulta de un condominio no la lista. Sin esto, el administrador de un
        // edificio veria acciones de la plataforma entera.
        AccountView nueva = cuentaNueva();
        AccountView quienConcede = cuentaNueva();
        new JdbcTemplate(fuenteDeDatos)
                .update("insert into platform_admin (user_id, granted_at) values (?, now())", quienConcede.id());
        identidades.grantPlatformAdmin(nueva.id(), quienConcede.id().toString());

        membresias.grant(condominio, nueva.id(), Set.of(MembershipRole.OWNER), null, administradora.toString());
        laFilaDe("MEMBERSHIP_GRANTED");

        assertThat(delCondominio().stream().map(AuditEntryView::action)).doesNotContain("PLATFORM_GRANTED");

        // Y la otra mitad, que es lo que trae el issue #20: en su propia consulta si sale, con el
        // actor de verdad --quien lo concedio-- y no el "arranque" de la unica fila que habia antes.
        assertThat(deLaPlataforma()).anySatisfy(fila -> assertThat(fila)
                .returns("PLATFORM_GRANTED", AuditEntryView::action)
                .returns(nueva.id(), AuditEntryView::entityId)
                .returns(quienConcede.id().toString(), AuditEntryView::actor)
                .returns(null, AuditEntryView::condominio));

        // Y las del condominio no se cuelan en la de plataforma: el filtro es la columna, no el
        // orden en que se escribieron.
        assertThat(deLaPlataforma().stream().map(AuditEntryView::action)).doesNotContain("MEMBERSHIP_GRANTED");
    }

    @Test
    @DisplayName("la revocacion del rol de plataforma queda, con su motivo: la fila de platform_admin se borra")
    void laRevocacionDePlataformaQueda() {
        AccountView quienConcede = cuentaNueva();
        AccountView quienLoPierde = cuentaNueva();
        new JdbcTemplate(fuenteDeDatos)
                .update("insert into platform_admin (user_id, granted_at) values (?, now())", quienConcede.id());
        identidades.grantPlatformAdmin(quienLoPierde.id(), quienConcede.id().toString());

        identidades.revokePlatformAdmin(
                quienLoPierde.id(), "dejo la empresa", quienConcede.id().toString());

        // Esta fila importa mas que la mayoria: revocar BORRA la fila de platform_admin, asi que si
        // esto no quedara, haber tenido el rol seria indistinguible de no haberlo tenido nunca.
        assertThat(deLaPlataforma()).anySatisfy(fila -> assertThat(fila)
                .returns("PLATFORM_REVOKED", AuditEntryView::action)
                .returns(quienLoPierde.id(), AuditEntryView::entityId));
        assertThat(identidades.esSuperadministrador(quienLoPierde.id())).isFalse();
    }

    @Test
    @DisplayName("cerrar todas las sesiones queda en la bitacora de plataforma, con cuantas se cerraron")
    void elCierreDeTodasLasSesionesQueda() {
        AccountView quien = cuentaNueva();

        identidades.signOutEverywhere(quien.id(), quien.id().toString());

        // Va sin condominio, como el rol de plataforma y por lo mismo: una sesion no es de un
        // edificio. Y se audita, al contrario que un cierre de sesion normal, porque su motivo
        // tipico es el que Seguridad 5.3 pone en la tabla --perdida o robo del telefono-- y quien
        // investigue despues necesita la hora a la que la persona se dio cuenta.
        assertThat(deLaPlataforma()).anySatisfy(fila -> assertThat(fila)
                .returns("SESSIONS_CLOSED", AuditEntryView::action)
                .returns(quien.id(), AuditEntryView::entityId)
                .returns(quien.id().toString(), AuditEntryView::actor)
                .returns(null, AuditEntryView::condominio));

        assertThat(delCondominio().stream().map(AuditEntryView::action)).doesNotContain("SESSIONS_CLOSED");
    }

    // --- Que no se pueda tocar: el primer criterio -------------------------------------------

    @Test
    @DisplayName("un intento de modificar la bitacora falla en la base de datos")
    void laBitacoraNoSeModifica() {
        // El primer criterio del issue, y se comprueba por SQL a proposito: lo que tiene que
        // proteger la fila es la base, no el codigo Java que hay delante. Un disparador y no un
        // REVOKE porque la aplicacion se conecta como superusuario y los privilegios no le aplican,
        // que es lo que se descubrio evaluando RLS para el #17.
        membresias.grant(condominio, cuentaNueva().id(), Set.of(MembershipRole.OWNER), null, administradora.toString());
        UUID fila = laFilaDe("MEMBERSHIP_GRANTED").id();
        var sql = new JdbcTemplate(fuenteDeDatos);

        assertThatThrownBy(() -> sql.update("update audit_log set actor = 'otro' where id = ?", fila))
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> sql.update("delete from audit_log where id = ?", fila))
                .hasMessageContaining("append-only");

        // Y sigue ahi, con su actor original.
        assertThat(sql.queryForObject("select actor from audit_log where id = ?", String.class, fila))
                .isEqualTo(administradora.toString());
    }

    // --- Que se pueda buscar: el segundo criterio --------------------------------------------

    @Test
    @DisplayName("la consulta filtra por actor, por entity y por rango de fechas")
    void laConsultaFiltra() {
        UUID otraActora = cuentaNueva().id();
        membresias.grant(condominio, cuentaNueva().id(), Set.of(MembershipRole.OWNER), null, administradora.toString());
        laFilaDe("MEMBERSHIP_GRANTED");

        // El reloj se adelanta entre las dos acciones para que caigan en instantes distintos y el
        // filtro de fechas tenga algo que separar. Con el reloj real, dos acciones seguidas pueden
        // compartir milisegundo.
        Instant corte = reloj.instant().plusSeconds(1);
        reloj.adelantar(Duration.ofMinutes(5));
        condominios.changeConfig(condominio, 8, 21, otraActora.toString());
        laFilaDe("CONFIGURATION_CHANGED");

        // Por actor.
        assertThat(filtrando(otraActora.toString(), null, null, null))
                .extracting(AuditEntryView::action)
                .containsExactly("CONFIGURATION_CHANGED");

        // Por entidad.
        assertThat(filtrando(null, "membresia", null, null))
                .extracting(AuditEntryView::action)
                .containsExactly("MEMBERSHIP_GRANTED");

        // Por rango: desde el corte solo esta la segunda.
        assertThat(filtrando(null, null, corte, null))
                .extracting(AuditEntryView::action)
                .containsExactly("CONFIGURATION_CHANGED");

        // Y hasta el corte, la primera y el alta del condominio, que paso antes que todo. Hasta es
        // exclusivo, asi que dos rangos contiguos no devuelven la misma fila dos veces.
        //
        // Sin orden a proposito: el reloj de prueba esta parado, asi que el alta del @BeforeEach y
        // esta membresia comparten el mismo instante y cual sale primero no lo decide nada que
        // este test quiera afirmar. Donde el orden SI importa --la paginacion-- el reloj se
        // adelanta entre accion y accion para que haya un orden de verdad que comprobar.
        assertThat(filtrando(null, null, null, corte))
                .extracting(AuditEntryView::action)
                .containsExactlyInAnyOrder("MEMBERSHIP_GRANTED", "CONDOMINIUM_REGISTERED");

        // Los filtros se combinan: este actor no toco ninguna membresia.
        assertThat(filtrando(otraActora.toString(), "membresia", null, null)).isEmpty();
    }

    private List<AuditEntryView> filtrando(
            @org.jspecify.annotations.Nullable String actor,
            @org.jspecify.annotations.Nullable String entity,
            @org.jspecify.annotations.Nullable Instant desde,
            @org.jspecify.annotations.Nullable Instant hasta) {
        return bitacora.ofCondominium(condominio, actor, entity, desde, hasta, 0, 100)
                .contenido();
    }

    @Test
    @DisplayName("la consulta pagina y devuelve lo mas reciente primero")
    void laConsultaPaginaYOrdena() {
        for (int i = 0; i < 3; i++) {
            reloj.adelantar(Duration.ofMinutes(1));
            condominios.changeConfig(condominio, 5 + i, 15 + i, administradora.toString());
        }
        // Cuatro y no tres: el alta del condominio tambien esta, escrita por el @BeforeEach.
        assertThat(delCondominio()).hasSize(4);

        PagedResponse<AuditEntryView> primera = bitacora.ofCondominium(condominio, null, null, null, null, 0, 2);
        assertThat(primera.contenido()).hasSize(2);
        assertThat(primera.total()).isEqualTo(4);
        assertThat(primera.totalPaginas()).isEqualTo(2);
        assertThat(primera.pagina()).isZero();

        // Lo mas reciente primero: una bitacora se lee empezando por lo ultimo.
        assertThat(primera.contenido().get(0).newValues()).containsEntry("issueDay", "7");
        assertThat(primera.contenido().get(1).newValues()).containsEntry("issueDay", "6");

        PagedResponse<AuditEntryView> segunda = bitacora.ofCondominium(condominio, null, null, null, null, 1, 2);
        assertThat(segunda.contenido()).hasSize(2);
        assertThat(segunda.contenido().get(0).newValues()).containsEntry("issueDay", "5");
        // Y al final de todo, el alta: es lo primero que le paso a este condominio.
        assertThat(segunda.contenido().get(1).action()).isEqualTo("CONDOMINIUM_REGISTERED");
    }

    // --- La retencion del año ----------------------------------------------------------------

    @Test
    @DisplayName("la purga borra lo viejo y deja lo del año")
    void laPurgaRespetaLaRetencion() {
        membresias.grant(condominio, cuentaNueva().id(), Set.of(MembershipRole.OWNER), null, administradora.toString());
        UUID reciente = laFilaDe("MEMBERSHIP_GRANTED").id();

        // Una fila vieja, puesta por SQL: para tenerla de hace trece meses habria que mover el reloj
        // antes de que naciera, y lo que se prueba aqui es la purga, no como llego la fila.
        //
        // La fecha se calcula con el reloj DE PRUEBA y no con el now() de la base. Con now(), el dia
        // que el reloj fijo del test quede a mas de un año del presente, la fila "reciente" tambien
        // caeria en la purga y el test fallaria por el calendario y no por el codigo.
        var sql = new JdbcTemplate(fuenteDeDatos);
        UUID vieja = UUID.randomUUID();
        sql.update(
                "insert into audit_log (id, actor, action, entity, entity_id, condominium_id, occurred_at)"
                        + " values (?, 'viejo', 'MEMBERSHIP_GRANTED', 'membresia', ?, ?, ?)",
                vieja,
                UUID.randomUUID(),
                condominio,
                java.sql.Timestamp.from(reloj.instant().minus(Duration.ofDays(400))));

        long borradas = bitacora.purgar(reloj.instant().minus(Duration.ofDays(365)));

        assertThat(borradas).isPositive();
        assertThat(sql.queryForObject("select count(*) from audit_log where id = ?", Long.class, vieja))
                .isZero();
        // Y la reciente sigue, que es la otra mitad: una purga que se llevara todo tambien pasaria
        // la primera comprobacion.
        assertThat(sql.queryForObject("select count(*) from audit_log where id = ?", Long.class, reciente))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("la purga deja el disparador puesto")
    void laPurgaNoDesarmaLaProteccion() {
        // La funcion de purga apaga el disparador para poder borrar. Si lo dejara apagado, la
        // bitacora quedaria modificable desde la primera purga y nadie lo notaria.
        membresias.grant(condominio, cuentaNueva().id(), Set.of(MembershipRole.OWNER), null, administradora.toString());
        UUID fila = laFilaDe("MEMBERSHIP_GRANTED").id();

        bitacora.purgar(reloj.instant().minus(Duration.ofDays(365)));

        var sql = new JdbcTemplate(fuenteDeDatos);
        assertThatThrownBy(() -> sql.update("delete from audit_log where id = ?", fila))
                .hasMessageContaining("append-only");
    }

    // --- La IP y la traza, que no vienen del dominio -----------------------------------------

    @Test
    @DisplayName("la IP y la traza de la peticion acaban en la fila")
    void laIpYLaTrazaDeLaPeticionQuedan() throws Exception {
        // 5.2 pide la IP en AuditLog, y ningun evento de dominio puede llevarla: un evento no sabe
        // de HTTP. Las pone el filtro de trazas en el contexto de log, y llegan al oyente --que
        // corre en OTRO HILO-- porque PropagacionDelMdc copia el MDC. Sin eso, las dos salen nulas y
        // el criterio no se cumple.
        membresias.grant(condominio, administradora, Set.of(MembershipRole.FINANCE_ADMIN), null, "admin");
        laFilaDe("MEMBERSHIP_GRANTED");

        mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        "/api/v1/condominios/" + condominio + "/configuracion")
                                .with(org.springframework.security.test.web.servlet.request
                                        .SecurityMockMvcRequestPostProcessors.csrf())
                                .with(org.springframework.security.test.web.servlet.request
                                        .SecurityMockMvcRequestPostProcessors.user(administradora.toString())
                                        .roles("FINANCE_ADMIN"))
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("{\"issueDay\":9,\"dueDay\":22}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk());

        AuditEntryView fila = laFilaDe("CONFIGURATION_CHANGED");
        assertThat(fila.ip()).isNotBlank();
        assertThat(fila.traceId()).isNotBlank();
    }
}
