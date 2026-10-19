package bo.coprop.identity;

/**
 * Estado de una membresia.
 *
 * <p>No hay valor para "terminada": eso lo dice la vigencia cerrando la fila con {@code validTo}.
 * Un estado y una vigencia que signifiquen lo mismo acaban contradiciendose, y la vigencia es la
 * que ademas guarda cuando paso.
 *
 * <p>Esa decision es la que el issue <strong>#19</strong> hizo valer: la baja cierra la fila y la
 * suspension cambia el estado dejandola abierta, asi que una membresia suspendida sigue siendo del
 * condominio y una cerrada ya no. Cerrarla <strong>no toca el estado</strong>, de modo que la fila
 * conserva como estaba cuando se la dio de baja.
 */
public enum MembershipStatus {
    ACTIVE,
    SUSPENDED
}
