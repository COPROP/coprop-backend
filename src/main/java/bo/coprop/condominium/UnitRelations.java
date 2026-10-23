package bo.coprop.condominium;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Las tres relaciones de negocio entre una persona y una unidad: quien es propietario, quien
 * ocupa y quien paga.
 *
 * <p><strong>Son tres y no una con columna de tipo.</strong> Una sola no puede decir que el
 * inquilino ocupa y el propietario sigue siendo el responsable de pago, que es el reparto normal
 * de un alquiler. Seguridad 2 construye la autorizacion sobre esa distincion: el rol dice que tipo
 * de cosas puede hacer alguien, y estas relaciones sobre que unidades. Cuando una vence, el acceso
 * a esa unidad se pierde sin tocar la cuenta.
 *
 * <p><strong>Viven en este modulo y no en {@code identity}, aunque el diagrama de Seguridad 2 las
 * cuelgue de la persona.</strong> El motivo es la regla 2 de ARCHITECTURE.md: {@code identity} no
 * depende de {@code condominium}, asi que desde alli no hay forma de comprobar que la unidad sea
 * de este condominio, y sin esa comprobacion no se puede cumplir el 404-no-403 que arrastra el
 * issue #17 --la unidad de otro condominio se serviria igual. Aqui la comprobacion es directa.
 *
 * <p>La persona se referencia por identificador, que es el mismo patron con el que
 * {@code identity} referencia al condominio. Si mas adelante conviene moverlas a
 * {@code identity}, hace falta un puerto en {@code shared} que responda si una unidad es de un
 * condominio; no se hizo ahora porque seria introducir una indireccion para un solo uso.
 */
public interface UnitRelations {

    /**
     * Registra que una persona es propietaria de una unidad.
     *
     * <p>Admite varios copropietarios vigentes sobre la misma unidad, cada uno con su porcentaje;
     * lo que no admite es la misma persona dos veces. <strong>No se valida que los porcentajes
     * sumen 100</strong>, por la misma razon que no se validan las alicuotas: no se puede
     * comprobar fila a fila, y es una validacion de la emision de expensas que le toca a M2.
     *
     * <p>Publica {@link OwnershipGranted}.
     *
     * @param share porcentaje de copropiedad, mayor que 0 y hasta 100
     * @param actor quien lo registra, para la bitacora
     * @throws bo.coprop.shared.ResourceNotFound si la unidad no es de ese condominio
     * @throws bo.coprop.shared.BusinessRuleViolated si esa persona ya es propietaria vigente de
     *     esa unidad
     */
    OwnershipView grantOwnership(UUID condominiumId, UUID unitId, UUID personId, BigDecimal share, String actor);

    /**
     * Registra que una persona ocupa una unidad, como residente o como inquilina.
     *
     * <p>Publica {@link OccupancyGranted}.
     *
     * @throws bo.coprop.shared.ResourceNotFound si la unidad no es de ese condominio
     * @throws bo.coprop.shared.BusinessRuleViolated si esa persona ya ocupa esa unidad
     */
    OccupancyView grantOccupancy(UUID condominiumId, UUID unitId, UUID personId, OccupancyRole role, String actor);

    /**
     * Asigna quien responde por los cobros de una unidad.
     *
     * <p>Publica {@link BillingResponsibilityAssigned}.
     *
     * @param conceptId nulo = responde por todo lo que se cobre a la unidad; con valor, solo por
     *     ese concepto. El concepto como entidad nace en M2, asi que hoy no se comprueba que
     *     exista.
     * @throws bo.coprop.shared.ResourceNotFound si la unidad no es de ese condominio
     * @throws bo.coprop.shared.BusinessRuleViolated si esa persona ya responde por ese concepto
     *     de esa unidad
     */
    BillingResponsibilityView assignBilling(
            UUID condominiumId, UUID unitId, UUID personId, @Nullable UUID conceptId, String actor);

    /**
     * La venta de una unidad: la titularidad pasa de una persona a otra. Issue <strong>#19</strong>.
     *
     * <p><strong>Es una sola operacion y no un cierre seguido de un alta</strong>, por dos motivos.
     * El primero es que entre los dos pasos la unidad se quedaria sin dueño, y si el segundo falla
     * se queda asi; aqui las dos cosas pasan en la misma transaccion o no pasa ninguna. El segundo
     * es la bitacora: dos filas sueltas obligarian a quien la lee a emparejarlas por la fecha y a
     * adivinar si fueron la misma venta.
     *
     * <p><strong>Cierra las TRES relaciones del vendedor con esa unidad</strong>, no solo su
     * titularidad: si vendio y seguia ocupandola o respondiendo por sus cobros, esas filas le
     * seguirian dando acceso --las tres cuentan por igual en {@link #isRelatedTo}-- y el criterio
     * del issue, que el propietario anterior pierda el acceso, no se cumpliria. Si el vendedor se
     * queda como inquilino, el administrador vuelve a registrar su ocupacion, que es una decision
     * explicita y no un resto de la venta.
     *
     * <p>El comprador recibe <strong>el mismo porcentaje</strong> que tenia el vendedor. No se pasa
     * como argumento a proposito: una venta parcial --vender la mitad de lo que uno tiene-- no es
     * esto, se registra cerrando y abriendo las titularidades a mano, y aceptar aqui un porcentaje
     * distinto dejaria que una venta cambiara en silencio el reparto de la unidad.
     *
     * <p>Lo historico no se toca, que es el segundo criterio del issue: las filas cerradas se
     * quedan, y las obligaciones ya emitidas siguen apuntando a quien respondia cuando se
     * emitieron.
     *
     * <p>Publica {@link OwnershipTransferred}.
     *
     * @return la titularidad nueva, la del comprador
     * @throws bo.coprop.shared.ResourceNotFound si la unidad no es de ese condominio, o si quien
     *     vende no es propietario vigente de ella
     * @throws bo.coprop.shared.BusinessRuleViolated si comprador y vendedor son la misma persona,
     *     o si el comprador ya es propietario vigente de esa unidad --ahi lo que hay es una venta
     *     entre copropietarios, que cambia el reparto y se registra a mano--
     */
    OwnershipView transferOwnership(UUID condominiumId, UUID unitId, UUID fromPersonId, UUID toPersonId, String actor);

    /**
     * Alguien deja de ocupar una unidad: se mudo, o se le acabo el contrato. Issue
     * <strong>#19</strong>.
     *
     * <p>Es la mitad manual de lo que Seguridad 6.2 describe como automatico --"el inquilino deja de
     * ver esa unidad al dia siguiente"--. La fecha de fin del contrato como dato de la ocupacion no
     * existe todavia, asi que hoy el cierre lo registra el administrador; el dia que exista, esta
     * misma operacion es la que ejecutara la tarea.
     *
     * <p>Publica {@link OccupancyEnded}.
     *
     * @throws bo.coprop.shared.ResourceNotFound si la unidad no es de ese condominio, o si esa
     *     persona no la ocupa vigentemente
     */
    void endOccupancy(UUID condominiumId, UUID unitId, UUID personId, String actor);

    /**
     * Alguien deja de responder por los cobros de una unidad. Issue <strong>#19</strong>.
     *
     * <p>Publica {@link BillingResponsibilityEnded}.
     *
     * @param conceptId nulo = la responsabilidad general, la que cubre todo lo que se cobre a la
     *     unidad. Hay que pasar el mismo con el que se asigno: cerrar la general no cierra las de
     *     un concepto concreto, porque son filas distintas y responden por cosas distintas
     * @throws bo.coprop.shared.ResourceNotFound si la unidad no es de ese condominio, o si esa
     *     persona no responde vigentemente por ese concepto de esa unidad
     */
    void endBilling(UUID condominiumId, UUID unitId, UUID personId, @Nullable UUID conceptId, String actor);

    /**
     * Si una persona tiene <strong>alguna</strong> relacion vigente con esa unidad de ese
     * condominio: propietaria, ocupante o responsable de sus cobros.
     *
     * <p>Es el tercer eslabon de la cadena de autorizacion (Seguridad 7.2, issue
     * <strong>#15</strong>): el que convierte el "Propias" de la matriz en una respuesta sobre
     * <em>este</em> recurso. Lo pregunta el control de acceso en cada peticion, asi que contesta
     * con tres {@code EXISTS} y sin traer una sola fila; si no fuera asi, una pantalla con veinte
     * unidades costaria veinte listados.
     *
     * <p>Las tres relaciones cuentan por igual, y es deliberado: el propietario que alquilo sigue
     * respondiendo por los cobros y el inquilino residente tiene que poder subir su comprobante.
     * Reducirlo a la titularidad --que es lo que la palabra "propias" sugiere-- dejaria al
     * inquilino fuera de lo suyo. Que ademas la <em>operacion</em> le corresponda a su rol no lo
     * decide este modulo, sino la matriz.
     *
     * <p>Una unidad de otro condominio no empareja nunca, porque el condominio entra en la
     * consulta: asi quien llama no tiene que comprobar aparte de quien es la unidad.
     */
    boolean isRelatedTo(UUID condominiumId, UUID unitId, UUID personId);

    /**
     * Las unidades de las que una persona es propietaria vigente en ese condominio.
     *
     * <p>Es media cadena de autorizacion del issue #15: "un propietario ve solo las unidades que
     * figuran en UnitOwnership vigente" (Seguridad 2). La otra media --si <em>esta</em> unidad es
     * suya-- la contesta {@link #isRelatedTo}, que es lo que se pregunta por peticion; esta sirve
     * para acotar un listado, que es el punto 4 de Seguridad 7.2.
     */
    List<UUID> unitsOwnedBy(UUID condominiumId, UUID personId);

    /** Las unidades que una persona ocupa vigentemente en ese condominio. */
    List<UUID> unitsOccupiedBy(UUID condominiumId, UUID personId);

    /** Las unidades por cuyos cobros responde vigentemente una persona en ese condominio. */
    List<UUID> unitsBilledTo(UUID condominiumId, UUID personId);

    /**
     * Las responsabilidades de pago vigentes del condominio que afectan a un concepto: las de ese
     * concepto y las generales.
     *
     * <p>Es lo que necesita la emision de expensas del issue <strong>#24</strong> para decidir a
     * quien se le cobra cada unidad, y es <strong>una sola consulta para todo el edificio</strong>:
     * preguntarlo unidad por unidad serian tantas como unidades, que es justo lo que no puede hacer
     * una emision mensual de doscientas.
     *
     * <p><strong>Devuelve las dos clases juntas y sin resolver la precedencia</strong>, y es
     * deliberado. Que la del concepto manda sobre la general es semantica de
     * {@link #assignBilling}, pero quien tiene que ver la diferencia entre "esta unidad no tiene
     * responsable" y "tiene dos y no se sabe cual" es quien emite: las dos cosas se arreglan de
     * forma distinta y un metodo que devolviera ya resuelto el responsable las confundiria en un
     * hueco. Resolverlo son cuatro lineas en {@code Expenses}; distinguirlo despues de haberlo
     * resuelto, imposible.
     *
     * <p>Ordenadas por unidad, para que el reparto no cambie de orden entre dos
     * previsualizaciones.
     *
     * <p><strong>No comprueba que el condominio exista</strong>, al contrario que el resto de este
     * puerto: no hay unidad que comprobar, quien emite ya resolvio el condominio antes de llegar
     * aqui, y un condominio que no existe no tiene filas. Añadir la comprobacion obligaria a este
     * servicio a depender de {@code Condominiums} para nada.
     */
    List<BillingResponsibilityView> billingResponsibilitiesFor(UUID condominiumId, UUID conceptId);

    /** Las titularidades vigentes de una unidad, para saber a quien se le cobra. */
    List<OwnershipView> ownersOf(UUID condominiumId, UUID unitId);

    /**
     * Todas las titularidades que ha tenido una unidad, vigentes y cerradas, de la mas reciente a
     * la mas antigua. Issue <strong>#19</strong>.
     *
     * <p>Es la mitad consultable del segundo criterio del issue: los datos de quien vendio se
     * conservan y el administrador los puede ver. Seguridad 6.2 lo dice del lado del vendedor --"si
     * necesita un comprobante de su epoca, lo solicita al administrador"--, y para dar ese
     * comprobante hay que poder decir quien era el dueño en un mes dado, que es lo que esta lista
     * contesta y {@link #ownersOf} no.
     *
     * <p>Separada y no un parametro de {@link #ownersOf} porque las dos preguntas no se responden a
     * la misma gente: la de hoy es la cabecera de un estado de cuenta y la ve tambien el
     * propietario de la unidad; el historico completo es del administrador.
     */
    List<OwnershipView> ownershipHistoryOf(UUID condominiumId, UUID unitId);

    /**
     * Las unidades de ese condominio con las que una persona tiene alguna relacion vigente, y cual.
     *
     * <p>Es lo que necesita {@code GET /api/v1/me} (issue <strong>#14</strong>) para decirle a cada
     * cliente que unidades pintar. Resuelve las tres relaciones de una vez en vez de dejar que
     * quien llame pida {@link #unitsOwnedBy}, {@link #unitsOccupiedBy} y {@link #unitsBilledTo} y
     * despues busque el codigo de cada unidad: eso serian tres consultas mas una por unidad, y la
     * union de las tres relaciones --que una unidad aparezca una sola vez con sus dos papeles-- es
     * trabajo de este modulo, que es el que sabe que son tres tablas.
     *
     * <p>Ordenadas por codigo, para que la pantalla no cambie de orden entre dos peticiones.
     */
    List<RelatedUnitView> relatedTo(UUID condominiumId, UUID personId);
}
