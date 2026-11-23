package com.policalum.erp.application.service.serviceImpl;

import com.policalum.erp.application.dto.request.AperturaCajaRequestDTO;
import com.policalum.erp.application.dto.request.CierreCajaRequestDTO;
import com.policalum.erp.application.dto.response.ResumenCierreCajaDTO;
import com.policalum.erp.application.dto.response.TurnoAbiertoResponseDTO;
import com.policalum.erp.application.dto.response.TurnoCajaResponseDTO;
import com.policalum.erp.application.mapper.TurnoCajaMapper;
import com.policalum.erp.application.service.AuditService;
import com.policalum.erp.application.service.TurnoCajaService;
import com.policalum.erp.domain.enums.EstadoTurno;
import com.policalum.erp.domain.enums.MetodoPago;
import com.policalum.erp.domain.enums.RolNombre;
import com.policalum.erp.domain.enums.TipoCajaChica;
import com.policalum.erp.domain.model.Sede;
import com.policalum.erp.domain.model.TurnoCaja;
import com.policalum.erp.domain.model.User;
import com.policalum.erp.exception.ResourceNotFoundException;
import com.policalum.erp.infrastructure.repository.MovimientoCajaChicaRepository;
import com.policalum.erp.infrastructure.repository.PagoVentaRepository;
import com.policalum.erp.infrastructure.repository.SedeRepository;
import com.policalum.erp.infrastructure.repository.TurnoCajaRepository;
import com.policalum.erp.infrastructure.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Map;
import com.policalum.erp.domain.enums.AccionAudit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TurnoCajaServiceImpl implements TurnoCajaService {
    private final TurnoCajaRepository turnoRepository;
    private final SedeRepository sedeRepository;
    private final UserRepository userRepository;
    private final PagoVentaRepository pagoRepository;
    private final MovimientoCajaChicaRepository cajaChicaRepository;
    private final TurnoCajaMapper mapper;
    private final AuditService auditService;

    @Override @Transactional(rollbackFor = Exception.class)
    public TurnoCajaResponseDTO abrir(AperturaCajaRequestDTO request) {
        User user = usuarioActual();
        Sede sede = sedeRepository.findById(request.sedeId()).orElseThrow(() -> new ResourceNotFoundException("Sede no encontrada"));
        exigirAccesoSede(user, sede);
        if (turnoRepository.existsByUser_IdAndEstado(user.getId(), EstadoTurno.ABIERTO)) {
            throw new IllegalStateException("El usuario ya tiene un turno abierto");
        }
        TurnoCaja turno = TurnoCaja.builder().sede(sede).user(user).montoInicial(request.montoInicial())
                .estado(EstadoTurno.ABIERTO).fechaApertura(LocalDateTime.now()).observaciones(request.observaciones()).build();
        TurnoCaja guardado = turnoRepository.save(turno);
        auditService.registrar("turnos_caja", guardado.getId(), AccionAudit.INSERT,
                Map.of("sedeId", sede.getId().toString(), "montoInicial", guardado.getMontoInicial()));
        return mapper.toResponseDto(guardado);
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public ResumenCierreCajaDTO cerrar(UUID turnoId, CierreCajaRequestDTO request) {
        TurnoCaja turno = obtenerPropioAbierto(turnoId);
        ResumenCierreCajaDTO resumen = construirResumen(turno, request.montoFinalDeclarado());
        turno.setMontoFinalDeclarado(request.montoFinalDeclarado());
        turno.setFechaCierre(LocalDateTime.now());
        turno.setEstado(EstadoTurno.CERRADO);
        // Se agrega (no se reemplaza) para conservar el registro de retiros de socios del turno
        if (request.observaciones() != null && !request.observaciones().isBlank()) {
            String actual = turno.getObservaciones();
            turno.setObservaciones(actual == null || actual.isBlank()
                    ? request.observaciones() : actual + "\n[CIERRE] " + request.observaciones());
        }
        turnoRepository.save(turno);
        auditService.registrar("turnos_caja", turno.getId(), AccionAudit.UPDATE,
                Map.of("operacion", "CIERRE", "montoFinalDeclarado", request.montoFinalDeclarado(),
                        "efectivoEsperado", resumen.esperado(), "diferencia", resumen.diferencia()));
        return resumen;
    }

    @Override @Transactional(readOnly = true)
    public ResumenCierreCajaDTO resumen(UUID turnoId) {
        TurnoCaja turno = turnoRepository.findById(turnoId).orElseThrow(() -> new ResourceNotFoundException("Turno no encontrado"));
        exigirAccesoTurno(turno, usuarioActual());
        return construirResumen(turno, turno.getMontoFinalDeclarado());
    }

    @Override
    @Transactional(readOnly = true)
    public TurnoAbiertoResponseDTO turnoAbierto(UUID sedeId) {
        return turnoRepository.abiertosDe(usuarioActual().getId()).stream()
                .filter(turno -> sedeId == null || (turno.getSede() != null && sedeId.equals(turno.getSede().getId())))
                .findFirst()
                .map(turno -> new TurnoAbiertoResponseDTO(true, mapper.toResponseDto(turno)))
                .orElseGet(() -> new TurnoAbiertoResponseDTO(false, null));
    }

    private ResumenCierreCajaDTO construirResumen(TurnoCaja turno, BigDecimal declarado) {
        BigDecimal efectivo = pagoRepository.sumMontoByTurnoAndMetodo(turno.getId(), MetodoPago.EFECTIVO);
        if (efectivo == null) efectivo = BigDecimal.ZERO;
        BigDecimal retiros = turno.getTotalRetirosSociosSeguro();
        BigDecimal reembolsos = turno.getTotalReembolsosSeguro();
        BigDecimal ingresosChica = cero(cajaChicaRepository.sumar(turno.getId(), TipoCajaChica.INGRESO));
        BigDecimal egresosChica = cero(cajaChicaRepository.sumar(turno.getId(), TipoCajaChica.EGRESO));
        BigDecimal esperado = turno.getMontoInicial().add(efectivo).add(ingresosChica).subtract(retiros).subtract(reembolsos).subtract(egresosChica);

        if (esperado.signum() < 0) {
            log.warn("Turno {} cerrado con efectivo esperado NEGATIVO: S/ {}. " +
                     "Inicial: {}, Ventas efectivo: {}, Retiros socios: {}, Reembolsos: {}",
                     turno.getId(), esperado, turno.getMontoInicial(), efectivo, retiros, reembolsos);
        }

        return new ResumenCierreCajaDTO(turno.getId(), turno.getMontoInicial(), efectivo, retiros, reembolsos, esperado, declarado,
                declarado == null ? null : declarado.subtract(esperado));
    }

    private TurnoCaja obtenerPropioAbierto(UUID id) {
        // FOR UPDATE: un retiro de socio concurrente no puede colarse durante el cierre
        TurnoCaja turno = turnoRepository.findByIdParaActualizar(id).orElseThrow(() -> new ResourceNotFoundException("Turno no encontrado"));
        if (turno.getEstado() != EstadoTurno.ABIERTO) throw new IllegalStateException("El turno ya está cerrado");
        if (!turno.getUser().getId().equals(usuarioActual().getId())) {
            throw new AccessDeniedException("Solo el responsable puede cerrar el turno");
        }
        return turno;
    }
    private static BigDecimal cero(BigDecimal valor) {
        return valor == null ? BigDecimal.ZERO : valor;
    }

    private void exigirAccesoSede(User user, Sede sede) {
        if (user.getRol() != null && user.getRol().getNombre() == RolNombre.ROLE_ADMIN) {
            return;
        }
        if (user.getSede() == null || user.getSede().getId() == null
                || !user.getSede().getId().equals(sede.getId())) {
            throw new AccessDeniedException("No puede abrir caja en otra sede");
        }
    }

    private void exigirAccesoTurno(TurnoCaja turno, User user) {
        if (user.getRol() != null && user.getRol().getNombre() == RolNombre.ROLE_ADMIN) return;
        if (turno.getUser() == null || !user.getId().equals(turno.getUser().getId())) {
            throw new AccessDeniedException("No puede consultar el turno de otro usuario");
        }
    }

    private User usuarioActual() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmailAndActivoTrue(email).orElseThrow(() -> new IllegalStateException("Usuario autenticado no válido"));
    }
}
