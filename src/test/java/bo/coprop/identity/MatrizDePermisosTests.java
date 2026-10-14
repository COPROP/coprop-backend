package bo.coprop.identity;

import static bo.coprop.identity.Alcance.NINGUNO;
import static bo.coprop.identity.Alcance.PROPIO;
import static bo.coprop.identity.Alcance.TODO;
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
            String accion,
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
                        Permiso.CREAR_CONDOMINIOS,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Configurar proveedor de pagos y credenciales bancarias",
                        Permiso.CONFIGURAR_PROVEEDOR_DE_PAGOS,
                        TODO,
                        NINGUNO,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Gestionar bloques, unidades y personas",
                        Permiso.GESTIONAR_UNIDADES,
                        NINGUNO,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Invitar usuarios y asignar roles",
                        Permiso.INVITAR_USUARIOS,
                        NINGUNO,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Invitar, pero solo a administradores",
                        Permiso.INVITAR_SOLO_ADMINISTRADORES,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Configurar conceptos, tarifas de agua y politica de mora",
                        Permiso.CONFIGURAR_COBROS,
                        NINGUNO,
                        NINGUNO,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Emitir expensas y cobro de agua del periodo",
                        Permiso.EMITIR_EXPENSAS,
                        NINGUNO,
                        NINGUNO,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Registrar lecturas de medidores",
                        Permiso.REGISTRAR_LECTURAS,
                        NINGUNO,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        TODO,
                        NINGUNO),
                new Fila(
                        "Anular una obligacion",
                        Permiso.ANULAR_OBLIGACION,
                        NINGUNO,
                        NINGUNO,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Ver estado de cuenta",
                        Permiso.VER_ESTADO_DE_CUENTA,
                        NINGUNO,
                        TODO,
                        TODO,
                        PROPIO,
                        PROPIO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Generar QR de pago",
                        Permiso.GENERAR_QR,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        PROPIO,
                        PROPIO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Subir comprobante de pago manual",
                        Permiso.SUBIR_COMPROBANTE,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        PROPIO,
                        PROPIO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Validar pago manual",
                        Permiso.VALIDAR_PAGO_MANUAL,
                        NINGUNO,
                        NINGUNO,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Bandeja de excepciones y conciliacion",
                        Permiso.CONCILIAR,
                        NINGUNO,
                        NINGUNO,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Ver saldos a favor",
                        Permiso.VER_SALDOS_A_FAVOR,
                        NINGUNO,
                        TODO,
                        TODO,
                        PROPIO,
                        PROPIO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Registrar devolucion de saldo a favor",
                        Permiso.DEVOLVER_SALDO_A_FAVOR,
                        NINGUNO,
                        NINGUNO,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Gestionar areas comunes y reglas de reserva",
                        Permiso.GESTIONAR_AREAS_COMUNES,
                        NINGUNO,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Crear o cancelar reservas",
                        Permiso.RESERVAR,
                        NINGUNO,
                        TODO,
                        NINGUNO,
                        PROPIO,
                        PROPIO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Ver reservas del dia y bloquear areas por mantenimiento",
                        Permiso.OPERAR_AREAS_COMUNES,
                        NINGUNO,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        TODO,
                        NINGUNO),
                new Fila(
                        "Reportes de recaudacion y morosidad (agregados)",
                        Permiso.VER_REPORTES,
                        NINGUNO,
                        TODO,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        TODO),
                new Fila(
                        "Morosidad unidad por unidad",
                        Permiso.VER_MOROSIDAD_POR_UNIDAD,
                        NINGUNO,
                        TODO,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Consultar auditoria del condominio",
                        Permiso.CONSULTAR_AUDITORIA,
                        TODO,
                        TODO,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO),
                new Fila(
                        "Acceder a datos de un condominio para soporte",
                        Permiso.ACCEDER_PARA_SOPORTE,
                        TODO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO,
                        NINGUNO));
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
        assertThat(MatrizDePermisos.alcanceDe(BOARD, Permiso.VER_REPORTES)).isEqualTo(TODO);
        assertThat(MatrizDePermisos.alcanceDe(BOARD, Permiso.VER_MOROSIDAD_POR_UNIDAD))
                .isEqualTo(NINGUNO);
    }

    @Test
    @DisplayName("separacion de funciones: quien administra no maneja dinero, y quien maneja dinero no reparte roles")
    void laSeparacionDeFuncionesSeSostiene() {
        // Seguridad 3. Son las dos mitades de la misma regla, y romper cualquiera deja a una sola
        // persona pudiendo darse a si misma el control del dinero.
        assertThat(MatrizDePermisos.alcanceDe(CONDO_ADMIN, Permiso.EMITIR_EXPENSAS))
                .isEqualTo(NINGUNO);
        assertThat(MatrizDePermisos.alcanceDe(CONDO_ADMIN, Permiso.VALIDAR_PAGO_MANUAL))
                .isEqualTo(NINGUNO);
        assertThat(MatrizDePermisos.alcanceDe(FINANCE_ADMIN, Permiso.INVITAR_USUARIOS))
                .isEqualTo(NINGUNO);
        assertThat(MatrizDePermisos.alcanceDe(FINANCE_ADMIN, Permiso.GESTIONAR_UNIDADES))
                .isEqualTo(NINGUNO);
    }

    @Test
    @DisplayName("el doble rol administrativo suma, porque el condominio lo decidio asi")
    void elDobleRolAdministrativoSuma() {
        // 10.1 lo permite explicitamente: en condominios pequenos una sola persona hace las dos
        // cosas. Lo que no puede es pasar desapercibido, y de eso se encarga la auditoria.
        var ambos = Set.of(CONDO_ADMIN, FINANCE_ADMIN);

        assertThat(MatrizDePermisos.alcanceDe(ambos, Permiso.GESTIONAR_UNIDADES))
                .isEqualTo(TODO);
        assertThat(MatrizDePermisos.alcanceDe(ambos, Permiso.EMITIR_EXPENSAS)).isEqualTo(TODO);
    }

    @Test
    @DisplayName("el superadministrador no opera condominios")
    void elSuperadministradorNoOperaCondominios() {
        // 10.1: no ve deudas ni pagos por defecto. Crea condominios, pone administradores, lee
        // auditoria y abre soporte. Nada de lo que mueve plata del condominio.
        assertThat(MatrizDePermisos.alcanceDe(PLATFORM_ADMIN, Permiso.VER_ESTADO_DE_CUENTA))
                .isEqualTo(NINGUNO);
        assertThat(MatrizDePermisos.alcanceDe(PLATFORM_ADMIN, Permiso.VER_REPORTES))
                .isEqualTo(NINGUNO);
        assertThat(MatrizDePermisos.alcanceDe(PLATFORM_ADMIN, Permiso.EMITIR_EXPENSAS))
                .isEqualTo(NINGUNO);
        assertThat(MatrizDePermisos.alcanceDe(PLATFORM_ADMIN, Permiso.GESTIONAR_UNIDADES))
                .isEqualTo(NINGUNO);
    }

    @Test
    @DisplayName("las tres celdas de step-up son las que mueven dinero")
    void elStepUpEsSoloDondeLoDiceLaMatriz() {
        assertThat(Permiso.values())
                .filteredOn(MatrizDePermisos::pideSegundoFactor)
                .containsExactlyInAnyOrder(
                        Permiso.CONFIGURAR_PROVEEDOR_DE_PAGOS,
                        Permiso.ANULAR_OBLIGACION,
                        Permiso.DEVOLVER_SALDO_A_FAVOR);
    }
}
