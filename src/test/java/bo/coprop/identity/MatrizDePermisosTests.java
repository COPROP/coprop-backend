package bo.coprop.identity;

import static bo.coprop.identity.Alcance.ALL;
import static bo.coprop.identity.Alcance.NONE;
import static bo.coprop.identity.Alcance.OWN;
import static bo.coprop.identity.MembershipRole.BOARD;
import static bo.coprop.identity.MembershipRole.CONDO_ADMIN;
import static bo.coprop.identity.MembershipRole.FINANCE_ADMIN;
import static bo.coprop.identity.MembershipRole.MAINTENANCE;
import static bo.coprop.identity.MembershipRole.OWNER;
import static bo.coprop.identity.MembershipRole.PLATFORM_ADMIN;
import static bo.coprop.identity.MembershipRole.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * La matriz de Seguridad 3, fila por fila. Es el primer criterio de aceptacion del issue #16.
 *
 * <p>Cada caso declara la fila entera --los siete roles de una vez-- y no solo las celdas con "Sí".
 * Es a proposito: la mitad del valor de una matriz de permisos esta en los "—", y un test que solo
 * compruebe lo que se permite pasa igual el dia que alguien le conceda una fila de mas a un rol.
 *
 * <p>No arranca Spring. Lo que se prueba es una decision escrita, y hacerlo contra un contexto
 * tardaria mas en levantar que en comprobar las ciento cincuenta celdas.
 */
class MatrizDePermisosTests {

    /**
     * Una fila de la matriz: la accion, y que alcance tiene cada rol, en el orden de las columnas
     * del analisis.
     */
    private record Fila(
            String action,
            Permiso permiso,
            Alcance plataforma,
            Alcance condominio,
            Alcance finanzas,
            Alcance propietario,
            Alcance inquilino,
            Alcance mantenimiento,
            Alcance directiva) {}

    /** Las veintiuna filas del analisis, mas las dos que esta implementacion separa. */
    private static List<Fila> matriz() {
        return List.of(
                new Fila(
                        "Crear condominios y asignar su primer administrador",
                        Permiso.CREATE_CONDOMINIUMS,
                        ALL,
                        NONE,
                        NONE,
                        NONE,
                        NONE,
                        NONE,
                        NONE),
                new Fila(
                        "Configurar proveedor de pagos y credenciales bancarias",
                        Permiso.CONFIGURE_PAYMENT_PROVIDER,
                        ALL,
                        NONE,
                        ALL,
                        NONE,
                        NONE,
                        NONE,
                        NONE),
                new Fila(
                        "Gestionar bloques, unidades y personas",
                        Permiso.MANAGE_UNITS,
                        NONE,
                        ALL,
                        NONE,
                        NONE,
                        NONE,
                        NONE,
                        NONE),
                new Fila(
                        "Invitar usuarios y asignar roles",
                        Permiso.INVITE_USERS,
                        NONE,
                        ALL,
                        NONE,
                        NONE,
                        NONE,
                        NONE,
                        NONE),
                new Fila(
                        "Invitar, pero solo a administradores",
                        Permiso.INVITE_ADMINS_ONLY,
                        ALL,
                        NONE,
                        NONE,
                        NONE,
                        NONE,
                        NONE,
                        NONE),
                new Fila(
                        "Configurar conceptos, tarifas de agua y politica de mora",
                        Permiso.CONFIGURE_CHARGES,
                        NONE,
                        NONE,
                        ALL,
                        NONE,
                        NONE,
                        NONE,
                        NONE),
                new Fila(
                        "Emitir expensas y cobro de agua del periodo",
                        Permiso.ISSUE_DUES,
                        NONE,
                        NONE,
                        ALL,
                        NONE,
                        NONE,
                        NONE,
                        NONE),
                new Fila(
                        "Registrar lecturas de medidores",
                        Permiso.RECORD_METER_READINGS,
                        NONE,
                        ALL,
                        NONE,
                        NONE,
                        NONE,
                        ALL,
                        NONE),
                new Fila("Anular una obligacion", Permiso.CANCEL_OBLIGATION, NONE, NONE, ALL, NONE, NONE, NONE, NONE),
                new Fila("Ver estado de cuenta", Permiso.VIEW_ACCOUNT_STATEMENT, NONE, ALL, ALL, OWN, OWN, NONE, NONE),
                new Fila("Generar QR de pago", Permiso.GENERATE_QR, NONE, NONE, NONE, OWN, OWN, NONE, NONE),
                new Fila(
                        "Subir comprobante de pago manual",
                        Permiso.UPLOAD_RECEIPT,
                        NONE,
                        NONE,
                        NONE,
                        OWN,
                        OWN,
                        NONE,
                        NONE),
                new Fila(
                        "Validar pago manual",
                        Permiso.VALIDATE_MANUAL_PAYMENT,
                        NONE,
                        NONE,
                        ALL,
                        NONE,
                        NONE,
                        NONE,
                        NONE),
                new Fila(
                        "Bandeja de excepciones y conciliacion",
                        Permiso.RECONCILE,
                        NONE,
                        NONE,
                        ALL,
                        NONE,
                        NONE,
                        NONE,
                        NONE),
                new Fila("Ver saldos a favor", Permiso.VIEW_CREDIT_BALANCES, NONE, ALL, ALL, OWN, OWN, NONE, NONE),
                new Fila(
                        "Registrar devolucion de saldo a favor",
                        Permiso.REFUND_CREDIT_BALANCE,
                        NONE,
                        NONE,
                        ALL,
                        NONE,
                        NONE,
                        NONE,
                        NONE),
                new Fila(
                        "Gestionar areas comunes y reglas de reserva",
                        Permiso.MANAGE_AMENITIES,
                        NONE,
                        ALL,
                        NONE,
                        NONE,
                        NONE,
                        NONE,
                        NONE),
                new Fila("Crear o cancelar reservas", Permiso.RESERVE, NONE, ALL, NONE, OWN, OWN, NONE, NONE),
                new Fila(
                        "Ver reservas del dia y bloquear areas por mantenimiento",
                        Permiso.OPERATE_AMENITIES,
                        NONE,
                        ALL,
                        NONE,
                        NONE,
                        NONE,
                        ALL,
                        NONE),
                new Fila(
                        "Reportes de recaudacion y morosidad (agregados)",
                        Permiso.VIEW_REPORTS,
                        NONE,
                        ALL,
                        ALL,
                        NONE,
                        NONE,
                        NONE,
                        ALL),
                new Fila(
                        "Morosidad unidad por unidad",
                        Permiso.VIEW_DELINQUENCY_BY_UNIT,
                        NONE,
                        ALL,
                        ALL,
                        NONE,
                        NONE,
                        NONE,
                        NONE),
                new Fila(
                        "Consultar auditoria del condominio",
                        Permiso.VIEW_AUDIT_LOG,
                        ALL,
                        ALL,
                        ALL,
                        NONE,
                        NONE,
                        NONE,
                        NONE),
                new Fila(
                        "Acceder a datos de un condominio para soporte",
                        Permiso.ACCESS_FOR_SUPPORT,
                        ALL,
                        NONE,
                        NONE,
                        NONE,
                        NONE,
                        NONE,
                        NONE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matriz")
    @DisplayName("cada fila de la matriz dice lo mismo que el analisis, en las siete columnas")
    void laFilaCoincideConElAnalisis(Fila fila) {
        assertThat(MatrizDePermisos.alcanceDe(PLATFORM_ADMIN, fila.permiso())).isEqualTo(fila.plataforma());
        assertThat(MatrizDePermisos.alcanceDe(CONDO_ADMIN, fila.permiso())).isEqualTo(fila.condominio());
        assertThat(MatrizDePermisos.alcanceDe(FINANCE_ADMIN, fila.permiso())).isEqualTo(fila.finanzas());
        assertThat(MatrizDePermisos.alcanceDe(OWNER, fila.permiso())).isEqualTo(fila.propietario());
        assertThat(MatrizDePermisos.alcanceDe(TENANT, fila.permiso())).isEqualTo(fila.inquilino());
        assertThat(MatrizDePermisos.alcanceDe(MAINTENANCE, fila.permiso())).isEqualTo(fila.mantenimiento());
        assertThat(MatrizDePermisos.alcanceDe(BOARD, fila.permiso())).isEqualTo(fila.directiva());
    }

    @Test
    @DisplayName("la matriz cubre el catalogo entero: ningun permiso se queda sin fila")
    void ningunPermisoSeQuedaSinFila() {
        // Si entra un permiso nuevo al catalogo y nadie escribe su fila, este test lo dice. Sin
        // esto, un permiso sin declarar se comporta como "nadie puede" y nadie se entera.
        var conFila = matriz().stream().map(Fila::permiso).toList();

        assertThat(conFila).containsExactlyInAnyOrder(Permiso.values());
    }

    @Test
    @DisplayName("la directiva ve los agregados pero NO la morosidad por unidad")
    void laDirectivaNoVeLaMorosidadPorUnidad() {
        // El segundo criterio de aceptacion del issue #16. Esta aparte de la fila porque es la linea
        // que no se cruza: expone lo que debe un vecino a otro vecino, y 10.1 lo deja fuera salvo
        // que el administrador lo habilite --y esa habilitacion es M2, cuando exista el reporte--.
        assertThat(MatrizDePermisos.alcanceDe(BOARD, Permiso.VIEW_REPORTS)).isEqualTo(ALL);
        assertThat(MatrizDePermisos.alcanceDe(BOARD, Permiso.VIEW_DELINQUENCY_BY_UNIT))
                .isEqualTo(NONE);
    }

    @Test
    @DisplayName("separacion de funciones: quien administra no maneja dinero, y quien maneja dinero no reparte roles")
    void laSeparacionDeFuncionesSeSostiene() {
        // Seguridad 3. Son las dos mitades de la misma regla, y romper cualquiera deja a una sola
        // persona pudiendo darse a si misma el control del dinero.
        assertThat(MatrizDePermisos.alcanceDe(CONDO_ADMIN, Permiso.ISSUE_DUES)).isEqualTo(NONE);
        assertThat(MatrizDePermisos.alcanceDe(CONDO_ADMIN, Permiso.VALIDATE_MANUAL_PAYMENT))
                .isEqualTo(NONE);
        assertThat(MatrizDePermisos.alcanceDe(FINANCE_ADMIN, Permiso.INVITE_USERS))
                .isEqualTo(NONE);
        assertThat(MatrizDePermisos.alcanceDe(FINANCE_ADMIN, Permiso.MANAGE_UNITS))
                .isEqualTo(NONE);
    }

    @Test
    @DisplayName("el doble rol administrativo suma, porque el condominio lo decidio asi")
    void elDobleRolAdministrativoSuma() {
        // 10.1 lo permite explicitamente: en condominios pequenos una sola persona hace las dos
        // cosas. Lo que no puede es pasar desapercibido, y de eso se encarga la auditoria.
        var ambos = Set.of(CONDO_ADMIN, FINANCE_ADMIN);

        assertThat(MatrizDePermisos.alcanceDe(ambos, Permiso.MANAGE_UNITS)).isEqualTo(ALL);
        assertThat(MatrizDePermisos.alcanceDe(ambos, Permiso.ISSUE_DUES)).isEqualTo(ALL);
    }

    @Test
    @DisplayName("el superadministrador no opera condominios")
    void elSuperadministradorNoOperaCondominios() {
        // 10.1: no ve deudas ni pagos por defecto. Crea condominios, pone administradores, lee
        // auditoria y abre soporte. Nada de lo que mueve plata del condominio.
        assertThat(MatrizDePermisos.alcanceDe(PLATFORM_ADMIN, Permiso.VIEW_ACCOUNT_STATEMENT))
                .isEqualTo(NONE);
        assertThat(MatrizDePermisos.alcanceDe(PLATFORM_ADMIN, Permiso.VIEW_REPORTS))
                .isEqualTo(NONE);
        assertThat(MatrizDePermisos.alcanceDe(PLATFORM_ADMIN, Permiso.ISSUE_DUES))
                .isEqualTo(NONE);
        assertThat(MatrizDePermisos.alcanceDe(PLATFORM_ADMIN, Permiso.MANAGE_UNITS))
                .isEqualTo(NONE);
    }

    @Test
    @DisplayName("las tres celdas de step-up son las que mueven dinero")
    void elStepUpEsSoloDondeLoDiceLaMatriz() {
        assertThat(Permiso.values())
                .filteredOn(MatrizDePermisos::pideSegundoFactor)
                .containsExactlyInAnyOrder(
                        Permiso.CONFIGURE_PAYMENT_PROVIDER, Permiso.CANCEL_OBLIGATION, Permiso.REFUND_CREDIT_BALANCE);
    }
}
