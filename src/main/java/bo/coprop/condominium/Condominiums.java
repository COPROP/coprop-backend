package bo.coprop.condominium;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Lo unico que otros modulos ven de este.
 *
 * <p>Es una interfaz y la implementacion vive en {@code internal} para que las entidades JPA
 * puedan quedarse de paquete: asi el resto de la aplicacion no puede tocarlas ni por descuido, sin
 * depender de que {@code ModularityTests} lo pille despues.
 */
public interface Condominiums {

    /**
     * Da de alta un condominio con su configuracion minima.
     *
     * <p>Publica {@link CondominiumRegistered}, que implementa {@code AuditableEvent} y por eso
     * deja el alta en la bitacora. Lo hace desde el 16-oct-2026: el evento existia desde el primer
     * dia pero no implementaba la interfaz, asi que nadie lo escuchaba y el alta no se registraba.
     *
     * @param nuevo los datos del alta, ya validados
     * @param actor quien lo da de alta, para la bitacora
     * @throws bo.coprop.shared.BusinessRuleViolated si ya existe un condominio con ese NIT
     */
    CondominiumView register(NewCondominium nuevo, String actor);

    /**
     * Cambia el dia de emision y el de vencimiento.
     *
     * <p><strong>No actualiza nada en sitio</strong>: cierra la configuracion vigente y abre otra.
     * La anterior sigue en la base con su fecha de cierre, que es lo que garantiza que una
     * obligacion ya emitida no cambie bajo los pies.
     *
     * <p>Publica {@link CondominiumConfigChanged} con los valores de antes y los de despues.
     * Es el cambio que mueve las fechas de vencimiento y la mora, asi que la bitacora del
     * analisis 12.2 tiene que poder decir quien lo hizo.
     *
     * @param actor quien lo cambia, para la bitacora
     * @throws bo.coprop.shared.ResourceNotFound si el condominio no existe
     */
    CondominiumView changeConfig(UUID condominiumId, int issueDay, int dueDay, String actor);

    /**
     * Corrige el nombre, el NIT y la direccion.
     *
     * <p>Los tres datos descriptivos del condominio, y solo esos. El <strong>tipo</strong> queda
     * fuera porque cambiarlo exige validarlo contra la estructura ya registrada --un
     * {@code BUILDING} con bloques y pisos no cabe en {@code HOUSES}-- y el <strong>estado</strong>
     * tambien, porque suspender un condominio no es editar un campo: es una accion de negocio cuyo
     * efecto sobre los QR vivos y sobre la mora en curso esta sin decidir.
     *
     * <p><strong>Actualiza en sitio, al contrario que {@link #changeConfig}</strong>, y no es una
     * incoherencia. La configuracion se versiona porque una obligacion emitida tiene que poder
     * decir con que dia de vencimiento nacio; el nombre y la direccion no entran en ningun calculo,
     * y del NIT lo que hace falta saber es quien lo cambio y desde que valor, que es lo que cuenta
     * {@link CondominiumEdited} en la bitacora.
     *
     * <p>Publica {@link CondominiumEdited} con los tres campos de antes y los tres de despues.
     *
     * @param address nula se admite: el alta no la pide y hay condominios sin ella
     * @param actor quien lo corrige, para la bitacora
     * @throws bo.coprop.shared.ResourceNotFound si el condominio no existe
     * @throws bo.coprop.shared.BusinessRuleViolated si el NIT ya es de otro condominio
     */
    CondominiumView edit(UUID condominiumId, String name, String nit, @Nullable String address, String actor);

    /**
     * Devuelve un condominio con su configuracion vigente.
     *
     * @throws bo.coprop.shared.ResourceNotFound si no existe
     */
    CondominiumView find(UUID condominiumId);
}
