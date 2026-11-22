
package com.policalum.erp.application.service.serviceImpl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.policalum.erp.application.dto.request.UserRequestDTO;
import com.policalum.erp.application.dto.response.UserResponseDTO;
import com.policalum.erp.application.mapper.UserMapper;
import com.policalum.erp.application.service.UserService;
import com.policalum.erp.domain.model.Rol;
import com.policalum.erp.domain.model.Sede;
import com.policalum.erp.domain.model.User;
import com.policalum.erp.exception.ResourceNotFoundException;
import com.policalum.erp.infrastructure.repository.RolRepository;
import com.policalum.erp.infrastructure.repository.SedeRepository;
import com.policalum.erp.infrastructure.repository.RefreshTokenRepository;
import com.policalum.erp.infrastructure.repository.UserRepository;
import com.policalum.erp.infrastructure.security.SesionUsuarioCache;

import lombok.RequiredArgsConstructor;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserImpl implements UserService {

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SesionUsuarioCache sesionUsuarioCache;
    private final RolRepository rolRepository;
    private final SedeRepository sedeRepository;

    /** Resuelve IDs validados; nunca recibe entidades JPA desde el cliente. */
    private void fijarRelacionesGestionadas(User user, UserRequestDTO dto) {
        Rol rol = rolRepository.findById(dto.rolId())
                .orElseThrow(() -> new ResourceNotFoundException("El rol indicado no existe: " + dto.rolId()));
        Sede sede = sedeRepository.findById(dto.sedeId())
                .orElseThrow(() -> new ResourceNotFoundException("La sede indicada no existe: " + dto.sedeId()));
        user.setRol(rol);
        user.setSede(sede);
    }

    // Crear usuario
    @Transactional
    @Override
    public UserResponseDTO createUser(UserRequestDTO userRequestDTO) {

        if (userRepository.findByEmail(userRequestDTO.email()).isPresent()) {
            throw new IllegalArgumentException("El correo ya está registrado");
        }

        User user = userMapper.toEntity(userRequestDTO);
        fijarRelacionesGestionadas(user, userRequestDTO);

        String securedPassword = passwordEncoder.encode(
                userRequestDTO.passwordHash()
        );

        user.setPasswordHash(securedPassword);
        user.setActivo(true);

        User savedUser = userRepository.save(user);

        User userWithRelations = userRepository
                .findByIdWithRelations(savedUser.getId())
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "No se pudo recuperar el usuario creado"
                        )
                );

        return userMapper.toResponseDto(userWithRelations);
    }

    // Listar usuarios
    @Transactional(readOnly = true)
    @Override
    public List<UserResponseDTO> getUsers() {

        return userRepository.findAllWithRelations()
                .stream()
                .map(userMapper::toResponseDto)
                .toList();
    }

    // Buscar usuario por ID
    @Transactional(readOnly = true)
    @Override
    public Optional<UserResponseDTO> findById(UUID id) {

        return userRepository.findByIdWithRelations(id)
                .map(userMapper::toResponseDto);
    }

    // Actualizar usuario
    @Transactional
    @Override
    public UserResponseDTO updateUser(UUID id, UserRequestDTO dto) {

        User existingUser = userRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Usuario no encontrado"
                        )
                );

        String correoPrevio = existingUser.getEmail();
        String currentPasswordHash = existingUser.getPasswordHash();
        UUID rolPrevio = existingUser.getRol() == null ? null : existingUser.getRol().getId();
        UUID sedePrevia = existingUser.getSede() == null ? null : existingUser.getSede().getId();
        Boolean activoPrevio = existingUser.getActivo();

        userMapper.updateEntityFromDto(dto, existingUser);
        fijarRelacionesGestionadas(existingUser, dto);

        boolean credencialCambiada = dto.passwordHash() != null && !dto.passwordHash().isBlank();
        if (credencialCambiada) {

            existingUser.setPasswordHash(
                    passwordEncoder.encode(dto.passwordHash())
            );

        } else {

            existingUser.setPasswordHash(currentPasswordHash);
        }

        boolean autorizacionCambiada = !Objects.equals(rolPrevio, id(existingUser.getRol()))
                || !Objects.equals(sedePrevia, id(existingUser.getSede()))
                || !Objects.equals(activoPrevio, existingUser.getActivo())
                || !Objects.equals(correoPrevio, existingUser.getEmail());
        if (credencialCambiada || autorizacionCambiada) {
            int version = existingUser.getSesionVersion() == null ? 0 : existingUser.getSesionVersion();
            existingUser.setSesionVersion(version + 1);
            refreshTokenRepository.revocarDe(existingUser.getId());
            sesionUsuarioCache.invalidar(correoPrevio);
            if (existingUser.getEmail() != null && !existingUser.getEmail().equals(correoPrevio)) {
                sesionUsuarioCache.invalidar(existingUser.getEmail());
            }
        }

        userRepository.save(existingUser);

        User updatedUser = userRepository
                .findByIdWithRelations(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "No se pudo recuperar el usuario actualizado"
                        )
                );

        return userMapper.toResponseDto(updatedUser);
    }

    // Eliminar usuario
    @Transactional
    @Override
    public void DeleteUser(UUID id) {

        if (!userRepository.existsById(id)) {

            throw new ResourceNotFoundException(
                    "Usuario no encontrado con el id: " + id
            );
        }

        userRepository.deleteById(id);
    }

    private static UUID id(Rol rol) {
        return rol == null ? null : rol.getId();
    }

    private static UUID id(Sede sede) {
        return sede == null ? null : sede.getId();
    }
}
