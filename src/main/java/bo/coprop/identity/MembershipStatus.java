package bo.coprop.identity;

/**
 * Estado de una membresia.
 *
 * <p>No hay valor para "terminada": eso lo dice la vigencia cerrando la fila con {@code validTo}.
 * Un estado y una vigencia que signifiquen lo mismo acaban contradiciendose, y la vigencia es la
 * que ademas guarda cuando paso. La suspension y la baja las trabaja el issue <strong>#19</strong>.
 */
public enum MembershipStatus {
    ACTIVA,
    SUSPENDIDA
}
