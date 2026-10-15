package bo.coprop.shared;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Lo que un evento tiene que saber decir de si mismo para acabar en la bitacora. Issue #21.
 *
 * <p><strong>Implementar esta interfaz ES la declaracion de que un evento es auditable.</strong> No
 * hay lista en ningun sitio: el modulo {@code audit} escucha esta interfaz y nada mas, asi que
 * sumar un evento a la bitacora es implementarla en el propio evento, y quitarlo es dejar de
 * implementarla. Eso es lo que hace cierto lo que el {@code package-info} de {@code audit} prometia:
 * que añadir un evento auditable no obliga a tocar ese modulo.
 *
 * <p><strong>Vive en {@code shared} por una razon de dependencias, no por comodidad.</strong> Si el
 * contrato estuviera en {@code audit}, cada modulo de dominio tendria que depender de el para
 * declarar sus eventos; y si {@code audit} escuchara los tipos concretos, tendria que depender de
 * todos los dominios y crecer con cada uno. Con la interfaz aqui, las flechas no apuntan a ninguna
 * de las dos direcciones: los dos modulos miran a {@code shared}.
 *
 * <p>Los nombres estan en español porque el analisis no los nombra; lo que si fija, en 5.2, son los
 * datos: actor, accion, entidad, antes, despues, fecha e IP. La fecha es {@link #occurredAt}, y la
 * IP y la traza no las pone el evento --no son hechos del dominio, son de la peticion-- sino el
 * modulo {@code audit}, leyendolas del contexto de log.
 */
public interface EventoAuditable {

    /**
     * El nombre de la accion en la bitacora. En mayusculas y con guion bajo, como
     * {@code MEMBRESIA_CONCEDIDA}.
     *
     * <p><strong>Una vez elegido no se cambia nunca.</strong> La bitacora de ayer lo lleva escrito
     * en sus filas, y renombrarlo partiria en dos la historia de una misma accion sin que nada
     * falle. Por eso es una cadena explicita en cada evento y no se deriva del nombre de la clase:
     * un refactor que renombre el record no puede cambiar en silencio lo que dicen doce meses de
     * auditoria.
     */
    String accion();

    /**
     * En que condominio paso. <strong>Nulo</strong> cuando la accion es de plataforma y no cuelga de
     * ninguno: conceder el rol de superadministrador, o el primer login de una cuenta.
     *
     * <p>Se llama como el componente que ya tienen los eventos de dominio, asi que casi todos la
     * cumplen sin escribir nada.
     */
    @Nullable
    UUID condominiumId();

    /**
     * Quien lo hizo. Es el identificador de la cuenta, en texto, igual que el actor que ya viajaba
     * en los eventos; en las operaciones del arranque de la plataforma es un nombre.
     */
    String actor();

    /** Sobre que tipo de cosa, en singular y en minusculas: {@code membresia}, {@code invitacion}. */
    String entidad();

    /** Cual, para poder reconstruir la historia de un recurso concreto. */
    UUID entidadId();

    /**
     * Los valores de antes, cuando la accion cambia algo que ya existia.
     *
     * <p>Nulo cuando no habia nada antes, que es el caso de todo alta. No es un mapa vacio: vacio
     * diria "existia y no tenia valores", y eso no es lo mismo.
     */
    @Nullable
    default Map<String, String> antes() {
        return null;
    }

    /**
     * Los valores de despues.
     *
     * <p>Nulo cuando la accion no deja valores que mirar --una revocacion, por ejemplo-- porque lo
     * que cuenta ahi es la accion y su fecha.
     *
     * <p>Son cadenas y no objetos a proposito: la bitacora tiene que poder leerse dentro de un año,
     * cuando la forma de la entidad ya no sea la de hoy. Un mapa de texto no se queda invalido
     * porque alguien añada un campo.
     */
    @Nullable
    default Map<String, String> despues() {
        return null;
    }

    /** Cuando. Lo pone el {@code Clock} inyectado, igual que en el resto del sistema. */
    Instant occurredAt();
}
