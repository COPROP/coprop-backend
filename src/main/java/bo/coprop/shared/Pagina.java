package bo.coprop.shared;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Sobre uniforme de toda respuesta paginada.
 *
 * <p>No se serializa el {@code Page} de Spring Data directamente: su formato no es contrato
 * estable, cambia entre versiones y arrastra campos internos. Este sobre si es contrato.
 *
 * <p><strong>Vive en {@code shared} y no en {@code api}</strong>, desde el issue #21. Nacio en
 * {@code api} con el contrato del #5, cuando ningun endpoint paginaba todavia; el primero que lo
 * hace es la consulta de auditoria, y su controlador esta en {@code audit}, que no puede depender de
 * {@code api} (regla 6). Es el mismo traslado que ya hizo {@code @ControladorDeApi} y por el mismo
 * motivo: un tipo que todo modulo necesita para hablar por HTTP no puede obligar a depender de quien
 * sabe de HTTP. Estaba anotado como deuda desde el #10, que por esto no trajo listado de unidades.
 *
 * @param contenido los elementos de esta pagina
 * @param pagina indice de la pagina, empezando en 0
 * @param tamano elementos por pagina solicitados
 * @param total elementos que hay en total
 * @param totalPaginas paginas que hay en total
 */
public record Pagina<T>(List<T> contenido, int pagina, int tamano, long total, int totalPaginas) {

    /** Envuelve una pagina de Spring Data convirtiendo cada elemento a su representacion de API. */
    public static <E, T> Pagina<T> de(Page<E> pagina, Function<E, T> aDto) {
        return new Pagina<>(
                pagina.getContent().stream().map(aDto).toList(),
                pagina.getNumber(),
                pagina.getSize(),
                pagina.getTotalElements(),
                pagina.getTotalPages());
    }
}
