package bo.coprop.identity;

import static bo.coprop.identity.Alcance.ALL;
import static bo.coprop.identity.Alcance.OWN;
import static bo.coprop.identity.Permiso.ACCESS_FOR_SUPPORT;
import static bo.coprop.identity.Permiso.CANCEL_OBLIGATION;
import static bo.coprop.identity.Permiso.CONFIGURE_CHARGES;
import static bo.coprop.identity.Permiso.CONFIGURE_PAYMENT_PROVIDER;
import static bo.coprop.identity.Permiso.CREATE_CONDOMINIUMS;
import static bo.coprop.identity.Permiso.GENERATE_QR;
import static bo.coprop.identity.Permiso.INVITE_ADMINS_ONLY;
import static bo.coprop.identity.Permiso.INVITE_USERS;
import static bo.coprop.identity.Permiso.ISSUE_DUES;
import static bo.coprop.identity.Permiso.MANAGE_AMENITIES;
import static bo.coprop.identity.Permiso.MANAGE_UNITS;
import static bo.coprop.identity.Permiso.OPERATE_AMENITIES;
import static bo.coprop.identity.Permiso.RECONCILE;
import static bo.coprop.identity.Permiso.RECORD_METER_READINGS;
import static bo.coprop.identity.Permiso.REFUND_CREDIT_BALANCE;
import static bo.coprop.identity.Permiso.RESERVE;
import static bo.coprop.identity.Permiso.UPLOAD_RECEIPT;
import static bo.coprop.identity.Permiso.VALIDATE_MANUAL_PAYMENT;
import static bo.coprop.identity.Permiso.VIEW_ACCOUNT_STATEMENT;
import static bo.coprop.identity.Permiso.VIEW_AUDIT_LOG;
import static bo.coprop.identity.Permiso.VIEW_CREDIT_BALANCES;
import static bo.coprop.identity.Permiso.VIEW_DELINQUENCY_BY_UNIT;
import static bo.coprop.identity.Permiso.VIEW_REPORTS;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * La matriz de Seguridad 3, transcrita. Es el unico sitio donde se decide quien puede que.
 *
 * <p>Esta en {@code identity} y no en {@code api} porque quien puede que no es una cuestion de
 * HTTP: es la misma respuesta para una peticion web, para la app movil y para un worker. La capa
 * web solo pregunta.
 *
 * <p><strong>Es codigo y no una tabla</strong>, a proposito. El issue #16 lo pide --"permisos
 * derivados del rol, no almacenados por usuario"-- y la razon practica es que una tabla se edita a
 * mano: el dia que alguien cambie una fila en produccion, el analisis dira una cosa y el sistema
 * otra, y nadie se enterara hasta que alguien vea lo que no debia. Aqui un cambio pasa por un PR y
 * rompe un test.
 *
 * <p>Estan las veintiuna filas, incluidas las de acciones cuyos endpoints son de M2 y M3. No es
 * adelantarse: la decision ya esta tomada en el analisis, y escribirla entera permite probarla fila
 * por fila hoy. Lo que llega con cada funcionalidad es la anotacion que exige el permiso.
 *
 * <p><strong>Lo que esta matriz no decide</strong>: si <em>este</em> recurso es tuyo. Eso es
 * {@link Alcance#OWN}, y lo resuelve {@code ControlDeAcceso} en el modulo {@code api} (issue
 * #15), que es el unico sitio donde se ven a la vez la matriz y las relaciones de la unidad. Y si
 * hizo falta segundo factor hace poco, que es {@link #pideSegundoFactor} y lo hara cumplir el #22.
 */
public final class MatrizDePermisos {

    private static final Map<MembershipRole, Map<Permiso, Alcance>> MATRIZ = construir();

    /**
     * Lo que no se hace sin haber reautenticado hace poco (Seguridad 7.3).
     *
     * <p>Son las tres celdas que la matriz marca "Sí (step-up)", y las tres mueven dinero o abren la
     * puerta por donde entra: cambiar las credenciales del banco, anular una deuda y devolver plata.
     * Quien las haga con una sesion abierta y ajena hace dano de verdad.
     */
    private static final Set<Permiso> CON_SEGUNDO_FACTOR =
            EnumSet.of(CONFIGURE_PAYMENT_PROVIDER, CANCEL_OBLIGATION, REFUND_CREDIT_BALANCE);

    private MatrizDePermisos() {}

    /** Hasta donde llega ese rol para ese permiso. Nunca nulo: lo que no se declara es NONE. */
    public static Alcance alcanceDe(MembershipRole rol, Permiso permiso) {
        return MATRIZ.getOrDefault(rol, Map.of()).getOrDefault(permiso, Alcance.NONE);
    }

    /** El alcance mayor que dan los roles de una membresia, que puede tener varios. */
    public static Alcance alcanceDe(Set<MembershipRole> roles, Permiso permiso) {
        Alcance mayor = Alcance.NONE;
        for (MembershipRole rol : roles) {
            Alcance suyo = alcanceDe(rol, permiso);
            if (suyo.compareTo(mayor) > 0) {
                mayor = suyo;
            }
        }
        return mayor;
    }

    public static boolean pideSegundoFactor(Permiso permiso) {
        return CON_SEGUNDO_FACTOR.contains(permiso);
    }

    /**
     * Si ese rol es administrativo, que es lo que acota a quien puede invitar
     * {@code PLATFORM_ADMIN}: la celda dice "Solo administradores".
     */
    public static boolean esAdministrativo(MembershipRole rol) {
        return rol == MembershipRole.PLATFORM_ADMIN
                || rol == MembershipRole.CONDO_ADMIN
                || rol == MembershipRole.FINANCE_ADMIN;
    }

    private static Map<MembershipRole, Map<Permiso, Alcance>> construir() {
        var matriz = new EnumMap<MembershipRole, Map<Permiso, Alcance>>(MembershipRole.class);

        // El superadministrador NO opera condominios (Seguridad 10.1): no ve deudas ni pagos. Crea
        // condominios, pone su primer administrador, lee auditoria, y para soporte abre un acceso
        // temporal con motivo. Nada mas.
        matriz.put(
                MembershipRole.PLATFORM_ADMIN,
                Map.of(
                        CREATE_CONDOMINIUMS, ALL,
                        INVITE_ADMINS_ONLY, ALL,
                        CONFIGURE_PAYMENT_PROVIDER, ALL,
                        VIEW_AUDIT_LOG, ALL,
                        ACCESS_FOR_SUPPORT, ALL));

        // Administra personas, unidades y areas comunes. NO maneja dinero: es la separacion de
        // funciones de Seguridad 3. Si ve reportes y saldos, que es mirar, no mover.
        var administracion = new EnumMap<Permiso, Alcance>(Permiso.class);
        administracion.put(MANAGE_UNITS, ALL);
        administracion.put(INVITE_USERS, ALL);
        administracion.put(RECORD_METER_READINGS, ALL);
        administracion.put(MANAGE_AMENITIES, ALL);
        administracion.put(OPERATE_AMENITIES, ALL);
        administracion.put(RESERVE, ALL);
        administracion.put(VIEW_ACCOUNT_STATEMENT, ALL);
        administracion.put(VIEW_CREDIT_BALANCES, ALL);
        administracion.put(VIEW_REPORTS, ALL);
        administracion.put(VIEW_DELINQUENCY_BY_UNIT, ALL);
        administracion.put(VIEW_AUDIT_LOG, ALL);
        matriz.put(MembershipRole.CONDO_ADMIN, administracion);

        // Maneja el dinero y NO puede asignarse roles, que es la otra mitad de la separacion de
        // funciones: si pudiera, se daria CONDO_ADMIN y la separacion no existiria.
        var finanzas = new EnumMap<Permiso, Alcance>(Permiso.class);
        finanzas.put(CONFIGURE_PAYMENT_PROVIDER, ALL);
        finanzas.put(CONFIGURE_CHARGES, ALL);
        finanzas.put(ISSUE_DUES, ALL);
        finanzas.put(CANCEL_OBLIGATION, ALL);
        finanzas.put(VALIDATE_MANUAL_PAYMENT, ALL);
        finanzas.put(RECONCILE, ALL);
        finanzas.put(REFUND_CREDIT_BALANCE, ALL);
        finanzas.put(VIEW_ACCOUNT_STATEMENT, ALL);
        finanzas.put(VIEW_CREDIT_BALANCES, ALL);
        finanzas.put(VIEW_REPORTS, ALL);
        finanzas.put(VIEW_DELINQUENCY_BY_UNIT, ALL);
        finanzas.put(VIEW_AUDIT_LOG, ALL);
        matriz.put(MembershipRole.FINANCE_ADMIN, finanzas);

        // El propietario y el inquilino ven y hacen lo SUYO. La diferencia entre ellos no esta en
        // esta matriz: esta en que relacion tienen con la unidad, y por eso las dos columnas son
        // identicas. Quien las separa es ControlDeAcceso, que pregunta por las tres relaciones
        // --titularidad, ocupacion y responsabilidad de pago-- y no solo por la titularidad.
        var propietario = Map.of(
                VIEW_ACCOUNT_STATEMENT, OWN,
                VIEW_CREDIT_BALANCES, OWN,
                GENERATE_QR, OWN,
                UPLOAD_RECEIPT, OWN,
                RESERVE, OWN);
        matriz.put(MembershipRole.OWNER, propietario);
        matriz.put(MembershipRole.TENANT, propietario);

        // Porteria: lecturas y el dia a dia de las areas comunes. No ve dinero.
        matriz.put(MembershipRole.MAINTENANCE, Map.of(RECORD_METER_READINGS, ALL, OPERATE_AMENITIES, ALL));

        // La directiva mira y no toca, y lo que mira son MONTOS AGREGADOS. Que no tenga
        // VIEW_DELINQUENCY_BY_UNIT es el criterio de aceptacion del issue #16, no un olvido: el
        // detalle por unidad expone lo que debe un vecino a otro vecino.
        matriz.put(MembershipRole.BOARD, Map.of(VIEW_REPORTS, ALL));

        return matriz;
    }
}
