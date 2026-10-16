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
import bo.coprop.identity.DoblesDeInvitacion;
import bo.coprop.identity.Invitations;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.Memberships;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.identity.PersonView;
import bo.coprop.shared.Pagina;
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
@Import({TestcontainersConfiguration.class, DoblesDeInvitacion.class})
@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class AuditoriaTests {

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
    private DoblesDeInvitacion.RelojDePrueba reloj;

    @Autowired
    private DoblesDeInvitacion.CorreoDePrueba buzon;

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
                                CondominiumType.EDIFICIO,
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
    private AuditEntryView laFilaDe(String accion) {
        List<AuditEntryView> todas = delCondominio();
        return todas.stream()
                .filter(fila -> fila.accion().equals(accion))
                .findFirst()
                .orElseThrow(() -> new AssertionError("La bitacora no registro %s. Lo que si tiene: %s"
                        .formatted(
                                accion,
                                todas.stream().map(AuditEntryView::accion).toList())));
    }

    // --- Que las acciones lleguen -----------------------------------------------------------

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

        AuditEntryView fila = laFilaDe("MEMBRESIA_CONCEDIDA");
        assertThat(fila.actor()).isEqualTo(administradora.toString());
        assertThat(fila.entidad()).isEqualTo("membresia");
        assertThat(fila.condominio()).isEqualTo(condominio);
        // Antes es nulo porque no habia nada antes: la membresia nace aqui. Vacio querria decir
        // "existia y no tenia valores", que no es lo mismo.
        assertThat(fila.antes()).isNull();
        assertThat(fila.despues()).containsEntry("accountId", invitada.toString());
        // Ordenados al escribirlos, para que dos filas con los mismos roles se vean iguales.
        assertThat(fila.despues()).containsEntry("roles", "BOARD,OWNER");
    }

    @Test
    @DisplayName("cambiar la configuracion queda con los valores de antes y de despues")
    void laConfiguracionQuedaConLasDosMitades() {
        condominios.changeConfig(condominio, 7, 20, administradora.toString());

        AuditEntryView fila = laFilaDe("CONFIGURACION_CAMBIADA");
        assertThat(fila.entidad()).isEqualTo("configuracion");
        // La entidad es el CONDOMINIO y no la fila de configuracion: la configuracion lleva
        // vigencia, asi que su identificador cambia en cada cambio y no serviria para seguir la
        // historia.
        assertThat(fila.entidadId()).isEqualTo(condominio);
        assertThat(fila.antes()).containsEntry("issueDay", "5").containsEntry("dueDay", "10");
        assertThat(fila.despues()).containsEntry("issueDay", "7").containsEntry("dueDay", "20");
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

        assertThat(laFilaDe("INVITACION_EMITIDA").entidad()).isEqualTo("invitacion");
        assertThat(laFilaDe("INVITACION_REVOCADA").antes())
                .containsEntry("accountId", unaInvitada.id().toString());
        assertThat(laFilaDe("INVITACION_ACEPTADA").despues()).containsKey("membershipId");
    }

    @Test
    @DisplayName("un reenvio es una accion distinta de una emision")
    void elReenvioEsOtraAccion() {
        // Quien lee la bitacora busca "a quien se invito" o "a quien hubo que insistirle". Con una
        // bandera dentro del JSON eso no se filtra.
        membresias.grant(condominio, administradora, Set.of(MembershipRole.CONDO_ADMIN), null, "admin");
        UUID primera = invitaciones
                .issue(condominio, cuentaNueva().id(), Set.of(MembershipRole.OWNER), administradora.toString())
                .id();

        invitaciones.resend(condominio, primera, administradora.toString());

        laFilaDe("INVITACION_REENVIADA");
        laFilaDe("INVITACION_REVOCADA_POR_REENVIO");
        assertThat(delCondominio().stream().map(AuditEntryView::accion)).doesNotContain("INVITACION_REVOCADA");
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

        AuditEntryView baja = laFilaDe("MEMBRESIA_DADA_DE_BAJA");
        assertThat(baja.entidad()).isEqualTo("membresia");
        assertThat(baja.entidadId()).isEqualTo(deLaQueSeVa);
        assertThat(baja.antes()).containsEntry("accountId", queSeVa.toString()).containsEntry("roles", "OWNER");
        // El motivo vive AQUI y solo aqui: la fila cerrada dice cuando, y la bitacora por que.
        assertThat(baja.despues()).containsEntry("motivo", "vendio su departamento");

        assertThat(laFilaDe("MEMBRESIA_SUSPENDIDA").despues()).containsEntry("motivo", "cuota impaga");
        // Reactivar es una accion distinta y no la misma con una bandera: quien lee busca "a quien
        // se suspendio" o "a quien se le devolvio el acceso", y una bandera dentro del JSON no se
        // filtra. Mismo criterio que el reenvio de una invitacion.
        assertThat(laFilaDe("MEMBRESIA_REACTIVADA").despues()).containsEntry("motivo", "pago al dia");
    }

    @Test
    @DisplayName("la venta de una unidad queda como un solo hecho, con sus dos lados")
    void laVentaQuedaComoUnSoloHecho() {
        UUID unidad = unidades.register(
                        condominio,
                        new NewUnit("AUD-101", UnitType.DEPARTAMENTO, new BigDecimal("1.000000"), null, null, null),
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
        AuditEntryView venta = laFilaDe("TITULARIDAD_TRANSFERIDA");
        assertThat(venta.entidad()).isEqualTo("titularidad");
        // La entidad es la UNIDAD: la titularidad lleva vigencia, asi que su identificador cambia en
        // cada venta y no serviria para seguir la historia.
        assertThat(venta.entidadId()).isEqualTo(unidad);
        assertThat(venta.antes()).containsEntry("personId", vendedor.toString());
        assertThat(venta.despues())
                .containsEntry("personId", comprador.toString())
                .containsEntry("relacionesCerradas", "1");

        // Y el alta que la precedio tambien esta: una bitacora que registra el fin de una relacion
        // y no su principio hace creer que la relacion nacio sola.
        assertThat(laFilaDe("TITULARIDAD_CONCEDIDA").despues()).containsEntry("personId", vendedor.toString());
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

        AuditEntryView fila = laFilaDe("MEMBRESIA_ROLES_CAMBIADOS");
        assertThat(fila.entidad()).isEqualTo("membresia");
        assertThat(fila.entidadId()).isEqualTo(suya);
        assertThat(fila.actor()).isEqualTo(administradora.toString());
        assertThat(fila.antes())
                .containsEntry("accountId", aQuienSeLeCambian.toString())
                .containsEntry("roles", "CONDO_ADMIN");
        assertThat(fila.despues())
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
        laFilaDe("MEMBRESIA_CONCEDIDA");

        assertThat(delCondominio().stream().map(AuditEntryView::accion)).doesNotContain("PLATAFORMA_CONCEDIDA");

        // Y la otra mitad, que es lo que trae el issue #20: en su propia consulta si sale, con el
        // actor de verdad --quien lo concedio-- y no el "arranque" de la unica fila que habia antes.
        assertThat(deLaPlataforma()).anySatisfy(fila -> assertThat(fila)
                .returns("PLATAFORMA_CONCEDIDA", AuditEntryView::accion)
                .returns(nueva.id(), AuditEntryView::entidadId)
                .returns(quienConcede.id().toString(), AuditEntryView::actor)
                .returns(null, AuditEntryView::condominio));

        // Y las del condominio no se cuelan en la de plataforma: el filtro es la columna, no el
        // orden en que se escribieron.
        assertThat(deLaPlataforma().stream().map(AuditEntryView::accion)).doesNotContain("MEMBRESIA_CONCEDIDA");
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
                .returns("PLATAFORMA_REVOCADA", AuditEntryView::accion)
                .returns(quienLoPierde.id(), AuditEntryView::entidadId));
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
                .returns("SESIONES_CERRADAS", AuditEntryView::accion)
                .returns(quien.id(), AuditEntryView::entidadId)
                .returns(quien.id().toString(), AuditEntryView::actor)
                .returns(null, AuditEntryView::condominio));

        assertThat(delCondominio().stream().map(AuditEntryView::accion)).doesNotContain("SESIONES_CERRADAS");
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
        UUID fila = laFilaDe("MEMBRESIA_CONCEDIDA").id();
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
    @DisplayName("la consulta filtra por actor, por entidad y por rango de fechas")
    void laConsultaFiltra() {
        UUID otraActora = cuentaNueva().id();
        membresias.grant(condominio, cuentaNueva().id(), Set.of(MembershipRole.OWNER), null, administradora.toString());
        laFilaDe("MEMBRESIA_CONCEDIDA");

        // El reloj se adelanta entre las dos acciones para que caigan en instantes distintos y el
        // filtro de fechas tenga algo que separar. Con el reloj real, dos acciones seguidas pueden
        // compartir milisegundo.
        Instant corte = reloj.instant().plusSeconds(1);
        reloj.adelantar(Duration.ofMinutes(5));
        condominios.changeConfig(condominio, 8, 21, otraActora.toString());
        laFilaDe("CONFIGURACION_CAMBIADA");

        // Por actor.
        assertThat(filtrando(otraActora.toString(), null, null, null))
                .extracting(AuditEntryView::accion)
                .containsExactly("CONFIGURACION_CAMBIADA");

        // Por entidad.
        assertThat(filtrando(null, "membresia", null, null))
                .extracting(AuditEntryView::accion)
                .containsExactly("MEMBRESIA_CONCEDIDA");

        // Por rango: desde el corte solo esta la segunda.
        assertThat(filtrando(null, null, corte, null))
                .extracting(AuditEntryView::accion)
                .containsExactly("CONFIGURACION_CAMBIADA");

        // Y hasta el corte solo la primera. Hasta es exclusivo, asi que dos rangos contiguos no
        // devuelven la misma fila dos veces.
        assertThat(filtrando(null, null, null, corte))
                .extracting(AuditEntryView::accion)
                .containsExactly("MEMBRESIA_CONCEDIDA");

        // Los filtros se combinan: este actor no toco ninguna membresia.
        assertThat(filtrando(otraActora.toString(), "membresia", null, null)).isEmpty();
    }

    private List<AuditEntryView> filtrando(
            @org.jspecify.annotations.Nullable String actor,
            @org.jspecify.annotations.Nullable String entidad,
            @org.jspecify.annotations.Nullable Instant desde,
            @org.jspecify.annotations.Nullable Instant hasta) {
        return bitacora.ofCondominium(condominio, actor, entidad, desde, hasta, 0, 100)
                .contenido();
    }

    @Test
    @DisplayName("la consulta pagina y devuelve lo mas reciente primero")
    void laConsultaPaginaYOrdena() {
        for (int i = 0; i < 3; i++) {
            reloj.adelantar(Duration.ofMinutes(1));
            condominios.changeConfig(condominio, 5 + i, 15 + i, administradora.toString());
        }
        assertThat(delCondominio()).hasSize(3);

        Pagina<AuditEntryView> primera = bitacora.ofCondominium(condominio, null, null, null, null, 0, 2);
        assertThat(primera.contenido()).hasSize(2);
        assertThat(primera.total()).isEqualTo(3);
        assertThat(primera.totalPaginas()).isEqualTo(2);
        assertThat(primera.pagina()).isZero();

        // Lo mas reciente primero: una bitacora se lee empezando por lo ultimo.
        assertThat(primera.contenido().get(0).despues()).containsEntry("issueDay", "7");
        assertThat(primera.contenido().get(1).despues()).containsEntry("issueDay", "6");

        Pagina<AuditEntryView> segunda = bitacora.ofCondominium(condominio, null, null, null, null, 1, 2);
        assertThat(segunda.contenido()).hasSize(1);
        assertThat(segunda.contenido().get(0).despues()).containsEntry("issueDay", "5");
    }

    // --- La retencion del año ----------------------------------------------------------------

    @Test
    @DisplayName("la purga borra lo viejo y deja lo del año")
    void laPurgaRespetaLaRetencion() {
        membresias.grant(condominio, cuentaNueva().id(), Set.of(MembershipRole.OWNER), null, administradora.toString());
        UUID reciente = laFilaDe("MEMBRESIA_CONCEDIDA").id();

        // Una fila vieja, puesta por SQL: para tenerla de hace trece meses habria que mover el reloj
        // antes de que naciera, y lo que se prueba aqui es la purga, no como llego la fila.
        //
        // La fecha se calcula con el reloj DE PRUEBA y no con el now() de la base. Con now(), el dia
        // que el reloj fijo del test quede a mas de un año del presente, la fila "reciente" tambien
        // caeria en la purga y el test fallaria por el calendario y no por el codigo.
        var sql = new JdbcTemplate(fuenteDeDatos);
        UUID vieja = UUID.randomUUID();
        sql.update(
                "insert into audit_log (id, actor, accion, entidad, entidad_id, condominium_id, occurred_at)"
                        + " values (?, 'viejo', 'MEMBRESIA_CONCEDIDA', 'membresia', ?, ?, ?)",
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
        UUID fila = laFilaDe("MEMBRESIA_CONCEDIDA").id();

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
        laFilaDe("MEMBRESIA_CONCEDIDA");

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

        AuditEntryView fila = laFilaDe("CONFIGURACION_CAMBIADA");
        assertThat(fila.ip()).isNotBlank();
        assertThat(fila.traceId()).isNotBlank();
    }
}
