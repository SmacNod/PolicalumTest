package com.policalum.erp.application.dto.request;

import com.policalum.erp.domain.enums.TipoMovimiento;
import com.policalum.erp.domain.enums.TipoReferencia;
import java.math.BigDecimal;
import java.util.UUID;

public record KardexMovimientoRequestDTO(
        UUID productoId,
        UUID almacenId,
        UUID referenciaId,
        TipoReferencia tipoReferencia,
        TipoMovimiento tipoMovimiento,
        BigDecimal cantidadMovida,
        String observacion
) {}
