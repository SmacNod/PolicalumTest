package com.policalum.erp.application.service.serviceImpl;

import com.policalum.erp.application.dto.request.SedeRequestDTO;
import com.policalum.erp.application.dto.response.SedeMapaDTO;
import com.policalum.erp.application.dto.response.SedeResponseDTO;
import com.policalum.erp.application.mapper.SedeMapper;
import com.policalum.erp.application.service.FileStorageService;
import com.policalum.erp.application.service.SedeService;
import com.policalum.erp.domain.model.Sede;
import com.policalum.erp.domain.model.User;
import com.policalum.erp.domain.enums.RolNombre;
import com.policalum.erp.exception.ResourceNotFoundException;
import com.policalum.erp.infrastructure.geo.PeruGeocoder;
import com.policalum.erp.infrastructure.repository.SedeRepository;
import com.policalum.erp.infrastructure.repository.UserRepository;
import jakarta.transaction.Transactional;
import jakarta.validation.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.policalum.erp.util.HorarioUtil;

@Service
@RequiredArgsConstructor
public class SedeServiceImpl implements SedeService {

    private final SedeRepository sedeRepository;
    private final UserRepository userRepository;
    private final SedeMapper sedeMapper;
    private final FileStorageService fileStorageService;

    @Override
    @Transactional
    @CacheEvict(value = "sedes", allEntries = true)
    public SedeResponseDTO crearSede(SedeRequestDTO request) {
        exigirAdmin(usuarioActual());
        List<String> diasLaborables = validarPoliticaDeSede(request);
        Sede sede = sedeMapper.toEntity(request);
        sede.setDiasLaborables(diasLaborables);

        if (sede.getPais() == null) sede.setPais("Perú");
        if (sede.getMoneda() == null) sede.setMoneda("PEN");
        if (sede.getActiva() == null) sede.setActiva(true);
        if (request.imagen() != null && !request.imagen().isBlank()) sede.setImagen(request.imagen().trim());
        completarUbicacion(sede);

        Sede guardada = sedeRepository.save(sede);
        return sedeMapper.toResponseDto(guardada);
    }

    @Override
    public Page<SedeResponseDTO> listarTodas(Pageable pageable) {
        User actual = usuarioActual();
        if (!esAdmin(actual)) return paginaPropia(actual, pageable);
        return sedeRepository.findAll(pageable)
                .map(sedeMapper::toResponseDto);
    }

    @Override
    public Page<SedeResponseDTO> listarPorEstado(Boolean activa, Pageable pageable) {
        User actual = usuarioActual();
        if (!esAdmin(actual)) {
            if (actual.getSede() == null || actual.getSede().getId() == null
                    || !Boolean.valueOf(activa).equals(actual.getSede().getActiva())) {
                return new PageImpl<>(List.of(), pageable, 0);
            }
            return paginaPropia(actual, pageable);
        }
        return sedeRepository.findByActiva(activa, pageable)
                .map(sedeMapper::toResponseDto);
    }

    @Override
    public SedeResponseDTO buscarPorId(UUID id) {
        Sede sede = sedeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sede no encontrada con ID: " + id));
        exigirAccesoSede(usuarioActual(), sede);
        return sedeMapper.toResponseDto(sede);
    }

    @Override
    @Transactional
    @CacheEvict(value = "sedes", allEntries = true)
    public SedeResponseDTO actualizarSede(UUID id, SedeRequestDTO request) {
        Sede sedeExistente = sedeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No se puede actualizar. Sede no encontrada con ID: " + id));
        exigirAdmin(usuarioActual());

        List<String> diasLaborables = validarPoliticaDeSede(request);
        sedeMapper.updateEntityFromDto(request, sedeExistente);
        sedeExistente.setDiasLaborables(diasLaborables);
        if (request.imagen() != null && !request.imagen().isBlank()) sedeExistente.setImagen(request.imagen().trim());
        completarUbicacion(sedeExistente);

        Sede actualizada = sedeRepository.save(sedeExistente);
        return sedeMapper.toResponseDto(actualizada);
    }

    /**
     * La política de la sede se valida con la misma regla que la de empresa y la del legajo, y se
     * normaliza (orden LUNES→DOMINGO, sin duplicados ni tildes) para que comparar herencias no
     * dependa de cómo escribió el usuario.
     *
     * @return la máscara lista para guardar, o null = "la sede no define días, hereda"
     */
    private static List<String> validarPoliticaDeSede(SedeRequestDTO request) {
        PoliticaRemuneracionServiceImpl.validarPolitica(request.politicaDivision(),
                request.diasLaborables(), request.tratamientoAusencias());
        return request.diasLaborables() == null || request.diasLaborables().isEmpty()
                ? null
                : HorarioUtil.normalizarDias(request.diasLaborables());
    }

    @Override
    @Transactional
    public List<SedeMapaDTO> mapa() {
        User actual = usuarioActual();
        if (!esAdmin(actual)) {
            if (actual.getSede() == null || !Boolean.TRUE.equals(actual.getSede().getActiva())) return List.of();
            return List.of(aMapa(actual.getSede()));
        }
        return sedeRepository.findByActiva(true, PageRequest.of(0, 500, Sort.by("nombre"))).getContent().stream()
                .map(this::aMapa)
                .toList();
    }

    @Override
    @Transactional
    @CacheEvict(value = "sedes", allEntries = true)
    public SedeResponseDTO guardarImagen(UUID id, MultipartFile archivo) {
        Sede sede = sedeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sede no encontrada con ID: " + id));
        exigirAdmin(usuarioActual());
        String nombre = archivo == null || archivo.getOriginalFilename() == null ? "" : archivo.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!(nombre.endsWith(".jpg") || nombre.endsWith(".jpeg") || nombre.endsWith(".png") || nombre.endsWith(".webp"))) {
            throw new ValidationException("La imagen de la sede debe ser JPG, PNG o WEBP");
        }
        String anterior = sede.getImagen();
        String ruta = fileStorageService.guardar(archivo, "sedes");
        sede.setImagen(ruta);
        if (anterior != null && anterior.startsWith("sedes/") && !anterior.contains("..")) {
            fileStorageService.eliminar(anterior);
        }
        return sedeMapper.toResponseDto(sedeRepository.save(sede));
    }

    @Override
    @Transactional
    @CacheEvict(value = "sedes", allEntries = true)
    public SedeResponseDTO eliminarSede(UUID id) {
        Sede sede = sedeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No se puede eliminar. Sede no encontrada con ID: " + id));
        exigirAdmin(usuarioActual());
        if (sedeRepository.tieneHistorial(id)) {
            if (Boolean.FALSE.equals(sede.getActiva())) {
                throw new ValidationException("La sede tiene historial y permanece inactiva");
            }
            sede.setActiva(false);
            return sedeMapper.toResponseDto(sedeRepository.save(sede));
        }
        sedeRepository.delete(sede);
        return null;
    }

    private void completarUbicacion(Sede sede) {
        if (sede.getLatitud() != null && sede.getLongitud() != null) return;
        double[] punto = PeruGeocoder.buscar(sede.getCiudad(), sede.getDireccion(), sede.getNombre());
        if (punto == null) return;
        if (sede.getLatitud() == null) sede.setLatitud(punto[0]);
        if (sede.getLongitud() == null) sede.setLongitud(punto[1]);
    }

    private SedeMapaDTO aMapa(Sede sede) {
        Double latitud = sede.getLatitud();
        Double longitud = sede.getLongitud();
        if (latitud == null || longitud == null) {
            double[] punto = PeruGeocoder.buscar(sede.getCiudad(), sede.getDireccion(), sede.getNombre());
            if (punto != null) {
                if (latitud == null) latitud = punto[0];
                if (longitud == null) longitud = punto[1];
            }
        }
        return new SedeMapaDTO(sede.getId(), sede.getNombre(), sede.getCiudad(), sede.getDireccion(),
                latitud, longitud, sede.getImagen(), latitud != null && longitud != null);
    }

    private Page<SedeResponseDTO> paginaPropia(User user, Pageable pageable) {
        if (user.getSede() == null || user.getSede().getId() == null || pageable.getPageNumber() > 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        return new PageImpl<>(List.of(sedeMapper.toResponseDto(user.getSede())), pageable, 1);
    }

    private void exigirAccesoSede(User user, Sede sede) {
        if (!esAdmin(user) && (user.getSede() == null || !user.getSede().getId().equals(sede.getId()))) {
            throw new AccessDeniedException("No tiene acceso a otra sede");
        }
    }

    private void exigirAdmin(User user) {
        if (!esAdmin(user)) throw new AccessDeniedException("Solo un administrador puede gestionar sedes");
    }

    private User usuarioActual() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmailAndActivoTrue(email)
                .orElseThrow(() -> new IllegalStateException("Usuario autenticado no válido"));
    }

    private static boolean esAdmin(User user) {
        return user.getRol() != null && user.getRol().getNombre() == RolNombre.ROLE_ADMIN;
    }
}
