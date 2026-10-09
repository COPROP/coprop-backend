package bo.coprop.condominium;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Lo que se devuelve de una unidad. */
public record UnitView(
        UUID id,
        UUID condominiumId,
        String code,
        UnitType type,
        BigDecimal aliquot,
        @Nullable BigDecimal areaM2,
        @Nullable UUID blockId,
        @Nullable UUID floorId,
        UnitStatus status) {}
