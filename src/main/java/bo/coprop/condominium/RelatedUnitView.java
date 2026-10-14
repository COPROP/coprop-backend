package bo.coprop.condominium;

import java.util.Set;
import java.util.UUID;

/**
 * Una unidad con la que quien pregunta tiene algo que ver, y que es lo que tiene que ver.
 *
 * <p>Lleva el codigo y no solo el identificador porque quien la consume la pinta en una pantalla
 * --el {@code GET /api/v1/me} del issue #14--, y un UUID no le dice a nadie que ese es su
 * departamento.
 *
 * <p>No lleva la alicuota ni el porcentaje de copropiedad: son datos de cobro y esta vista
 * contesta a "que es mio", no a "cuanto me toca". El estado de cuenta es M2.
 *
 * @param relations nunca vacio; si lo estuviera, la unidad no estaria en la lista
 */
public record RelatedUnitView(UUID unitId, String code, UnitType type, Set<UnitRelation> relations) {}
