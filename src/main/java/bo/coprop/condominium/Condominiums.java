package bo.coprop.condominium;

import java.util.UUID;

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
     * <p>Publica {@link CondominiumRegistered}, que es lo que hara que el alta quede en la
     * bitacora cuando llegue el issue #21.
     *
     * @param nuevo los datos del alta, ya validados
     * @param actor quien lo da de alta, para la bitacora
     * @throws bo.coprop.shared.ReglaDeNegocioViolada si ya existe un condominio con ese NIT
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
     * @throws bo.coprop.shared.RecursoNoEncontrado si el condominio no existe
     */
    CondominiumView changeConfig(UUID condominiumId, int issueDay, int dueDay, String actor);

    /**
     * Devuelve un condominio con su configuracion vigente.
     *
     * @throws bo.coprop.shared.RecursoNoEncontrado si no existe
     */
    CondominiumView find(UUID condominiumId);
}
