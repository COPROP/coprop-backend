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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * La consulta de la bitacora de un condominio. Segundo criterio del issue #21.
 *
 * <p>Cuelga del condominio, como todo lo demas, y con eso el filtro del #17 ya garantiza que quien
 * llega aqui es miembro activo. Encima pide {@code CONSULTAR_AUDITORIA}, que la matriz de Seguridad
 * 3 da a los dos administradores y al superadministrador, y a nadie mas: la bitacora dice que hizo
 * cada vecino.
 *
 * <p><strong>Es el primer endpoint paginado del sistema</strong>, y es la razon por la que
 * {@link Pagina} se movio de {@code api} a {@code shared}: este controlador vive en {@code audit}, y
 * un modulo de dominio no puede depender de {@code api} (regla 6). Estaba anotado como deuda desde
 * el issue #10.
 */
@ControladorDeApi
@RestController
@RequestMapping("/condominios/{condominiumId}/auditoria")
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
     * <p>Los tres filtros del criterio, todos opcionales y combinables. Las fechas van en ISO-8601
     * con zona ({@code 2026-10-14T12:00:00Z}), que es como las devuelve la propia respuesta: asi lo
     * que se lee en una fila se puede pegar tal cual como filtro.
     *
     * @param actor identificador de cuenta de quien hizo la accion
     * @param entidad tipo de cosa sobre la que se actuo: {@code membresia}, {@code invitacion},
     *     {@code configuracion}, {@code cuenta}
     * @param desde inclusive
     * @param hasta exclusive, para que dos rangos contiguos no repitan una fila
     */
    @PreAuthorize("@autorizacion.puede(authentication, 'CONSULTAR_AUDITORIA')")
    @GetMapping
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
}
