package bo.coprop.api;

import java.util.List;
import java.util.Map;

/**
 * Lo que devuelve {@code GET /api/v1/catalogs}: las enumeraciones del dominio. Issue #89.
 *
 * <p>Existe porque el catalogo es fijo en el MVP y la alternativa era que el cliente copiara los
 * valores a mano. Una copia se desincroniza en cuanto se añade un valor, y nadie se entera hasta que
 * un desplegable no ofrece una opcion que la API si acepta --o ofrece una que rechaza--.
 *
 * <p><strong>Las claves son el nombre de la enumeracion, no uno traducido.</strong> El analisis 5.2
 * nombra las entidades y sus enumeraciones en ingles, y reproducirlo aqui deja cero traduccion entre
 * leer esa seccion y leer la respuesta. Es la misma razon por la que el dominio no se traduce.
 *
 * <p><strong>Solo codigos, sin etiquetas.</strong> El texto que lee una persona es del cliente: una
 * etiqueta aqui congelaria la redaccion en el contrato y obligaria a desplegar el backend para
 * cambiar una palabra de una pantalla.
 *
 * @param enumeraciones de nombre a valores, ordenadas por nombre y cada lista en el orden en que se
 *     declaro la enumeracion. El orden importa: una respuesta que cambia entre dos peticiones
 *     mueve las opciones de sitio en un desplegable
 */
public record Catalogs(Map<String, List<String>> enumerations) {}
