package bo.coprop.identity;

/**
 * Los siete roles del catalogo de Seguridad 3.
 *
 * <p>Aqui esta solo el enum. La matriz de permisos es {@link PermissionMatrix}, del issue
 * <strong>#16</strong>, y el alcance de cada rol --plataforma o condominio-- lo hacen valer dos
 * sitios: {@code Memberships.grant} y {@code Memberships.changeRoles} rechazan
 * {@link #PLATFORM_ADMIN} en una membresia, y {@code Identities.grantPlatformAdmin} es la unica
 * puerta por la que ese rol se concede.
 *
 * <p>Los nombres estan en ingles porque los nombra asi el analisis, igual que las entidades.
 */
public enum MembershipRole {
    /**
     * Scope de plataforma, no de condominio: vive en su propia tabla y no en una membresia. Se
     * concede y se revoca por {@link Identities}, desde el issue <strong>#20</strong>.
     */
    PLATFORM_ADMIN,
    CONDO_ADMIN,
    FINANCE_ADMIN,
    OWNER,
    TENANT,
    MAINTENANCE,
    BOARD
}
