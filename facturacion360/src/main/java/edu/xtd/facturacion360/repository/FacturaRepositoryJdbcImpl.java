package edu.xtd.facturacion360.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import edu.xtd.facturacion360.dto.ClienteFactura;
import edu.xtd.facturacion360.dto.ConceptoFactura;
import edu.xtd.facturacion360.dto.DesgloseImpositivo;
import edu.xtd.facturacion360.dto.Factura;
import edu.xtd.facturacion360.dto.SugerenciaConcepto;

/**
 * Acceso a la tabla facturas mediante JdbcTemplate.
 */
@Repository
public class FacturaRepositoryJdbcImpl implements FacturaRepository {

	private static final Logger log = LoggerFactory.getLogger(FacturaRepositoryJdbcImpl.class);

	private static final String COLUMNAS_FACTURA = "f.idfactura, f.idcliente, c.nombre AS nombre_cliente, "
			+ "f.num_factura, f.fecha_emision, f.estado, f.observaciones, "
			+ "f.subtotal, f.importe_iva, f.total";

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	FacturaRowMapper facturaRowMapper;

	@Autowired
	ClienteFacturaRowMapper clienteFacturaRowMapper;

	@Autowired
	ConceptoFacturaRowMapper conceptoFacturaRowMapper;

	@Autowired
	DesgloseImpositivoRowMapper desgloseImpositivoRowMapper;

	@Override
	public Factura insertar(Factura factura) {
		String sqlInsertar = "INSERT INTO facturas "
				+ "(idcliente, num_factura, fecha_emision, estado, observaciones, subtotal, importe_iva, total, "
				+ "fecha_creacion, fecha_actualizacion) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";

		int filasInsertadas;
		try {
			filasInsertadas = jdbcTemplate.update(sqlInsertar,
				factura.idCliente(),
				factura.numeroFactura(),
				factura.fechaEmision(),
				factura.estado(),
				factura.observaciones(),
				factura.subtotal(),
				factura.importeIva(),
				factura.total());
		} catch (DuplicateKeyException error) {
			if (RestriccionSql.duplicado(error, "num_factura_UNIQUE")) {
				throw new NumeroFacturaDuplicadoException(error);
			}
			throw error;
		} catch (DataIntegrityViolationException error) {
			// Va DESPUES del catch de arriba a proposito: DuplicateKeyException hereda de esta,
			// asi que ponerla primero se tragaria las colisiones de numero.
			if (RestriccionSql.padreQueFalta(error, "FK_CLIENTE")) {
				throw new ClienteInexistenteException(error);
			}
			throw error;
		}

		Factura facturaInsertada = null;
		if (filasInsertadas == 1) {
			String sqlBuscar = "SELECT " + COLUMNAS_FACTURA + " FROM facturas f "
					+ "INNER JOIN clientes c ON f.idcliente = c.idcliente WHERE f.num_factura = ?";
			facturaInsertada = jdbcTemplate.queryForObject(sqlBuscar, facturaRowMapper, factura.numeroFactura());
		}

		return facturaInsertada;
	}

	@Override
	public int obtenerUltimoNumero(int anio) {
		String sql = "SELECT COALESCE(MAX(CAST(SUBSTRING(num_factura, 8) AS UNSIGNED)), 0) "
				+ "FROM facturas WHERE CHAR_LENGTH(num_factura) = 11 "
				+ "AND REGEXP_LIKE(num_factura, ?, 'i') AND SUBSTRING(num_factura, 8) <> '0000'";
		return jdbcTemplate.queryForObject(sql, Integer.class, "^F-" + anio + "-[0-9]{4}$");
	}

	@Override
	public void insertarConceptos(int idFactura, List<ConceptoFactura> conceptos) {
		String sql = "INSERT INTO conceptos (descripcion, cantidad, precio_unitario, descuento, "
				+ "porcentaje_iva, importe_iva, base_imponible, total, idfactura, clave_regimen, "
				+ "calificacion) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		for (ConceptoFactura concepto : conceptos) {
			jdbcTemplate.update(sql, concepto.descripcion(), concepto.cantidad(), concepto.precioUnitario(),
					concepto.descuento(), concepto.porcentajeIva(), concepto.importeIva(), concepto.baseImponible(),
					concepto.total(), idFactura, concepto.claveRegimen(), concepto.calificacion());
		}
	}


	@Override
	public List<Factura> buscar(String busqueda) {
		String sql = "SELECT " + COLUMNAS_FACTURA + " FROM facturas f "
				+ "INNER JOIN clientes c ON f.idcliente = c.idcliente";
		List<Factura> facturas;

		if (busqueda == null || busqueda.isBlank()) {
			sql = sql + " ORDER BY f.fecha_emision DESC, f.idfactura DESC";
			facturas = jdbcTemplate.query(sql, facturaRowMapper);
		} else {
			sql = sql + " WHERE f.num_factura LIKE ? ESCAPE '\\\\' OR c.nombre LIKE ? ESCAPE '\\\\' "
					+ "ORDER BY f.fecha_emision DESC, f.idfactura DESC";
			String textoBuscado = "%" + escaparComodines(busqueda.trim()) + "%";
			facturas = jdbcTemplate.query(sql, facturaRowMapper, textoBuscado, textoBuscado);
		}

		log.debug("buscar({}) devuelve {} facturas", busqueda, facturas.size());
		return facturas;
	}

	@Override
	public List<Factura> buscar(String busqueda, String estado) {
		StringBuilder sql = new StringBuilder("SELECT ")
				.append(COLUMNAS_FACTURA)
				.append(" FROM facturas f INNER JOIN clientes c ON f.idcliente = c.idcliente");

		List<Object> parametros = new ArrayList<>();
		List<String> condiciones = new ArrayList<>();

		boolean hayBusqueda = busqueda != null && !busqueda.isBlank();
		boolean hayEstado   = estado != null && !estado.isBlank();

		if (hayBusqueda) {
			condiciones.add("(f.num_factura LIKE ? ESCAPE '\\\\' OR c.nombre LIKE ? ESCAPE '\\\\')");
			String textoBuscado = "%" + escaparComodines(busqueda.trim()) + "%";
			parametros.add(textoBuscado);
			parametros.add(textoBuscado);
		}

		if (hayEstado) {
			condiciones.add("f.estado = ?");
			parametros.add(estado.toUpperCase());
		}

		if (!condiciones.isEmpty()) {
			sql.append(" WHERE ").append(String.join(" AND ", condiciones));
		}

		sql.append(" ORDER BY f.fecha_emision DESC, f.idfactura DESC");

		List<Factura> facturas = jdbcTemplate.query(
				sql.toString(), facturaRowMapper, parametros.toArray());

		log.debug("buscar({}, estado={}) devuelve {} facturas", busqueda, estado, facturas.size());
		return facturas;
	}

	@Override
	public Factura buscarPorId(int idFactura) {
		String sql = "SELECT " + COLUMNAS_FACTURA + " FROM facturas f "
				+ "INNER JOIN clientes c ON f.idcliente = c.idcliente WHERE f.idfactura = ?";
		List<Factura> facturas = jdbcTemplate.query(sql, facturaRowMapper, idFactura);

		Factura factura = null;
		if (!facturas.isEmpty()) {
			factura = facturas.get(0);
		}
		return factura;
	}

	@Override
	public Factura buscarPorIdParaActualizar(int idFactura) {
		// Bloquea solo la cabecera, no las filas del cliente, hasta finalizar la transacción.
		String sql = "SELECT f.*, NULL AS nombre_cliente FROM facturas f WHERE f.idfactura = ? FOR UPDATE";
		List<Factura> facturas = jdbcTemplate.query(sql, facturaRowMapper, idFactura);
		return facturas.isEmpty() ? null : facturas.get(0);
	}

	@Override
	public int actualizarBorrador(Factura factura) {
		String sql = "UPDATE facturas SET idcliente=?, fecha_emision=?, estado=?, observaciones=?, "
				+ "subtotal=?, importe_iva=?, total=?, fecha_actualizacion=NOW() "
				+ "WHERE idfactura=? AND estado='BORRADOR'";
		try {
			return jdbcTemplate.update(sql, factura.idCliente(), factura.fechaEmision(), factura.estado(), factura.observaciones(),
					factura.subtotal(), factura.importeIva(), factura.total(), factura.idFactura());
		} catch (DataIntegrityViolationException error) {
			// Al editar se puede cambiar el cliente, asi que aqui tambien cabe apuntar a uno
			// que ya no esta.
			if (RestriccionSql.padreQueFalta(error, "FK_CLIENTE")) {
				throw new ClienteInexistenteException(error);
			}
			throw error;
		}
	}

	@Override
	public void eliminarConceptos(int idFactura) {
		jdbcTemplate.update("DELETE FROM conceptos WHERE idfactura=?", idFactura);
	}

	@Override
	public ClienteFactura buscarCliente(int idCliente) {
		String sql = "SELECT idcliente, nombre, nif_cif, direccion, codigopostal, poblacion, "
				+ "provincia, telefono, email FROM clientes WHERE idcliente = ?";
		List<ClienteFactura> clientes = jdbcTemplate.query(sql, clienteFacturaRowMapper, idCliente);

		ClienteFactura cliente = null;
		if (!clientes.isEmpty()) {
			cliente = clientes.get(0);
		}
		return cliente;
	}
	
	@Override
	public List<Factura> buscarPorTrimestre(LocalDate fechaInicio, LocalDate fechaFin) {
		String sql = "SELECT " + COLUMNAS_FACTURA + " FROM facturas f "
				+ "INNER JOIN clientes c ON f.idcliente = c.idcliente "
				+ "WHERE f.fecha_emision >= ? AND f.fecha_emision < ? "
				+ "ORDER BY f.fecha_emision, f.idfactura";

		List<Factura> facturas = jdbcTemplate.query(sql, facturaRowMapper, fechaInicio, fechaFin);
		log.debug("buscarPorTrimestre({}, {}) devuelve {} facturas", fechaInicio, fechaFin, facturas.size());
		return facturas;
	}

	@Override
	public List<SugerenciaConcepto> buscarSugerenciasConceptos(String texto, int limite) {
		String sql = "SELECT c.descripcion, c.precio_unitario, c.descuento, c.porcentaje_iva "
				+ "FROM conceptos c INNER JOIN facturas f ON f.idfactura = c.idfactura "
				+ "WHERE c.descripcion LIKE ? ESCAPE '\\\\' "
				+ "ORDER BY f.fecha_emision DESC, f.idfactura DESC, c.idconcepto DESC";
		return jdbcTemplate.query(sql, resultado -> {
			List<SugerenciaConcepto> sugerencias = new ArrayList<>();
			Set<String> descripciones = new HashSet<>();
			// La primera aparición es la más reciente. El límite se aplica después de deduplicar.
			while (sugerencias.size() < limite && resultado.next()) {
				String descripcion = resultado.getString("descripcion").trim();
				if (descripciones.add(descripcion.toLowerCase(Locale.ROOT))) {
					sugerencias.add(new SugerenciaConcepto(descripcion, resultado.getBigDecimal("precio_unitario"),
							resultado.getBigDecimal("descuento"), resultado.getBigDecimal("porcentaje_iva")));
				}
			}
			return sugerencias;
		}, "%" + escaparComodines(texto) + "%");
	}

	/** Evita que los caracteres propios de LIKE cambien el significado de la búsqueda. */
	private String escaparComodines(String texto) {
		return texto.replace("\\", "\\\\")
				.replace("%", "\\%")
				.replace("_", "\\_");
	}
	
	@Override
	public void insertarDesglose(int idFactura, List<DesgloseImpositivo> desglose) {
		String sql = "INSERT INTO desglose_impositivo (idfactura, impuesto, clave_regimen, "
				+ "calificacion, tipo_impositivo, base_imponible, cuota_repercutida) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?)";
		for (DesgloseImpositivo linea : desglose) {
			jdbcTemplate.update(sql, idFactura, linea.impuesto(), linea.claveRegimen(),
					linea.calificacion(), linea.tipoImpositivo(), linea.baseImponible(),
					linea.cuotaRepercutida());
		}
	}

	@Override
	public void eliminarDesglose(int idFactura) {
		jdbcTemplate.update("DELETE FROM desglose_impositivo WHERE idfactura=?", idFactura);
	}

	@Override
	public List<DesgloseImpositivo> buscarDesglose(int idFactura) {
		// El mismo orden con el que se guardo, que es el que fija CalculadoraDesglose. Si esto
		// saliera en un orden distinto cada vez, la huella que se firme manana cambiaria sin
		// que hubiera cambiado ni un importe.
		String sql = "SELECT impuesto, clave_regimen, calificacion, tipo_impositivo, "
				+ "base_imponible, cuota_repercutida FROM desglose_impositivo "
				+ "WHERE idfactura = ? ORDER BY impuesto, clave_regimen, calificacion, tipo_impositivo";
		return jdbcTemplate.query(sql, desgloseImpositivoRowMapper, idFactura);
	}

	@Override
	public List<ConceptoFactura> buscarConceptos(int idFactura) {
		String sql = "SELECT idconcepto, descripcion, cantidad, precio_unitario, descuento, "
				+ "porcentaje_iva, importe_iva, base_imponible, total, clave_regimen, calificacion "
				+ "FROM conceptos WHERE idfactura = ? ORDER BY idconcepto";
		return jdbcTemplate.query(sql, conceptoFacturaRowMapper, idFactura);
	}
}
