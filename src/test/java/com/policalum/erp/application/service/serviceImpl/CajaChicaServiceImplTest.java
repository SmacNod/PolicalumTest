package com.policalum.erp.application.service.serviceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.policalum.erp.application.dto.request.CajaChicaRequestDTO;
import com.policalum.erp.application.dto.response.CajaChicaResponseDTO;
import com.policalum.erp.application.service.AuditService;
import com.policalum.erp.domain.enums.ConceptoCajaChica;
import com.policalum.erp.domain.enums.EstadoTurno;
import com.policalum.erp.domain.enums.RolNombre;
import com.policalum.erp.domain.enums.TipoCajaChica;
import com.policalum.erp.domain.model.MovimientoCajaChica;
import com.policalum.erp.domain.model.Rol;
import com.policalum.erp.domain.model.Sede;
import com.policalum.erp.domain.model.TurnoCaja;
import com.policalum.erp.domain.model.User;
import com.policalum.erp.exception.ResourceNotFoundException;
import com.policalum.erp.infrastructure.repository.MovimientoCajaChicaRepository;
import com.policalum.erp.infrastructure.repository.TurnoCajaRepository;
import com.policalum.erp.infrastructure.repository.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class CajaChicaServiceImplTest {

    @Mock MovimientoCajaChicaRepository repository;
    @Mock TurnoCajaRepository turnoRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @InjectMocks CajaChicaServiceImpl service;

    private final String email = "cajero@policalum.com";
    private User cajero;
    private TurnoCaja turno;
    private CajaChicaRequestDTO request;

    @BeforeEach
    void setUp() {
        cajero = User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .rol(Rol.builder().nombre(RolNombre.ROLE_MULTIFUNCIONAL).build())
                .build();
        turno = TurnoCaja.builder()
                .id(UUID.randomUUID())
                .sede(Sede.builder().id(UUID.randomUUID()).build())
                .user(cajero)
                .estado(EstadoTurno.ABIERTO)
                .montoInicial(new BigDecimal("100.00"))
                .build();
        request = new CajaChicaRequestDTO(
                TipoCajaChica.EGRESO,
                ConceptoCajaChica.SUMINISTRO,
                new BigDecimal("12.50"),
                "Papelería");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, "N/A"));
        lenient().when(userRepository.findByEmailAndActivoTrue(email)).thenReturn(Optional.of(cajero));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void registrar_turnoDeOtroUsuario_lanzaAccessDenied_yNoGuardaNiAudita() {
        turno.setUser(User.builder().id(UUID.randomUUID()).build());
        when(turnoRepository.findById(turno.getId())).thenReturn(Optional.of(turno));

        assertThrows(AccessDeniedException.class, () -> service.registrar(turno.getId(), request));

        verifyNoInteractions(repository, auditService);
    }

    @Test
    void listar_turnoDeOtroUsuario_lanzaAccessDenied_yNoConsultaMovimientos() {
        turno.setUser(User.builder().id(UUID.randomUUID()).build());
        when(turnoRepository.findById(turno.getId())).thenReturn(Optional.of(turno));

        assertThrows(AccessDeniedException.class, () -> service.listar(turno.getId()));

        verifyNoInteractions(repository);
    }

    @Test
    void registrar_turnoCerrado_lanzaConflicto_yNoGuarda() {
        turno.setEstado(EstadoTurno.CERRADO);
        when(turnoRepository.findById(turno.getId())).thenReturn(Optional.of(turno));

        assertThrows(IllegalStateException.class, () -> service.registrar(turno.getId(), request));

        verifyNoInteractions(repository, auditService);
    }

    @Test
    void registrar_turnoPropioAbierto_guardaYAudita() {
        MovimientoCajaChica movimiento = MovimientoCajaChica.builder()
                .id(UUID.randomUUID())
                .turno(turno)
                .user(cajero)
                .tipo(request.tipo())
                .concepto(request.concepto())
                .monto(request.monto())
                .detalle(request.detalle())
                .build();
        when(turnoRepository.findById(turno.getId())).thenReturn(Optional.of(turno));
        when(repository.save(any(MovimientoCajaChica.class))).thenReturn(movimiento);

        CajaChicaResponseDTO response = service.registrar(turno.getId(), request);

        assertEquals(movimiento.getId(), response.id());
        assertEquals(turno.getId(), response.turnoId());
        assertEquals(request.monto(), response.monto());
        verify(repository).save(any(MovimientoCajaChica.class));
        verify(auditService).registrar(any(), any(), any(), any());
    }

    @Test
    void listar_turnoPropio_devuelveMovimientos() {
        MovimientoCajaChica movimiento = MovimientoCajaChica.builder()
                .id(UUID.randomUUID())
                .turno(turno)
                .user(cajero)
                .tipo(request.tipo())
                .concepto(request.concepto())
                .monto(request.monto())
                .detalle(request.detalle())
                .build();
        when(turnoRepository.findById(turno.getId())).thenReturn(Optional.of(turno));
        when(repository.findByTurno_IdOrderByCreatedAtDesc(turno.getId())).thenReturn(List.of(movimiento));

        List<CajaChicaResponseDTO> response = service.listar(turno.getId());

        assertEquals(1, response.size());
        assertEquals(movimiento.getId(), response.get(0).id());
    }

    @Test
    void listar_turnoInexistente_lanzaNotFound() {
        UUID turnoId = UUID.randomUUID();
        when(turnoRepository.findById(turnoId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.listar(turnoId));

        verifyNoInteractions(repository);
    }
}
