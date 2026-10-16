package bo.coprop.audit.internal;

import bo.coprop.audit.AuditEntryView;
import bo.coprop.audit.AuditTrail;
import bo.coprop.shared.ControladorDeApi;
import bo.coprop.shared.Pagina;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las dos consultas de la bitacora: la de un condominio (issue #21) y la de la plataforma
 * (issue <strong>#20</strong>).
 *
 * <p>Van en el mismo controlador aunque sus rutas no se parezcan --una cuelga del condominio y la
 * otra es global-- porque lo que de verdad hay que leer junto es <strong>en que se diferencian</strong>:
 * son dos publicos distintos sobre la misma tabla, y la linea que los separa es la columna
 * {@code condominium_id}. Por eso esta clase no tiene {@code @RequestMapping} y cada metodo escribe
 * su ruta entera.
 *
 * <p>Las dos piden {@code CONSULTAR_AUDITORIA}, y el <strong>alcance de la ruta hace el resto</strong>:
 *
 * <ul>
 *   <li>En la del condominio, el filtro del #17 ya garantizo que quien llega es miembro activo, y
 *       las autoridades de la peticion son las de su membresia. La matriz da ese permiso a los dos
 *       administradores del condominio y al superadministrador --que no opera, pero audita
 *       (Seguridad 10.1)-- y a nadie mas: la bitacora dice que hizo cada vecino.
 *   <li>En la global no corre el filtro de condominio, asi que las unicas autoridades son las que
 *       puso el de identidad: la de plataforma, si la tiene. Un {@code CONDO_ADMIN} llega sin
 *       ninguna y recibe 403 <strong>con el mismo permiso</strong>, sin una regla escrita aparte.
 *       Eso es lo que se queria: las acciones de la plataforma entera no son suyas.
 * </ul>
 *
 * <p><strong>Es el primer endpoint paginado del sistema</strong>, y es la razon por la que
 * {@link Pagina} se movio de {@code api} a {@code shared}: este controlador vive en {@code audit}, y
 * un modulo de dominio no puede depender de {@code api} (regla 6). Estaba anotado como deuda desde
 * el issue #10.
 */
@ControladorDeApi
@RestController
class AuditController {

    /**
     * El tamaño de pagina por defecto y el maximo.
     *
     * <p>El maximo no es desconfianza: sin el, un {@code tamano=100000} convierte la consulta en el
     * listado completo que la paginacion existe para evitar, y lo hace desde la primera vez que
     * alguien lo prueba.
     */
    private static final int TAMANO_POR_DEFECTO = 50;

    private static final int TAMANO_MAXIMO = 200;

    private final AuditTrail bitacora;

    AuditController(AuditTrail bitacora) {
        this.bitacora = bitacora;
    }

    /**
     * Las acciones del condominio, de la mas reciente a la mas vieja.
     *
     * <p>Los tres filtros del criterio del #21, todos opcionales y combinables. Las fechas van en
     * ISO-8601 con zona ({@code 2026-10-14T12:00:00Z}), que es como las devuelve la propia respuesta:
     * asi lo que se lee en una fila se puede pegar tal cual como filtro.
     *
     * @param actor identificador de cuenta de quien hizo la accion
     * @param entidad tipo de cosa sobre la que se actuo: {@code membresia}, {@code invitacion},
     *     {@code configuracion}, {@code cuenta}
     * @param desde inclusive
     * @param hasta exclusive, para que dos rangos contiguos no repitan una fila
     */
    @PreAuthorize("@autorizacion.puede(authentication, 'CONSULTAR_AUDITORIA')")
    @GetMapping("/condominios/{condominiumId}/auditoria")
    Pagina<AuditEntryView> consultar(
            @PathVariable UUID condominiumId,
            @RequestParam(required = false) @Nullable @Size(max = 100) String actor,
            @RequestParam(required = false) @Nullable @Size(max = 40) String entidad,
            @RequestParam(required = false) @Nullable Instant desde,
            @RequestParam(required = false) @Nullable Instant hasta,
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "" + TAMANO_POR_DEFECTO) @Min(1) @Max(TAMANO_MAXIMO) int tamano) {
        return bitacora.ofCondominium(condominiumId, actor, entidad, desde, hasta, pagina, tamano);
    }

    /**
     * Las acciones de plataforma: las que no cuelgan de ningun condominio. Issue #20.
     *
     * <p>Hoy son tres --conceder y revocar el rol de superadministrador, y el primer acceso de una
     * cuenta-- y las dos primeras llegan con este issue, que es el que trae la operacion de
     * concederlo. Antes de el, la unica fila de plataforma la escribia el arranque y no habia a quien
     * ensenarsela.
     *
     * <p>Mismos filtros y misma paginacion que la del condominio: lo que cambia es que filas se ven,
     * no como se piden.
     */
    @PreAuthorize("@autorizacion.puede(authentication, 'CONSULTAR_AUDITORIA')")
    @GetMapping("/auditoria")
    Pagina<AuditEntryView> consultarLaDePlataforma(
            @RequestParam(required = false) @Nullable @Size(max = 100) String actor,
            @RequestParam(required = false) @Nullable @Size(max = 40) String entidad,
            @RequestParam(required = false) @Nullable Instant desde,
            @RequestParam(required = false) @Nullable Instant hasta,
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "" + TAMANO_POR_DEFECTO) @Min(1) @Max(TAMANO_MAXIMO) int tamano) {
        return bitacora.ofPlatform(actor, entidad, desde, hasta, pagina, tamano);
    }
}
