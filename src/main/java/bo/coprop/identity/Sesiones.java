package bo.coprop.identity;

import java.util.Set;

/**
 * Las sesiones de servidor del BFF web, vistas desde el dominio. Issue <strong>#19</strong>.
 *
 * <p>Es un puerto y no una llamada directa porque las sesiones viven en {@code api} --las gobierna
 * la cadena de seguridad, y su tabla la crea la migracion V6 para Spring Session-- y este modulo no
 * puede depender de alli: la direccion permitida es {@code api} hacia el dominio (regla 6 de
 * ARCHITECTURE.md). Declarar aqui lo que hace falta y dejar que {@code api} lo implemente es la
 * inversion de siempre.
 *
 * <p><strong>Son dos cierres y no uno</strong>, y hacen falta los dos. Este cierra la sesion de
 * COPROP, la que sostiene la cookie del navegador; {@link ProveedorDeIdentidad#cerrarSesiones}
 * cierra la de Keycloak y revoca los refresh tokens de la app movil. Cerrar solo la de Keycloak
 * dejaria entrando a quien ya tiene cookie --su sesion no vuelve a preguntarle a Keycloak-- y
 * cerrar solo esta dejaria a la app movil renovando su token.
 *
 * <p>No hay una operacion para <em>listar</em> sesiones, que Seguridad 8 tambien nombra. Esa es del
 * issue <strong>#22</strong>, el de politicas de sesion; aqui solo hace falta cerrarlas.
 */
public interface Sesiones {

    /**
     * Cierra todas las sesiones del BFF que esten a nombre de alguno de esos nombres.
     *
     * <p><strong>Recibe un conjunto y no un identificador a proposito.</strong> Una sesion queda
     * indexada por el nombre de la autenticacion que se guardo en ella, y en COPROP hay dos
     * candidatos: el {@code sub} de Keycloak, que es el nombre de la autenticacion que escribe el
     * login, y el identificador de la cuenta, que es el nombre de la identidad con la que el filtro
     * del issue #13 la sustituye en cada peticion. Hoy se guarda la primera --Spring Security 6 no
     * persiste el contexto que cambia a mitad de peticion-- pero eso es un detalle de una libreria,
     * y una baja que dependa de el fallaria en silencio el dia que cambie. Pasar los dos cuesta una
     * consulta por indice.
     *
     * <p><strong>Es idempotente y no falla si no hay ninguna</strong>: a quien nunca entro por la
     * web no hay nada que cerrarle, y eso no es un error.
     *
     * @param nombres los nombres con los que esa cuenta puede estar identificada en una sesion
     * @return cuantas cerro
     */
    int cerrarLasDe(Set<String> nombres);
}
