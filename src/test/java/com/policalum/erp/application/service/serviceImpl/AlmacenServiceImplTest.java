package com.policalum.erp.application.service.serviceImpl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.policalum.erp.application.dto.request.AlmacenRequestDTO;
import com.policalum.erp.application.mapper.AlmacenMapper;
import com.policalum.erp.domain.model.Almacen;
import com.policalum.erp.domain.model.Rol;
import com.policalum.erp.domain.model.Sede;
import com.policalum.erp.domain.model.User;
import com.policalum.erp.exception.ResourceNotFoundException;
import com.policalum.erp.infrastructure.repository.AlmacenRepository;
import com.policalum.erp.infrastructure.repository.SedeRepository;
import com.policalum.erp.infrastructure.repository.UserRepository;
import jakarta.validation.ValidationException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/** Almacenes: nombre único dentro de cada sede. */
@ExtendWith(MockitoExtension.class)
class AlmacenServiceImplTest {

    @Mock AlmacenRepository almacenRepository;
    @Mock AlmacenMapper almacenMapper;
    @Mock SedeRepository sedeRepository;
    @Mock UserRepository userRepository;
    @InjectMocks AlmacenServiceImpl service;

    User usuario;
    Sede sede;
    Almacen principal;

    @BeforeEach
    void setUp() {
        sede = Sede.builder().id(UUID.randomUUID()).build();
        principal = Almacen.builder().id(UUID.randomUUID()).nombre("Principal").sede(sede).build();
        usuario = User.builder().id(UUID.randomUUID()).email("operador@policalum.com")
                .sede(sede).rol(Rol.builder().nombre(com.policalum.erp.domain.enums.RolNombre.ROLE_MULTIFUNCIONAL).build()).build();
        SecurityContext context = mock(SecurityContext.class);
        Authentication authentication = mock(Authentication.class);
        lenient().when(authentication.getName()).thenReturn(usuario.getEmail());
        lenient().when(context.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(context);
        lenient().when(userRepository.findByEmailAndActivoTrue(usuario.getEmail())).thenReturn(Optional.of(usuario));
        lenient().when(sedeRepository.findById(sede.getId())).thenReturn(Optional.of(sede));
        lenient().when(almacenRepository.findWithSedeById(principal.getId())).thenReturn(Optional.of(principal));
        lenient().when(almacenRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AlmacenRequestDTO request(String nombre) {
        return new AlmacenRequestDTO(sede, nombre, true, null);
    }

    @Test
    void create_nombreLibre_guardaRecortado() {
        Almacen mapeado = new Almacen();
        when(almacenMapper.toEntity(any())).thenReturn(mapeado);
        when(almacenRepository.findBySede_IdAndNombreIgnoreCase(sede.getId(), "Tienda")).thenReturn(Optional.empty());

        service.create(request("  Tienda "));

        assertEquals("Tienda", mapeado.getNombre());
        verify(almacenRepository).save(mapeado);
    }

    @Test
    void create_nombreRepetidoEnLaSede_lanzaValidation() {
        when(almacenRepository.findBySede_IdAndNombreIgnoreCase(sede.getId(), "principal")).thenReturn(Optional.of(principal));

        assertThrows(ValidationException.class, () -> service.create(request("principal")));
        verify(almacenRepository, never()).save(any());
    }

    @Test
    void create_sedeAjena_lanzaAccessDenied_yNoGuarda() {
        Sede ajena = Sede.builder().id(UUID.randomUUID()).build();
        when(sedeRepository.findById(ajena.getId())).thenReturn(Optional.of(ajena));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.create(new AlmacenRequestDTO(ajena, "Ajeno", true, null)));
        verify(almacenRepository, never()).save(any());
    }

    @Test
    void update_mismoNombre_permitido() {
        when(almacenRepository.findBySede_IdAndNombreIgnoreCase(sede.getId(), "Principal")).thenReturn(Optional.of(principal));

        service.update(principal.getId(), request("Principal "));

        verify(almacenMapper).updateEntityFromDto(any(), eq(principal));
        assertEquals("Principal", principal.getNombre());
    }

    @Test
    void update_nombreDeOtroAlmacenDeLaSede_lanzaValidation() {
        Almacen otro = Almacen.builder().id(UUID.randomUUID()).build();
        when(almacenRepository.findBySede_IdAndNombreIgnoreCase(sede.getId(), "Tienda")).thenReturn(Optional.of(otro));

        assertThrows(ValidationException.class, () -> service.update(principal.getId(), request("Tienda")));
    }

    @Test
    void update_inexistente_lanzaNotFound() {
        UUID x = UUID.randomUUID();
        when(almacenRepository.findWithSedeById(x)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.update(x, request("A")));
    }

    @Test
    void findById_almacenDeOtraSede_lanzaAccessDenied() {
        Sede ajena = Sede.builder().id(UUID.randomUUID()).build();
        Almacen almacenAjeno = Almacen.builder().id(UUID.randomUUID()).sede(ajena).build();
        when(almacenRepository.findWithSedeById(almacenAjeno.getId())).thenReturn(Optional.of(almacenAjeno));
        when(sedeRepository.findById(ajena.getId())).thenReturn(Optional.of(ajena));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.findById(almacenAjeno.getId()));
        verifyNoInteractions(almacenMapper);
    }

    @Test
    void search_terminoEnBlancoSeEnviaNulo() {
        Pageable pg = PageRequest.of(0, 10);
        when(almacenRepository.search(null, sede.getId(), true, pg)).thenReturn(Page.empty());

        service.search("   ", sede.getId(), true, pg);

        verify(almacenRepository).search(null, sede.getId(), true, pg);
    }

    @Test
    void findBySede_delete_yFindById() {
        when(almacenRepository.findBySede_IdOrderByNombreAsc(sede.getId())).thenReturn(List.of(principal));

        assertEquals(1, service.findBySede(sede.getId()).size());
        service.findById(principal.getId());
        service.delete(principal.getId());

        verify(almacenMapper, times(2)).toResponseDto(principal);
        verify(almacenRepository).delete(principal);
    }

    @Test
    void delete_conHistorial_desactivaYNoBorra() {
        principal.setActivo(true);
        when(almacenRepository.tieneHistorial(principal.getId())).thenReturn(true);

        service.delete(principal.getId());

        assertFalse(principal.getActivo());
        verify(almacenRepository).save(principal);
        verify(almacenRepository, never()).delete(any());
    }

    @Test
    void delete_inactivoConHistorial_rechaza() {
        principal.setActivo(false);
        when(almacenRepository.tieneHistorial(principal.getId())).thenReturn(true);

        assertThrows(ValidationException.class, () -> service.delete(principal.getId()));
        verify(almacenRepository, never()).save(any());
        verify(almacenRepository, never()).delete(any());
    }

    @Test
    void getDashboardStatistics_cuentaActivos() {
        when(almacenRepository.countBySede_Id(sede.getId())).thenReturn(3L);
        when(almacenRepository.countBySede_IdAndActivo(sede.getId(), true)).thenReturn(2L);
        when(almacenRepository.countBySede_IdAndActivo(sede.getId(), false)).thenReturn(1L);

        assertEquals(Map.of("totalAlmacenes", 3L, "almacenesActivos", 2L, "almacenesInactivos", 1L), service.getDashboardStatistics());
    }

    // ===================== Completado en auditoría de cobertura =====================
    @Test
    void isNombreDisponible_recortaYDistingueElPropio() {
        when(almacenRepository.findBySede_IdAndNombreIgnoreCase(sede.getId(), "Principal")).thenReturn(Optional.of(principal));
        when(almacenRepository.findBySede_IdAndNombreIgnoreCase(sede.getId(), "Libre")).thenReturn(Optional.empty());

        assertTrue(service.isNombreDisponible(sede.getId(), " Libre ", null));
        assertTrue(service.isNombreDisponible(sede.getId(), "Principal", principal.getId()));
        assertFalse(service.isNombreDisponible(sede.getId(), "Principal", null));
        assertFalse(service.isNombreDisponible(sede.getId(), "Principal", UUID.randomUUID()));
    }
}
