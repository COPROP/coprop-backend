package bo.coprop.identity;

/**
 * Los siete roles del catalogo de Seguridad 3.
 *
 * <p>Aqui esta solo el enum. El catalogo {@code Role} con su alcance --PLATAFORMA o CONDOMINIO-- y
 * la matriz de permisos son el issue <strong>#16</strong>, que es tambien quien desbloquea la
 * restriccion de superadministrador que arrastran el #9 y el #10. Por eso
 * {@link #PLATFORM_ADMIN} se admite en una membresia como cualquier otro: acotarlo ahora seria
 * adelantarse a esa decision.
 *
 * <p>Los nombres estan en ingles porque los nombra asi el analisis, igual que las entidades.
 */
public enum MembershipRole {
    /** Alcance de plataforma, no de condominio. Lo acotara el #16. */
    PLATFORM_ADMIN,
    CONDO_ADMIN,
    FINANCE_ADMIN,
    OWNER,
    TENANT,
    MAINTENANCE,
    BOARD
}
