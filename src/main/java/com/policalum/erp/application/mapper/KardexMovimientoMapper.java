package com.policalum.erp.application.mapper;

import org.mapstruct.Mapper;

import com.policalum.erp.application.dto.response.KardexMovimientoResponseDTO;
import com.policalum.erp.domain.model.KardexMovimiento;

@Mapper(componentModel = "spring")
public interface KardexMovimientoMapper {
    KardexMovimientoResponseDTO toResponseDto(KardexMovimiento entity);

    KardexMovimientoResponseDTO toResponseDTO(KardexMovimiento guardado);
}
