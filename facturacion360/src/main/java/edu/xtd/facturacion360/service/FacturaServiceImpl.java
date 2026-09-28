package edu.xtd.facturacion360.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import edu.xtd.facturacion360.dto.ClienteFactura;
import edu.xtd.facturacion360.dto.ConceptoFactura;
import edu.xtd.facturacion360.dto.ConceptoRequest;
import edu.xtd.facturacion360.dto.DesgloseImpositivo;
import edu.xtd.facturacion360.dto.DetalleFactura;
import edu.xtd.facturacion360.dto.Factura;
import edu.xtd.facturacion360.dto.FacturaRequest;
import edu.xtd.facturacion360.dto.ResumenTrimestralFactura;
import edu.xtd.facturacion360.dto.SugerenciaConcepto;
import edu.xtd.facturacion360.repository.FacturaRepository;
import edu.xtd.facturacion360.repository.FacturaRepository.NumeroFacturaDuplicadoException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

/**
 * Lógica necesaria para crear y buscar facturas.
 */
@Service
public class FacturaServiceImpl implements FacturaService {

	@Autowired
	FacturaRepository facturaRepository;

	@Autowired
	Validator validador;

	@Autowired
	PlatformTransactionManager gestorTransacciones;

	private static final BigDecimal IMPORTE_MAXIMO = new BigDecimal("99999999.99");

	public record CalculoFactura(List<ConceptoFactura> conceptos, BigDecimal subtotal,
			BigDecimal importeIva, BigDecimal total) {
	}

	/** Calcula sin acceder al repositorio ni modificar los datos recibidos. */
	public CalculoFactura calcularImportes(List<ConceptoRequest> conceptos, String estado) {
		if (estado == null || !List.of("BORRADOR", "EMITIDA", "ANULADA").contains(estado)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"El estado debe ser BORRADOR, EMITIDA o ANULADA");
		}
		if (conceptos == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La lista de conceptos es obligatoria");
		}
		if ("EMITIDA".equals(estado) && conceptos.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Para emitir la factura hace falta al menos un concepto");
		}

		List<ConceptoFactura> conceptosCalculados = new ArrayList<>();
		BigDecimal subtotal = new BigDecimal("0.00");
		BigDecimal ivaFactura = new BigDecimal("0.00");
		BigDecimal totalFactura = new BigDecimal("0.00");

		for (ConceptoRequest concepto : conceptos) {
			if (concepto == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El concepto no puede ser nulo");
			}
			Set<ConstraintViolation<ConceptoRequest>> errores = validador.validate(concepto);
			if (!errores.isEmpty()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errores.iterator().next().getMessage());
			}

			BigDecimal bruto = concepto.precioUnitario().multiply(BigDecimal.valueOf(concepto.cantidad()));
			BigDecimal descuentoImporte = bruto.multiply(concepto.descuento()).movePointLeft(2);
			BigDecimal baseImponible = bruto.subtract(descuentoImporte).setScale(2, RoundingMode.HALF_UP);
			BigDecimal importeIva = baseImponible.multiply(concepto.porcentajeIva())
					.movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
			BigDecimal total = baseImponible.add(importeIva);
			validarImporte(baseImponible);
			validarImporte(importeIva);
			validarImporte(total);

			conceptosCalculados.add(new ConceptoFactura(0, concepto.descripcion().trim(), concepto.cantidad(),
					concepto.precioUnitario(), concepto.descuento(), concepto.porcentajeIva(),
					importeIva, baseImponible, total, concepto.claveRegimen(), concepto.calificacion()));
			subtotal = subtotal.add(baseImponible);
			ivaFactura = ivaFactura.add(importeIva);
			totalFactura = totalFactura.add(total);
			validarImporte(subtotal);
			validarImporte(ivaFactura);
			validarImporte(totalFactura);
		}
		return new CalculoFactura(List.copyOf(conceptosCalculados), subtotal, ivaFactura, totalFactura);
	}

	private void validarImporte(BigDecimal importe) {
		if (importe.compareTo(IMPORTE_MAXIMO) > 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El importe supera el máximo de 99.999.999,99");
		}
	}

	@Override
	public Factura crear(FacturaRequest facturaRequest) {
		validarPeticion(facturaRequest);
		int anio = facturaRequest.fechaEmision().getYear();
		CalculoFactura calculo = calcularImportes(facturaRequest.conceptos(), facturaRequest.estado());
		TransactionTemplate transaccion = new TransactionTemplate(gestorTransacciones);
		transaccion.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

		for (int intento = 1; intento <= 3; intento++) {
			try {
				return transaccion.execute(estadoTransaccion -> {
					int ultimoNumero = facturaRepository.obtenerUltimoNumero(anio);
					if (ultimoNumero >= 9999) {
						throw new ResponseStatusException(HttpStatus.CONFLICT, "Se ha alcanzado el límite de 9999 facturas para " + anio);
					}
					String numeroFactura = String.format(Locale.ROOT, "F-%04d-%04d", anio, ultimoNumero + 1);
					Factura factura = new Factura(0, facturaRequest.idCliente(), null, numeroFactura,
							facturaRequest.fechaEmision(), facturaRequest.estado(), facturaRequest.observaciones(),
							calculo.subtotal(), calculo.importeIva(), calculo.total());
					Factura facturaNueva = facturaRepository.insertar(factura);
					if (facturaNueva == null) {
						throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
								"No se ha podido guardar la factura. Vuelve a intentarlo en unos segundos");
					}
					facturaRepository.insertarConceptos(facturaNueva.idFactura(), calculo.conceptos());
					facturaRepository.insertarDesglose(facturaNueva.idFactura(),
							CalculadoraDesglose.calcular(calculo.conceptos()));
					return facturaNueva;
				});
			} catch (NumeroFacturaDuplicadoException error) {
				// execute ya ha revertido el intento; el siguiente vuelve a consultar en otra transacción.
				if (intento == 3) {
					throw new ResponseStatusException(HttpStatus.CONFLICT,
							"No se pudo asignar el número tras tres intentos. Vuelve a intentarlo.", error);
				}
			}
		}
		throw new IllegalStateException("No se completó el guardado de la factura");
	}

	private void validarPeticion(FacturaRequest facturaRequest) {
		if (facturaRequest == null) {
			// Solo se llega aqui si el cuerpo viaja vacio; Spring suele cazarlo antes.
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"No se han recibido los datos de la factura");
		}
		Set<ConstraintViolation<FacturaRequest>> errores = validador.validate(facturaRequest);
		if (!errores.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errores.iterator().next().getMessage());
		}
		int anio = facturaRequest.fechaEmision().getYear();
		if (anio < 1000 || anio > 9999) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El año debe estar entre 1000 y 9999");
		}
	}

	@Override
	public Factura editarBorrador(int idFactura, FacturaRequest facturaRequest) {
		if (idFactura <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"El identificador de factura tiene que ser un número mayor que cero, y se ha "
							+ "recibido " + idFactura);
		}
		validarPeticion(facturaRequest);
		if (!List.of("BORRADOR", "EMITIDA").contains(facturaRequest.estado())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La edición solo admite el estado BORRADOR o EMITIDA");
		}
		CalculoFactura calculo = calcularImportes(facturaRequest.conceptos(), facturaRequest.estado());
		TransactionTemplate transaccion = new TransactionTemplate(gestorTransacciones);
		return transaccion.execute(estadoTransaccion -> {
			Factura anterior = facturaRepository.buscarPorIdParaActualizar(idFactura);
			if (anterior == null) {
				throw new ResponseStatusException(HttpStatus.NOT_FOUND,
						"La factura " + idFactura + " ya no existe");
			}
			if (!"BORRADOR".equals(anterior.estado())) {
				throw new ResponseStatusException(HttpStatus.CONFLICT,
						"La factura " + anterior.numeroFactura() + " está " + anterior.estado()
								+ " y solo se pueden editar las que están en BORRADOR");
			}
			int anioNumero = anterior.fechaEmision().getYear();
			// Los números manuales ajenos al formato mantienen el año de su fecha anterior.
			if (anterior.numeroFactura().matches("(?i)F-[0-9]{4}-[0-9]{4}")) {
				anioNumero = Integer.parseInt(anterior.numeroFactura().substring(2, 6));
			}
			if (facturaRequest.fechaEmision().getYear() != anioNumero) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha debe conservar el año " + anioNumero + " del número de factura");
			}
			Factura modificada = new Factura(idFactura, facturaRequest.idCliente(), null, anterior.numeroFactura(),
					facturaRequest.fechaEmision(), facturaRequest.estado(), facturaRequest.observaciones(),
					calculo.subtotal(), calculo.importeIva(), calculo.total());
			if (facturaRepository.actualizarBorrador(modificada) != 1) {
				// Se lee con FOR UPDATE justo antes, asi que llegar aqui significa que la fila ha
				// cambiado pese al bloqueo. No se inventa un motivo que no se puede comprobar:
				// se dice lo unico seguro, que no se ha guardado, y que hay que volver a abrirla.
				throw new ResponseStatusException(HttpStatus.CONFLICT,
						"No se han guardado los cambios de la factura " + anterior.numeroFactura()
								+ ". Vuelve a abrirla para ver como esta ahora");
			}
			facturaRepository.eliminarConceptos(idFactura);
			facturaRepository.insertarConceptos(idFactura, calculo.conceptos());

			// El desglose se rehace entero, no se parchea: es el reflejo de los conceptos que
			// acaban de sustituirse, y dejar lineas del anterior seria declarar bases que ya no
			// existen.
			facturaRepository.eliminarDesglose(idFactura);
			facturaRepository.insertarDesglose(idFactura, CalculadoraDesglose.calcular(calculo.conceptos()));
			return facturaRepository.buscarPorId(idFactura);
		});
	}

	@Override
	public List<Factura> buscar(String busqueda) {
		return facturaRepository.buscar(busqueda);
	}

	@Override
	public List<Factura> buscar(String busqueda, String estado) {
		return facturaRepository.buscar(busqueda, estado);
	}

	@Override
	public List<SugerenciaConcepto> buscarSugerenciasConceptos(String texto, int limite) {
		String textoBuscado = texto == null ? "" : texto.trim();
		if (textoBuscado.length() < 2 || textoBuscado.length() > 50) {
			return List.of();
		}
		int limiteAcotado = Math.max(1, Math.min(limite, 20));
		return facturaRepository.buscarSugerenciasConceptos(textoBuscado, limiteAcotado);
	}

	@Transactional(readOnly = true)
	@Override
	public DetalleFactura obtenerDetalle(int idFactura) {
		if (idFactura <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"El identificador de factura tiene que ser un número mayor que cero, y se ha "
							+ "recibido " + idFactura);
		}

		Factura factura = facturaRepository.buscarPorId(idFactura);
		if (factura == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND,
					"La factura " + idFactura + " ya no existe");
		}

		ClienteFactura cliente = facturaRepository.buscarCliente(factura.idCliente());
		if (cliente == null) {
			// Esto no lo puede provocar el usuario: significa que hay una factura apuntando a un
			// cliente que no esta, o sea un dato incoherente en la base de datos.
			throw new ResponseStatusException(HttpStatus.NOT_FOUND,
					"La factura " + factura.numeroFactura() + " apunta a un cliente que ya no existe");
		}

		List<ConceptoFactura> conceptos = facturaRepository.buscarConceptos(idFactura);
		List<DesgloseImpositivo> desglose = facturaRepository.buscarDesglose(idFactura);

		// Las facturas anteriores a esta migracion no tienen desglose guardado, y devolverlas
		// sin el dejaria la factura impresa sin su cuadro de IVA. Se calcula al vuelo a partir
		// de sus conceptos: es la MISMA operacion que se hizo al guardarlas, asi que sale lo
		// mismo. Las nuevas si lo tienen guardado y ese es el que manda.
		if (desglose.isEmpty()) {
			desglose = CalculadoraDesglose.calcular(conceptos);
		}

		return new DetalleFactura(factura, cliente, conceptos, desglose);
	}


	@Override
	public ResumenTrimestralFactura listarTrimestre(int anio, int trimestre) {
		if (anio < 2000 || anio > 2100) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El año debe estar entre 2000 y 2100");
		}
		if (trimestre < 1 || trimestre > 4) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El trimestre debe estar entre 1 y 4");
		}

		int primerMes = (trimestre - 1) * 3 + 1;
		LocalDate fechaInicio = LocalDate.of(anio, primerMes, 1);
		LocalDate fechaFin = fechaInicio.plusMonths(3);
		List<Factura> facturas = facturaRepository.buscarPorTrimestre(fechaInicio, fechaFin);

		BigDecimal subtotal = BigDecimal.ZERO;
		BigDecimal importeIva = BigDecimal.ZERO;
		BigDecimal total = BigDecimal.ZERO;
		for (Factura factura : facturas) {
			subtotal = subtotal.add(factura.subtotal());
			importeIva = importeIva.add(factura.importeIva());
			total = total.add(factura.total());
		}

		return new ResumenTrimestralFactura(anio, trimestre, facturas, subtotal, importeIva, total);
	}

}
