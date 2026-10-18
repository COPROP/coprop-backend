package bo.coprop.identity;

/**
 * Lo que se puede hacer en COPROP, una entrada por fila de la matriz de Seguridad 3.
 *
 * <p><strong>Los permisos se derivan del rol, no se guardan por usuario.</strong> Lo exige el issue
 * #16, y la razon es que una tabla de permisos por persona se desincroniza con la matriz en cuanto
 * alguien edita una fila a mano: a partir de ahi el documento dice una cosa y la base otra, y nadie
 * se entera hasta que alguien ve lo que no debia.
 *
 * <p>Aqui esta el catalogo entero, incluidas las acciones cuyos endpoints todavia no existen. No es
 * adelantarse: la matriz es una decision ya tomada y escribirla completa permite probarla fila por
 * fila hoy. Lo que llega con cada funcionalidad es la anotacion que la exige, no la decision de
 * quien puede.
 *
 * <p>Los nombres van en español porque el analisis no los nombra: nombra las acciones en prosa.
 */
public enum Permiso {

    // --- Alcance de plataforma ---

    /** Crear condominios y asignar su primer administrador. Solo {@code PLATFORM_ADMIN}. */
    CREATE_CONDOMINIUMS,

    /**
     * Acceder a los datos de un condominio para dar soporte.
     *
     * <p>Seguridad 10.1 lo acota: con motivo registrado y acceso temporal, visible para el
     * administrador del condominio. El motivo y la caducidad son del issue <strong>#21</strong>,
     * que es quien trae la bitacora; aqui solo vive quien puede.
     */
    ACCESS_FOR_SUPPORT,

    // --- Administracion del condominio ---

    /** Gestionar bloques, unidades y personas. */
    MANAGE_UNITS,

    /**
     * Invitar usuarios y asignar roles.
     *
     * <p>{@code CONDO_ADMIN} puede asignar cualquiera, incluidos {@code CONDO_ADMIN} y
     * {@code FINANCE_ADMIN} (decision de 10.1, que queda en auditoria). {@code PLATFORM_ADMIN}
     * solo administradores: lo acota {@link #INVITE_ADMINS_ONLY}.
     */
    INVITE_USERS,

    /**
     * Invitar, pero solo a roles administrativos.
     *
     * <p>Es el "Solo administradores" de la celda de {@code PLATFORM_ADMIN}. Existe como permiso
     * aparte y no como matiz de {@link #INVITE_USERS} porque es una regla distinta --acota a
     * quien, no si-- y mezclarlas obligaria a que cada comprobacion supiera cual de las dos es.
     */
    INVITE_ADMINS_ONLY,

    /** Gestionar areas comunes y sus reglas de reserva. */
    MANAGE_AMENITIES,

    /** Ver las reservas del dia y bloquear areas por mantenimiento. */
    OPERATE_AMENITIES,

    /** Registrar lecturas de medidores. */
    RECORD_METER_READINGS,

    // --- Dinero ---

    /** Configurar el proveedor de pagos y las credenciales bancarias. Pide segundo factor. */
    CONFIGURE_PAYMENT_PROVIDER,

    /** Configurar conceptos, tarifas de agua y politica de mora. */
    CONFIGURE_CHARGES,

    /** Emitir las expensas y el cobro de agua del periodo. */
    ISSUE_DUES,

    /** Anular una obligacion. Pide segundo factor. */
    CANCEL_OBLIGATION,

    /** Validar un pago manual. */
    VALIDATE_MANUAL_PAYMENT,

    /** Trabajar la bandeja de excepciones y la conciliacion. */
    RECONCILE,

    /** Registrar la devolucion de un saldo a favor. Pide segundo factor. */
    REFUND_CREDIT_BALANCE,

    // --- Lo que cada uno ve de lo suyo ---

    /** Ver el estado de cuenta. */
    VIEW_ACCOUNT_STATEMENT,

    /** Ver los saldos a favor. */
    VIEW_CREDIT_BALANCES,

    /** Generar el QR de pago. */
    GENERATE_QR,

    /** Subir el comprobante de un pago manual. */
    UPLOAD_RECEIPT,

    /** Crear o cancelar reservas. */
    RESERVE,

    // --- Reportes ---

    /**
     * Ver los reportes de recaudacion y morosidad, en montos agregados.
     *
     * <p>Esto es lo que ve la directiva. El detalle por unidad es otro permiso, y no lo tiene.
     */
    VIEW_REPORTS,

    /**
     * Ver la morosidad <strong>unidad por unidad</strong>.
     *
     * <p>Es una fila aparte y no un matiz de {@link #VIEW_REPORTS} porque es justo la linea que el
     * criterio del issue #16 exige que no se cruce: <em>un usuario BOARD no puede obtener morosidad
     * por unidad con la configuracion por defecto</em>. Expone datos financieros de un vecino a
     * otro, y 10.1 lo deja fuera salvo que el administrador lo habilite explicitamente --esa
     * habilitacion por condominio es M2, cuando exista el reporte--.
     */
    VIEW_DELINQUENCY_BY_UNIT,

    /** Consultar la auditoria del condominio. */
    VIEW_AUDIT_LOG
}
