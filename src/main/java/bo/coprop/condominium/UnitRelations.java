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
     * @throws bo.coprop.shared.RecursoNoEncontrado si la unidad no es de ese condominio
     * @throws bo.coprop.shared.ReglaDeNegocioViolada si esa persona ya es propietaria vigente de
     *     esa unidad
     */
    OwnershipView grantOwnership(UUID condominiumId, UUID unitId, UUID personId, BigDecimal share, String actor);

    /**
     * Registra que una persona ocupa una unidad, como residente o como inquilina.
     *
     * <p>Publica {@link OccupancyGranted}.
     *
     * @throws bo.coprop.shared.RecursoNoEncontrado si la unidad no es de ese condominio
     * @throws bo.coprop.shared.ReglaDeNegocioViolada si esa persona ya ocupa esa unidad
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
     * @throws bo.coprop.shared.RecursoNoEncontrado si la unidad no es de ese condominio
     * @throws bo.coprop.shared.ReglaDeNegocioViolada si esa persona ya responde por ese concepto
     *     de esa unidad
     */
    BillingResponsibilityView assignBilling(
            UUID condominiumId, UUID unitId, UUID personId, @Nullable UUID conceptId, String actor);

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

    /** Las titularidades vigentes de una unidad, para saber a quien se le cobra. */
    List<OwnershipView> ownersOf(UUID condominiumId, UUID unitId);

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
