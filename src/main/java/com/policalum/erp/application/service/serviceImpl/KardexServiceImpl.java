package com.policalum.erp.application.service.serviceImpl;

import java.math.BigDecimal;
import com.policalum.erp.application.dto.response.ArchivoDescargaDTO;
import com.policalum.erp.application.dto.response.HistorialProductoDTO;
import com.policalum.erp.application.dto.response.KardexMovimientoDetalleDTO;
import com.policalum.erp.application.dto.response.MermaKardexDTO;
import com.policalum.erp.application.dto.response.MermaPorProductoDTO;
import com.policalum.erp.application.dto.response.ReporteMermasDTO;
import com.policalum.erp.application.dto.response.SaldoKardexDTO;
import com.policalum.erp.application.dto.response.SaldosProductoDTO;
import com.policalum.erp.application.dto.response.ValorizacionKardexDTO;
import com.policalum.erp.application.dto.response.ValorizacionProductoDTO;
import com.policalum.erp.application.mapper.KardexConsultaMapper;
import com.policalum.erp.application.service.AuditService;
import com.policalum.erp.application.service.ConfiguracionService;
import com.policalum.erp.application.service.support.RangosConsulta;
import com.policalum.erp.domain.enums.AccionAudit;
import com.policalum.erp.domain.enums.RolNombre;
import com.policalum.erp.infrastructure.repository.projection.MermaProjection;
import com.policalum.erp.infrastructure.repository.projection.SaldoProductoAlmacenProjection;
import com.policalum.erp.infrastructure.repository.projection.TotalPorTipoMovimientoProjection;
import com.policalum.erp.infrastructure.repository.projection.ValorizacionProductoProjection;
import com.policalum.erp.util.CsvUtil;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.policalum.erp.application.dto.request.KardexMovimientoRequestDTO;
import com.policalum.erp.application.dto.request.TransferenciaRequestDTO;
import com.policalum.erp.application.dto.response.KardexMovimientoResponseDTO;
import com.policalum.erp.application.mapper.KardexMovimientoMapper;
import com.policalum.erp.application.service.KardexService;
import com.policalum.erp.domain.enums.TipoMovimiento;
import com.policalum.erp.domain.enums.TipoReferencia;
import com.policalum.erp.domain.model.Almacen;
import com.policalum.erp.domain.model.KardexMovimiento;
import com.policalum.erp.domain.model.Producto;
import com.policalum.erp.domain.model.Stock;
import com.policalum.erp.domain.model.User;
import com.policalum.erp.exception.ResourceNotFoundException;
import com.policalum.erp.infrastructure.repository.AlmacenRepository;
import com.policalum.erp.infrastructure.repository.KardexRepository;
import com.policalum.erp.infrastructure.repository.ProductoRepository;
import com.policalum.erp.infrastructure.repository.StockRepository;
import com.policalum.erp.infrastructure.repository.UserRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class KardexServiceImpl implements KardexService {

    static final LocalDate FECHA_MIN = LocalDate.of(2000, 1, 1);
    static final LocalDate FECHA_MAX = LocalDate.of(2100, 1, 1);
    static final int LOTE_EXPORTACION = 1_000;
    static final String[] COLUMNAS_CSV = {"fecha", "tipo_movimiento", "tipo_referencia", "referencia_id", "producto", "sku",
            "unidad", "almacen", "sede", "cantidad", "stock_anterior", "stock_resultante", "usuario", "observacion"};
    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FORMATO_ARCHIVO = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");
    private static final BigDecimal CIEN = new BigDecimal("100");

    private final KardexRepository kardexRepository;
    private final ConfiguracionService configuracionService;
    private final StockRepository stockRepository;
    private final ProductoRepository productoRepository;
    private final AlmacenRepository almacenRepository;
    private final UserRepository userRepository;
    private final KardexMovimientoMapper kardexMapper;
    private final KardexConsultaMapper consultaMapper;
    private final AuditService auditService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KardexMovimientoResponseDTO registrarMovimiento(KardexMovimientoRequestDTO dto) {
        // 1) Validaciones de entrada
        if (dto.tipoMovimiento() != TipoMovimiento.ENTRADA && dto.tipoMovimiento() != TipoMovimiento.SALIDA) {
            throw new IllegalArgumentException("El tipo de movimiento es requerido");
        }
        if (dto.cantidadMovida() == null || dto.cantidadMovida().signum() <= 0) {
            throw new IllegalArgumentException("La cantidad movida debe ser mayor a cero");
        }
        if (dto.productoId() == null) throw new IllegalArgumentException("El producto es requerido");
        if (dto.almacenId() == null) throw new IllegalArgumentException("El almacén es requerido");

        // 2) Resolver entidades DENTRO del service
        Producto producto = productoRepository.findById(dto.productoId())
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado: " + dto.productoId()));
        Almacen almacen = almacenRepository.findWithSedeById(dto.almacenId())
                .orElseThrow(() -> new ResourceNotFoundException("Almacén no encontrado: " + dto.almacenId()));

        // 3) Almacén activo
        if (!Boolean.TRUE.equals(almacen.getActivo())) {
            throw new IllegalArgumentException("El almacén está inactivo");
        }

        // 4) Usuario SIEMPRE del token, nunca del DTO
        User usuario = obtenerUsuarioAutenticado();
        if (usuario == null) throw new IllegalArgumentException("Usuario autenticado no válido");

        // 5) Sede autorizada (admin ve todo; los demás solo su sede)
        boolean esAdmin = usuario.getRol() != null && usuario.getRol().getNombre() == RolNombre.ROLE_ADMIN;
        if (!esAdmin) {
            if (usuario.getSede() == null || almacen.getSede() == null
                    || !usuario.getSede().getId().equals(almacen.getSede().getId())) {
                throw new AccessDeniedException("No tiene acceso a un almacén de otra sede");
            }
        }

        // 6) Bloqueo pesimista del stock (FOR UPDATE vía findParaActualizar)
        Stock stock = stockRepository.findParaActualizar(almacen.getId(), producto.getId())
                .orElseGet(() -> crearStockInicial(producto, almacen));

        BigDecimal stockAnterior = stock.getCantidad() != null ? stock.getCantidad() : BigDecimal.ZERO;
        BigDecimal stockNuevo;

        if (dto.tipoMovimiento() == TipoMovimiento.ENTRADA) {
            stockNuevo = stockAnterior.add(dto.cantidadMovida());
        } else {
            // SALIDA: respetar permiteStockNegativo del producto
            boolean permiteNegativo = Boolean.TRUE.equals(producto.getPermiteStockNegativo());
            if (!permiteNegativo && stockAnterior.compareTo(dto.cantidadMovida()) < 0) {
                throw new IllegalArgumentException("Stock insuficiente en el almacén. Stock actual: " + stockAnterior);
            }
            stockNuevo = stockAnterior.subtract(dto.cantidadMovida());
        }

        stock.setCantidad(stockNuevo);
        stockRepository.save(stock);

        // 7) Crear movimiento (tipoReferencia AJUSTE por defecto si viene null)
        KardexMovimiento movimiento = KardexMovimiento.builder()
                .producto(producto)
                .almacen(almacen)
                .referenciaId(dto.referenciaId())
                .tipoReferencia(dto.tipoReferencia() != null ? dto.tipoReferencia() : TipoReferencia.AJUSTE)
                .tipoMovimiento(dto.tipoMovimiento())
                .cantidadMovida(dto.cantidadMovida())
                .stockResultante(stockNuevo)
                .costoUnitario(producto.getPrecioCompra())
                .precioVenta(producto.getPrecioVentaBase())
                .user(usuario)
                .observacion(dto.observacion())
                .build();

        return kardexMapper.toResponseDto(kardexRepository.save(movimiento));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<KardexMovimientoResponseDTO> registrarTransferencia(TransferenciaRequestDTO dto) {
        return registrarTransferencia(dto, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<KardexMovimientoResponseDTO> registrarTransferencia(TransferenciaRequestDTO dto, UUID referenciaId) {
        if (dto == null || dto.productoId() == null || dto.almacenOrigenId() == null || dto.almacenDestinoId() == null) {
            throw new IllegalArgumentException("Producto y almacenes de origen/destino son requeridos");
        }
        if (dto.cantidad() == null || dto.cantidad().signum() <= 0) {
            throw new IllegalArgumentException("La cantidad transferida debe ser mayor a cero");
        }
        if (dto.almacenOrigenId().equals(dto.almacenDestinoId())) {
            throw new IllegalArgumentException("El almacén de origen y destino no pueden ser el mismo.");
        }

        Producto producto = productoRepository.findById(dto.productoId())
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado con ID: " + dto.productoId()));

        Almacen almacenOrigen = almacenRepository.findWithSedeById(dto.almacenOrigenId())
                .orElseThrow(() -> new ResourceNotFoundException("Almacén de origen no encontrado con ID: " + dto.almacenOrigenId()));

        Almacen almacenDestino = almacenRepository.findWithSedeById(dto.almacenDestinoId())
                .orElseThrow(() -> new ResourceNotFoundException("Almacén de destino no encontrado con ID: " + dto.almacenDestinoId()));

        validarAlmacenOperacion(almacenOrigen);
        validarAlmacenOperacion(almacenDestino);

        // Bloqueo determinista (producto, almacén) para evitar deadlocks en transferencias cruzadas
        bloquearSaldosEnOrden(producto.getId(), almacenOrigen.getId(), almacenDestino.getId());

        String obsSalida = "Transferencia hacia " + almacenDestino.getNombre()
                + (dto.motivo() != null ? " - " + dto.motivo() : "");

        KardexMovimientoRequestDTO salidaDTO = new KardexMovimientoRequestDTO(
                producto.getId(),
                almacenOrigen.getId(),
                referenciaId,
                TipoReferencia.TRANSFERENCIA,
                TipoMovimiento.SALIDA,
                dto.cantidad(),
                obsSalida
        );
        KardexMovimientoResponseDTO salidaResponse = registrarMovimiento(salidaDTO);

        String obsEntrada = "Transferencia desde " + almacenOrigen.getNombre()
                + (dto.motivo() != null ? " - " + dto.motivo() : "");

        KardexMovimientoRequestDTO entradaDTO = new KardexMovimientoRequestDTO(
                producto.getId(),
                almacenDestino.getId(),
                referenciaId,
                TipoReferencia.TRANSFERENCIA,
                TipoMovimiento.ENTRADA,
                dto.cantidad(),
                obsEntrada
        );
        KardexMovimientoResponseDTO entradaResponse = registrarMovimiento(entradaDTO);

        return List.of(salidaResponse, entradaResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<KardexMovimientoResponseDTO> searchMovimientos(
            UUID productoId,
            UUID almacenId,
            LocalDateTime fechaInicio,
            LocalDateTime fechaFin,
            Pageable pageable) {
        UUID sedeId = sedeConsulta(almacenId, null);
        if (almacenId == null && sedeId != null) {
            return kardexRepository.buscarFiltrado(productoId, null, null, RangosConsulta.desde(fechaInicio), RangosConsulta.hasta(fechaFin), false,
                    sedeId, null, sinOrden(pageable)).map(kardexMapper::toResponseDto);
        }
        if (almacenId != null) almacenConAcceso(almacenId);
        return kardexRepository.searchMovimientos(productoId, almacenId, RangosConsulta.desde(fechaInicio), RangosConsulta.hasta(fechaFin), pageable)
                .map(kardexMapper::toResponseDto);
    }

    // ===================== MÓDULO 10: CONSULTAS =====================

    @Override
    @Transactional(readOnly = true)
    public Page<KardexMovimientoDetalleDTO> buscarMovimientos(UUID productoId, UUID almacenId, TipoReferencia tipoReferencia,
                                                              LocalDate desde, LocalDate hasta, UUID sedeId, String busqueda, Pageable pageable) {
        Rango r = rango(desde, hasta);
        UUID sedeAutorizada = sedeConsulta(almacenId, sedeId);
        return kardexRepository.buscarFiltrado(productoId, almacenId, tipoReferencia, r.inicio(), r.fin(), r.incluirSinFecha(),
                sedeAutorizada, patronBusqueda(busqueda), sinOrden(pageable)).map(consultaMapper::toDetalleDto);
    }

    @Override
    @Transactional(readOnly = true)
    public KardexMovimientoDetalleDTO obtenerMovimiento(UUID id) {
        KardexMovimiento movimiento = kardexRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Movimiento de Kardex no encontrado: " + id));
        exigirAccesoAlmacen(movimiento.getAlmacen());
        return kardexRepository.findDetalleById(id).map(consultaMapper::toDetalleDto)
                .orElseThrow(() -> new ResourceNotFoundException("Movimiento de Kardex no encontrado: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SaldoKardexDTO> saldos(UUID almacenId, UUID sedeId, String busqueda, boolean soloBajoMinimo, Pageable pageable) {
        UUID sedeAutorizada = sedeConsulta(almacenId, sedeId);
        return stockRepository.buscarSaldos(null, almacenId, sedeAutorizada, patronBusqueda(busqueda), soloBajoMinimo, sinOrden(pageable))
                .map(consultaMapper::toSaldoDto);
    }

    @Override
    @Transactional(readOnly = true)
    public SaldosProductoDTO saldosPorProducto(UUID productoId) {
        Producto p = productoRepository.findById(productoId)
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado: " + productoId));
        UUID sedeId = sedeConsulta(null, null);
        List<SaldoKardexDTO> almacenes = saldosDeProducto(productoId, null, sedeId);
        return new SaldosProductoDTO(p.getId(), p.getNombre(), p.getCodigoSku(), p.getUnidad(), sumar(almacenes), almacenes);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SaldoKardexDTO> saldosPorAlmacen(UUID almacenId, String busqueda, boolean soloBajoMinimo, Pageable pageable) {
        almacenConAcceso(almacenId);
        return saldos(almacenId, null, busqueda, soloBajoMinimo, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public ValorizacionKardexDTO valorizacion(UUID sedeId) {
        exigirAdmin();
        return construirValorizacion(null, null, sedeId);
    }

    @Override
    @Transactional(readOnly = true)
    public ValorizacionKardexDTO valorizacionAlmacen(UUID almacenId) {
        exigirAdmin();
        Almacen almacen = almacenRepository.findWithSedeById(almacenId)
                .orElseThrow(() -> new ResourceNotFoundException("Almacén no encontrado: " + almacenId));
        return construirValorizacion(almacenId, almacen.getNombre(), null);
    }

    @Override
    @Transactional(readOnly = true)
    public ReporteMermasDTO mermas(UUID productoId, UUID almacenId, LocalDate desde, LocalDate hasta, Pageable pageable) {
        exigirAdmin();
        Rango r = rango(desde, hasta);
        List<MermaPorProductoDTO> porProducto = new ArrayList<>();
        long movimientos = 0;
        BigDecimal valorTotal = BigDecimal.ZERO;
        for (MermaProjection m : kardexRepository.mermasPorProducto(productoId, almacenId, r.inicio(), r.fin(), r.incluirSinFecha())) {
            long n = m.getMovimientos() != null ? m.getMovimientos() : 0L;
            BigDecimal valor = escala(cero(m.getValor()));
            porProducto.add(new MermaPorProductoDTO(m.getProductoId(), m.getProductoNombre(), m.getCodigoSku(), m.getUnidad(),
                    n, cero(m.getCantidad()), valor));
            movimientos += n;
            valorTotal = valorTotal.add(valor);
        }
        Page<MermaKardexDTO> detalle = kardexRepository.buscarMermas(productoId, almacenId, r.inicio(), r.fin(), r.incluirSinFecha(),
                sinOrden(pageable)).map(consultaMapper::toMermaDto);
        return new ReporteMermasDTO(desde, hasta, productoId, almacenId, movimientos, escala(valorTotal), porProducto,
                detalle.getContent(), detalle.getNumber(), detalle.getSize(), detalle.getTotalElements(), detalle.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public HistorialProductoDTO historialProducto(UUID productoId, UUID almacenId, LocalDate desde, LocalDate hasta, Pageable pageable) {
        Producto p = productoRepository.findById(productoId)
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado: " + productoId));
        if (almacenId != null && !almacenRepository.existsById(almacenId)) {
            throw new ResourceNotFoundException("Almacén no encontrado: " + almacenId);
        }
        Rango r = rango(desde, hasta);
        UUID sedeId = sedeConsulta(almacenId, null);
        List<SaldoKardexDTO> saldos = saldosDeProducto(productoId, almacenId, sedeId);

        BigDecimal entradas = BigDecimal.ZERO, salidas = BigDecimal.ZERO;
        for (TotalPorTipoMovimientoProjection t : kardexRepository.totalesPorTipo(productoId, almacenId, r.inicio(), r.fin(), r.incluirSinFecha())) {
            if (t.getTipo() == TipoMovimiento.ENTRADA) entradas = entradas.add(cero(t.getCantidad()));
            else if (t.getTipo() == TipoMovimiento.SALIDA) salidas = salidas.add(cero(t.getCantidad()));
        }
        Page<KardexMovimientoDetalleDTO> movs = kardexRepository.buscar(productoId, almacenId, null, r.inicio(), r.fin(),
                r.incluirSinFecha(), sinOrden(pageable)).map(consultaMapper::toDetalleDto);

        return new HistorialProductoDTO(p.getId(), p.getNombre(), p.getCodigoSku(), p.getUnidad(), almacenId, desde, hasta,
                sumar(saldos), saldos, entradas, salidas, movs.getContent(), movs.getNumber(), movs.getSize(),
                movs.getTotalElements(), movs.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public ArchivoDescargaDTO exportarCsv(UUID productoId, UUID almacenId, TipoReferencia tipoReferencia, LocalDate desde, LocalDate hasta) {
        User admin = exigirAdmin();
        Rango r = rango(desde, hasta);
        Page<KardexMovimiento> pagina = kardexRepository.buscarParaExportar(productoId, almacenId, tipoReferencia,
                r.inicio(), r.fin(), r.incluirSinFecha(), PageRequest.of(0, LOTE_EXPORTACION));
        int maxFilas = configuracionService.reportes().maxFilasExportacion();   // Configuración > reportes
        if (pagina.getTotalElements() > maxFilas) {
            throw new IllegalArgumentException("La consulta tiene " + pagina.getTotalElements() + " movimientos y el máximo exportable es "
                    + maxFilas + ". Acote las fechas o los filtros.");
        }

        StringBuilder csv = new StringBuilder(CsvUtil.BOM);
        CsvUtil.linea(csv, COLUMNAS_CSV);
        long filas = 0;
        while (true) {
            for (KardexMovimiento k : pagina.getContent()) {
                CsvUtil.linea(csv, filaCsv(k));
                filas++;
            }
            if (!pagina.hasNext() || filas >= maxFilas) break;
            pagina = kardexRepository.buscarParaExportar(productoId, almacenId, tipoReferencia, r.inicio(), r.fin(),
                    r.incluirSinFecha(), pagina.nextPageable());
        }

        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("operacion", "EXPORTACION_KARDEX_CSV");
        datos.put("filas", filas);
        datos.put("productoId", productoId != null ? productoId.toString() : null);
        datos.put("almacenId", almacenId != null ? almacenId.toString() : null);
        datos.put("tipoReferencia", tipoReferencia != null ? tipoReferencia.name() : null);
        datos.put("desde", desde != null ? desde.toString() : null);
        datos.put("hasta", hasta != null ? hasta.toString() : null);
        auditService.registrar("kardex_movimientos", admin.getId(), AccionAudit.ACCESS, datos);
        log.info("Kardex exportado a CSV por {}: {} filas", admin.getEmail(), filas);

        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        return new ArchivoDescargaDTO(new ByteArrayResource(bytes),
                "kardex_" + LocalDateTime.now().format(FORMATO_ARCHIVO) + ".csv", "text/csv; charset=UTF-8");
    }

    // ===================== MÓDULO 10: HELPERS =====================

    private ValorizacionKardexDTO construirValorizacion(UUID almacenId, String almacenNombre, UUID sedeId) {
        List<ValorizacionProductoProjection> filas = stockRepository.valorizacion(almacenId, sedeId);
        BigDecimal total = BigDecimal.ZERO;
        long sinCosto = 0;
        List<BigDecimal> valores = new ArrayList<>();
        for (ValorizacionProductoProjection f : filas) {
            BigDecimal costo = cero(f.getCostoUnitario());
            if (costo.signum() <= 0) sinCosto++;
            BigDecimal valor = escala(cero(f.getCantidad()).multiply(costo));
            valores.add(valor);
            total = total.add(valor);
        }
        List<ValorizacionProductoDTO> detalle = new ArrayList<>();
        for (int i = 0; i < filas.size(); i++) {
            ValorizacionProductoProjection f = filas.get(i);
            BigDecimal valor = valores.get(i);
            BigDecimal pct = total.signum() > 0 ? valor.multiply(CIEN).divide(total, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
            BigDecimal venta = cero(f.getPrecioVenta());
            BigDecimal ganancia = venta.subtract(cero(f.getCostoUnitario())).multiply(cero(f.getCantidad())).setScale(2, RoundingMode.HALF_UP);
            detalle.add(new ValorizacionProductoDTO(f.getProductoId(), f.getProductoNombre(), f.getCodigoSku(), f.getUnidad(),
                    cero(f.getCantidad()), cero(f.getCostoUnitario()), valor, pct, f.getAlmacenes() != null ? f.getAlmacenes() : 0L,
                    venta, ganancia));
        }
        detalle.sort(Comparator.comparing(ValorizacionProductoDTO::valorTotal).reversed()
                .thenComparing(ValorizacionProductoDTO::productoNombre, Comparator.nullsLast(String::compareToIgnoreCase)));
        return new ValorizacionKardexDTO(almacenId, almacenNombre, sedeId, LocalDateTime.now(), escala(total),
                detalle.size(), sinCosto, detalle);
    }

    /** Todos los saldos de un producto (sin paginar: un producto está en pocos almacenes). */
    private List<SaldoKardexDTO> saldosDeProducto(UUID productoId, UUID almacenId, UUID sedeId) {
        return stockRepository.buscarSaldos(productoId, almacenId, sedeId, null, false, Pageable.unpaged())
                .map(consultaMapper::toSaldoDto).getContent();
    }

    static String[] filaCsv(KardexMovimiento k) {
        BigDecimal anterior = null;
        if (k.getStockResultante() != null && k.getCantidadMovida() != null) {
            anterior = k.getTipoMovimiento() == TipoMovimiento.ENTRADA
                    ? k.getStockResultante().subtract(k.getCantidadMovida()) : k.getStockResultante().add(k.getCantidadMovida());
        }
        Producto p = k.getProducto();
        Almacen a = k.getAlmacen();
        User u = k.getUser();
        return new String[] {
                k.getCreatedAt() != null ? k.getCreatedAt().format(FORMATO_FECHA) : "",
                k.getTipoMovimiento() != null ? k.getTipoMovimiento().name() : "",
                k.getTipoReferencia() != null ? k.getTipoReferencia().name() : "",
                k.getReferenciaId() != null ? k.getReferenciaId().toString() : "",
                p != null ? p.getNombre() : "",
                p != null ? p.getCodigoSku() : "",
                p != null ? p.getUnidad() : "",
                a != null ? a.getNombre() : "",
                a != null && a.getSede() != null ? a.getSede().getNombre() : "",
                CsvUtil.numero(k.getCantidadMovida()),
                CsvUtil.numero(anterior),
                CsvUtil.numero(k.getStockResultante()),
                u != null ? ((u.getNombre() != null ? u.getNombre() : "") + " " + (u.getApellido() != null ? u.getApellido() : "")).trim() : "",
                k.getObservacion()
        };
    }

    private record Rango(LocalDateTime inicio, LocalDateTime fin, boolean incluirSinFecha) { }

    /** Fechas inclusivas → [inicio, fin). Sin ninguna fecha se incluyen los movimientos antiguos sin fecha. */
    static Rango rango(LocalDate desde, LocalDate hasta) {
        LocalDate d = desde != null ? desde : FECHA_MIN;
        LocalDate h = hasta != null ? hasta : FECHA_MAX;
        if (d.isAfter(h)) throw new IllegalArgumentException("La fecha 'desde' no puede ser posterior a 'hasta'");
        return new Rango(d.atStartOfDay(), h.plusDays(1).atStartOfDay(), desde == null && hasta == null);
    }

    /** "%texto%" en minúsculas, o null si no hay búsqueda. */
    static String patronBusqueda(String busqueda) {
        if (busqueda == null || busqueda.isBlank()) return null;
        return "%" + busqueda.trim().toLowerCase(Locale.ROOT) + "%";
    }

    /** El orden lo fija cada consulta. */
    private static Pageable sinOrden(Pageable pageable) {
        return pageable.isPaged() ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()) : pageable;
    }

    private static BigDecimal sumar(List<SaldoKardexDTO> saldos) {
        return saldos.stream().map(SaldoKardexDTO::cantidad).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal cero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static BigDecimal escala(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private User exigirAdmin() {
        User u = obtenerUsuarioAutenticado();
        if (u == null) throw new IllegalStateException("Usuario autenticado no válido");
        if (u.getRol() == null || u.getRol().getNombre() != RolNombre.ROLE_ADMIN) {
            throw new AccessDeniedException("Solo un administrador puede ver costos del inventario y exportar el Kardex");
        }
        return u;
    }

    /**
     * Dos transferencias en sentido contrario deben tomar las filas de stock en el mismo orden
     * de almacén. El movimiento posterior reutiliza el bloqueo de esta transacción.
     */
    private void bloquearSaldosEnOrden(UUID productoId, UUID almacenOrigenId, UUID almacenDestinoId) {
        UUID primero = almacenOrigenId.compareTo(almacenDestinoId) <= 0 ? almacenOrigenId : almacenDestinoId;
        UUID segundo = primero.equals(almacenOrigenId) ? almacenDestinoId : almacenOrigenId;
        stockRepository.findParaActualizar(primero, productoId);
        stockRepository.findParaActualizar(segundo, productoId);
    }

    private Stock crearStockInicial(Producto producto, Almacen almacen) {
        Stock nuevoStock = new Stock();
        nuevoStock.setProducto(producto);
        nuevoStock.setAlmacen(almacen);
        nuevoStock.setCantidad(BigDecimal.ZERO);
        return nuevoStock;
    }

    private UUID sedeConsulta(UUID almacenId, UUID sedeSolicitada) {
        User usuario = obtenerUsuarioAutenticado();
        if (usuario == null) throw new AccessDeniedException("Usuario autenticado no válido");
        boolean admin = usuario.getRol() != null && usuario.getRol().getNombre() == RolNombre.ROLE_ADMIN;
        if (admin) {
            if (almacenId != null) almacenConAcceso(almacenId);
            return sedeSolicitada;
        }
        UUID propia = usuario.getSede() != null ? usuario.getSede().getId() : null;
        if (propia == null) throw new AccessDeniedException("Su usuario no tiene una sede asignada");
        if (sedeSolicitada != null && !propia.equals(sedeSolicitada)) {
            throw new AccessDeniedException("Solo puede consultar su propia sede");
        }
        if (almacenId != null) almacenConAcceso(almacenId);
        return propia;
    }

    private void almacenConAcceso(UUID almacenId) {
        Almacen almacen = almacenRepository.findWithSedeById(almacenId)
                .orElseThrow(() -> new ResourceNotFoundException("Almacén no encontrado: " + almacenId));
        if (!Boolean.TRUE.equals(almacen.getActivo())) throw new AccessDeniedException("El almacén está inactivo");
        User usuario = obtenerUsuarioAutenticado();
        boolean admin = usuario != null && usuario.getRol() != null && usuario.getRol().getNombre() == RolNombre.ROLE_ADMIN;
        if (!admin && (usuario == null || usuario.getSede() == null || almacen.getSede() == null
                || !usuario.getSede().getId().equals(almacen.getSede().getId()))) {
            throw new AccessDeniedException("No tiene acceso a un almacén de otra sede");
        }
    }

    private void exigirAccesoAlmacen(Almacen almacen) {
        if (almacen == null) throw new AccessDeniedException("El movimiento no tiene almacén");
        almacenConAcceso(almacen.getId());
    }

    private void validarAlmacenOperacion(Almacen almacen) {
        if (!Boolean.TRUE.equals(almacen.getActivo())) {
            throw new IllegalArgumentException("El almacén " + almacen.getNombre() + " está inactivo");
        }
        exigirAccesoAlmacen(almacen);
    }

    private User obtenerUsuarioAutenticado() {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            return null;
        }
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmailAndActivoTrue(email).orElse(null);
    }
}
