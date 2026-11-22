package com.policalum.erp.application.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.policalum.erp.application.dto.request.UserRequestDTO;
import com.policalum.erp.application.dto.response.UserResponseDTO;
import com.policalum.erp.domain.model.User;

@Mapper(componentModel = "spring")
public interface UserMapper {

    UserResponseDTO toResponseDto(User user);

    // Las relaciones y la contraseña se resuelven exclusivamente en UserImpl.
    @Mapping(target = "rol", ignore = true)
    @Mapping(target = "sede", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    User toEntity(UserRequestDTO userRequestDTO);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "rol", ignore = true)
    @Mapping(target = "sede", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    void updateEntityFromDto(UserRequestDTO dto, @MappingTarget User user);
}
