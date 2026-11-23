package com.policalum.erp.application.service.serviceImpl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.policalum.erp.application.dto.request.AlmacenRequestDTO;
import com.policalum.erp.application.dto.response.AlmacenResponseDTO;
import com.policalum.erp.application.mapper.AlmacenMapper;
import com.policalum.erp.application.service.AlmacenService;
import com.policalum.erp.domain.enums.RolNombre;
import com.policalum.erp.domain.model.Almacen;
import com.policalum.erp.domain.model.Sede;
import com.policalum.erp.domain.model.User;
import com.policalum.erp.exception.ResourceNotFoundException;
import com.policalum.erp.infrastructure.repository.AlmacenRepository;
import com.policalum.erp.infrastructure.repository.SedeRepository;
import com.policalum.erp.infrastructure.repository.UserRepository;

import jakarta.validation.ValidationException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class AlmacenServiceImpl implements AlmacenService {

    private static final String ENTITY_NAME = "Almacén";

    private final AlmacenRepository almacenRepository;
    private final AlmacenMapper almacenMapper;
    private final SedeRepository sedeRepository;
    private final UserRepository userRepository;

    @Override
    public AlmacenResponseDTO create(AlmacenRequestDTO requestDTO) {
        Sede sede = sedeConAcceso(requestDTO.sede().getId());
        validateNombreAvailability(sede.getId(), requestDTO.nombre(), null);
        Almacen almacen = almacenMapper.toEntity(requestDTO);
        almacen.setSede(sede);
        almacen.setNombre(requestDTO.nombre().trim());
        return almacenMapper.toResponseDto(almacenRepository.save(almacen));
    }

    @Override
    public AlmacenResponseDTO update(UUID id, AlmacenRequestDTO requestDTO) {
        Almacen almacen = findEntityById(id);
        sedeConAcceso(almacen.getSede().getId());
        Sede sede = sedeConAcceso(requestDTO.sede().getId());
        validateNombreAvailability(sede.getId(), requestDTO.nombre(), id);
        almacenMapper.updateEntityFromDto(requestDTO, almacen);
        almacen.setSede(sede);
        almacen.setNombre(requestDTO.nombre().trim());
        return almacenMapper.toResponseDto(almacenRepository.save(almacen));
    }

    @Override
    @Transactional(readOnly = true)
    public AlmacenResponseDTO findById(UUID id) {
        Almacen almacen = findEntityById(id);
        sedeConAcceso(almacen.getSede().getId());
        return almacenMapper.toResponseDto(almacen);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AlmacenResponseDTO> search(String searchTerm, UUID sedeId, Boolean activo, Pageable pageable) {
        UUID sedePermitida = sedeSolicitadaConAcceso(sedeId);
        String normalizedSearchTerm = searchTerm == null || searchTerm.isBlank()
                ? null
                : searchTerm.trim();
        return almacenRepository.search(normalizedSearchTerm, sedePermitida, activo, pageable)
                .map(almacenMapper::toResponseDto);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlmacenResponseDTO> findBySede(UUID sedeId) {
        UUID sedePermitida = sedeConAcceso(sedeId).getId();
        return almacenRepository.findBySede_IdOrderByNombreAsc(sedePermitida).stream()
                .map(almacenMapper::toResponseDto)
                .toList();
    }

    @Override
    public AlmacenResponseDTO delete(UUID id) {
        Almacen almacen = findEntityById(id);
        sedeConAcceso(almacen.getSede().getId());
        if (almacenRepository.tieneHistorial(id)) {
            if (Boolean.FALSE.equals(almacen.getActivo())) {
                throw new ValidationException("El almacén tiene historial de inventario y permanece inactivo");
            }
            almacen.setActivo(false);
            return almacenMapper.toResponseDto(almacenRepository.save(almacen));
        }
        almacenRepository.delete(almacen);
        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isNombreDisponible(UUID sedeId, String nombre, UUID excludeId) {
        UUID sedePermitida = sedeConAcceso(sedeId).getId();
        return almacenRepository.findBySede_IdAndNombreIgnoreCase(sedePermitida, nombre.trim())
                .map(almacen -> excludeId != null && almacen.getId().equals(excludeId))
                .orElse(true);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getDashboardStatistics() {
        User actual = usuarioActual();
        UUID sedeId = esAdmin(actual) ? null : sedeActual(actual);
        Map<String, Object> statistics = new HashMap<>();
        if (sedeId == null) {
            statistics.put("totalAlmacenes", almacenRepository.count());
            statistics.put("almacenesActivos", almacenRepository.countByActivo(true));
            statistics.put("almacenesInactivos", almacenRepository.countByActivo(false));
        } else {
            statistics.put("totalAlmacenes", almacenRepository.countBySede_Id(sedeId));
            statistics.put("almacenesActivos", almacenRepository.countBySede_IdAndActivo(sedeId, true));
            statistics.put("almacenesInactivos", almacenRepository.countBySede_IdAndActivo(sedeId, false));
        }
        return statistics;
    }

    private Almacen findEntityById(UUID id) {
        return almacenRepository.findWithSedeById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        String.format("%s no encontrado con ID: %s", ENTITY_NAME, id)));
    }

    private void validateNombreAvailability(UUID sedeId, String nombre, UUID excludeId) {
        if (!isNombreDisponible(sedeId, nombre, excludeId)) {
            throw new ValidationException("Ya existe un almacén con ese nombre en la sede indicada");
        }
    }

    private Sede sedeConAcceso(UUID sedeId) {
        Sede sede = sedeRepository.findById(sedeId)
                .orElseThrow(() -> new ResourceNotFoundException("Sede no encontrada con ID: " + sedeId));
        User actual = usuarioActual();
        if (!esAdmin(actual) && !sedeId.equals(sedeActual(actual))) {
            throw new AccessDeniedException("No tiene acceso a otra sede");
        }
        return sede;
    }

    private UUID sedeSolicitadaConAcceso(UUID sedeId) {
        User actual = usuarioActual();
        if (esAdmin(actual)) return sedeId;
        UUID propia = sedeActual(actual);
        if (sedeId != null && !propia.equals(sedeId)) {
            throw new AccessDeniedException("No tiene acceso a otra sede");
        }
        return propia;
    }

    private User usuarioActual() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmailAndActivoTrue(email)
                .orElseThrow(() -> new AccessDeniedException("Usuario autenticado no válido"));
    }

    private UUID sedeActual(User user) {
        if (user.getSede() == null || user.getSede().getId() == null) {
            throw new AccessDeniedException("Su usuario no tiene una sede asignada");
        }
        return user.getSede().getId();
    }

    private boolean esAdmin(User user) {
        return user.getRol() != null && user.getRol().getNombre() == RolNombre.ROLE_ADMIN;
    }
}
