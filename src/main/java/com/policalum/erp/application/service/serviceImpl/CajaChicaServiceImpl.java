package com.policalum.erp.application.service.serviceImpl;

import com.policalum.erp.application.dto.request.CajaChicaRequestDTO;
import com.policalum.erp.application.dto.response.CajaChicaResponseDTO;
import com.policalum.erp.application.service.AuditService;
import com.policalum.erp.domain.enums.AccionAudit;
import com.policalum.erp.domain.enums.EstadoTurno;
import com.policalum.erp.domain.enums.RolNombre;
import com.policalum.erp.domain.model.MovimientoCajaChica;
import com.policalum.erp.domain.model.TurnoCaja;
import com.policalum.erp.domain.model.User;
import com.policalum.erp.exception.ResourceNotFoundException;
import com.policalum.erp.infrastructure.repository.MovimientoCajaChicaRepository;
import com.policalum.erp.infrastructure.repository.TurnoCajaRepository;
import com.policalum.erp.infrastructure.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CajaChicaServiceImpl {

    private final MovimientoCajaChicaRepository repository;
    private final TurnoCajaRepository turnoRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Transactional(rollbackFor = Exception.class)
    public CajaChicaResponseDTO registrar(UUID turnoId, CajaChicaRequestDTO request) {
        TurnoCaja turno = turnoRepository.findById(turnoId).orElseThrow(() -> new ResourceNotFoundException("Turno no encontrado"));
        User user = usuario();
        if (turno.getEstado() != EstadoTurno.ABIERTO) throw new IllegalStateException("La caja chica solo se mueve con el turno abierto");
        if (!turno.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("Solo el responsable del turno registra caja chica");
        }
        MovimientoCajaChica movimiento = MovimientoCajaChica.builder()
                .turno(turno).user(user).tipo(request.tipo()).concepto(request.concepto())
                .monto(request.monto()).detalle(request.detalle() == null ? null : request.detalle().trim())
                .build();
        MovimientoCajaChica guardado = repository.save(movimiento);
        auditService.registrar("movimientos_caja_chica", guardado.getId(), AccionAudit.INSERT,
                Map.of("turnoId", turno.getId().toString(), "tipo", guardado.getTipo().name(), "monto", guardado.getMonto()));
        return dto(guardado);
    }

    @Transactional(readOnly = true)
    public List<CajaChicaResponseDTO> listar(UUID turnoId) {
        TurnoCaja turno = turnoRepository.findById(turnoId).orElseThrow(() -> new ResourceNotFoundException("Turno no encontrado"));
        exigirAcceso(turno, usuario());
        return repository.findByTurno_IdOrderByCreatedAtDesc(turnoId).stream().map(this::dto).toList();
    }

    private CajaChicaResponseDTO dto(MovimientoCajaChica movimiento) {
        return new CajaChicaResponseDTO(movimiento.getId(), movimiento.getTurno().getId(), movimiento.getTipo(),
                movimiento.getConcepto(), movimiento.getMonto(), movimiento.getDetalle(), movimiento.getCreatedAt());
    }

    private User usuario() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmailAndActivoTrue(email).orElseThrow(() -> new IllegalStateException("Usuario autenticado no válido"));
    }

    private static void exigirAcceso(TurnoCaja turno, User user) {
        if (user.getRol() != null && user.getRol().getNombre() == RolNombre.ROLE_ADMIN) return;
        if (turno.getUser() == null || !user.getId().equals(turno.getUser().getId())) {
            throw new AccessDeniedException("No puede consultar movimientos de caja de otro usuario");
        }
    }
}
