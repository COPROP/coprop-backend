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
    CREAR_CONDOMINIOS,

    /**
     * Acceder a los datos de un condominio para dar soporte.
     *
     * <p>Seguridad 10.1 lo acota: con motivo registrado y acceso temporal, visible para el
     * administrador del condominio. <strong>Eso no existe todavia y no tiene ticket.</strong> Se le
     * atribuia al issue #21, que trajo la bitacora y cerro sin el motivo ni la caducidad, asi que la
     * referencia quedo colgada. Hoy el acceso de soporte no tiene entidad ni endpoint: el permiso
     * solo vive aqui y en {@link MatrizDePermisos}, donde dice quien puede y nada mas.
     */
    ACCEDER_PARA_SOPORTE,

    // --- Administracion del condominio ---

    /** Gestionar bloques, unidades y personas. */
    GESTIONAR_UNIDADES,

    /**
     * Invitar usuarios y asignar roles.
     *
     * <p>{@code CONDO_ADMIN} puede asignar cualquiera, incluidos {@code CONDO_ADMIN} y
     * {@code FINANCE_ADMIN} (decision de 10.1, que queda en auditoria). {@code PLATFORM_ADMIN}
     * solo administradores: lo acota {@link #INVITAR_SOLO_ADMINISTRADORES}.
     */
    INVITAR_USUARIOS,

    /**
     * Invitar, pero solo a roles administrativos.
     *
     * <p>Es el "Solo administradores" de la celda de {@code PLATFORM_ADMIN}. Existe como permiso
     * aparte y no como matiz de {@link #INVITAR_USUARIOS} porque es una regla distinta --acota a
     * quien, no si-- y mezclarlas obligaria a que cada comprobacion supiera cual de las dos es.
     */
    INVITAR_SOLO_ADMINISTRADORES,

    /** Gestionar areas comunes y sus reglas de reserva. */
    GESTIONAR_AREAS_COMUNES,

    /** Ver las reservas del dia y bloquear areas por mantenimiento. */
    OPERAR_AREAS_COMUNES,

    /** Registrar lecturas de medidores. */
    REGISTRAR_LECTURAS,

    // --- Dinero ---

    /** Configurar el proveedor de pagos y las credenciales bancarias. Pide segundo factor. */
    CONFIGURAR_PROVEEDOR_DE_PAGOS,

    /** Configurar conceptos, tarifas de agua y politica de mora. */
    CONFIGURAR_COBROS,

    /** Emitir las expensas y el cobro de agua del periodo. */
    EMITIR_EXPENSAS,

    /** Anular una obligacion. Pide segundo factor. */
    ANULAR_OBLIGACION,

    /** Validar un pago manual. */
    VALIDAR_PAGO_MANUAL,

    /** Trabajar la bandeja de excepciones y la conciliacion. */
    CONCILIAR,

    /** Registrar la devolucion de un saldo a favor. Pide segundo factor. */
    DEVOLVER_SALDO_A_FAVOR,

    // --- Lo que cada uno ve de lo suyo ---

    /** Ver el estado de cuenta. */
    VER_ESTADO_DE_CUENTA,

    /** Ver los saldos a favor. */
    VER_SALDOS_A_FAVOR,

    /** Generar el QR de pago. */
    GENERAR_QR,

    /** Subir el comprobante de un pago manual. */
    SUBIR_COMPROBANTE,

    /** Crear o cancelar reservas. */
    RESERVAR,

    // --- Reportes ---

    /**
     * Ver los reportes de recaudacion y morosidad, en montos agregados.
     *
     * <p>Esto es lo que ve la directiva. El detalle por unidad es otro permiso, y no lo tiene.
     */
    VER_REPORTES,

    /**
     * Ver la morosidad <strong>unidad por unidad</strong>.
     *
     * <p>Es una fila aparte y no un matiz de {@link #VER_REPORTES} porque es justo la linea que el
     * criterio del issue #16 exige que no se cruce: <em>un usuario BOARD no puede obtener morosidad
     * por unidad con la configuracion por defecto</em>. Expone datos financieros de un vecino a
     * otro, y 10.1 lo deja fuera salvo que el administrador lo habilite explicitamente --esa
     * habilitacion por condominio es M2, cuando exista el reporte--.
     */
    VER_MOROSIDAD_POR_UNIDAD,

    /** Consultar la auditoria del condominio. */
    CONSULTAR_AUDITORIA
}
