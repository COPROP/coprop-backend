package bo.coprop.api;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Sobre uniforme de toda respuesta paginada.
 *
 * <p>No se serializa el {@code Page} de Spring Data directamente: su formato no es contrato
 * estable, cambia entre versiones y arrastra campos internos. Este sobre si es contrato.
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
