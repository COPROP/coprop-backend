package bo.coprop.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * COPROP, la que sostiene la cookie del navegador; {@link IdentityProvider#cerrarSesiones}
 * cierra la de Keycloak y revoca los refresh tokens de la app movil. Cerrar solo la de Keycloak
 * dejaria entrando a quien ya tiene cookie --su sesion no vuelve a preguntarle a Keycloak-- y
 * cerrar solo esta dejaria a la app movil renovando su token.
 *
 * <p>Desde el issue <strong>#22</strong> tambien se pueden <em>listar</em>, que es la otra mitad de
 * lo que pide Seguridad 8 --"lista de sesiones activas por usuario; cierre remoto"--.
 */
public interface Sessions {

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
     * @param names los nombres con los que esa cuenta puede estar identificada en una sesion
     * @return cuantas cerro
     */
    int closeThoseOf(Set<String> names);

    /**
     * Las sesiones del BFF abiertas a nombre de alguno de esos nombres. Issue <strong>#22</strong>.
     *
     * <p>Recibe un conjunto por lo mismo que {@link #closeThoseOf}, y con la misma consecuencia: una
     * sesion que apareciera por dos nombres se contaria dos veces, asi que se devuelven sin
     * repetir.
     *
     * <p>De la mas usada a la menos: lo que se busca en esta lista es una sesion que no se
     * reconoce, y la que acaba de usarse es la que mas se parece a un intruso activo.
     *
     * @return las que hay, de cero a varias; nunca {@code null}
     */
    List<BffSession> listThoseOf(Set<String> names);

    /**
     * Una sesion de servidor del BFF, con lo poco que Spring Session guarda de ella.
     *
     * <p><strong>No hay IP ni dispositivo</strong>, que es lo que de verdad ayudaria a reconocer
     * una sesion ajena: la tabla de la V6 guarda la sesion, no de donde vino. Lo que si se sabe lo
     * sabe el proveedor de identidad de sus propias sesiones, y por eso la lista que ve el usuario
     * junta las dos mitades.
     *
     * @param id el identificador de la sesion, que es lo que hay que mandar para cerrarla
     * @param created cuando se abrio, que es lo que mide el tope de 24 horas
     * @param lastAccess la ultima peticion que la uso
     * @param inactivity cuanto aguanta sin usarse, ya resuelta por rol (Seguridad 5.3)
     */
    record BffSession(String id, Instant created, Instant lastAccess, Duration inactivity) {}
}
