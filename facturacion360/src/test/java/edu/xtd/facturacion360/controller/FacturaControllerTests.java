package edu.xtd.facturacion360.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import edu.xtd.facturacion360.dto.Factura;
import edu.xtd.facturacion360.dto.SugerenciaConcepto;
import edu.xtd.facturacion360.repository.FacturaRepository;
import edu.xtd.facturacion360.service.FacturaServiceImpl;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

/** Contrato HTTP con servicio real y persistencia simulada; no arranca la aplicación. */
class FacturaControllerTests {
	MockMvc clienteHttp;
	FacturaRepository repositorio;
	ValidatorFactory validadores;
	private static final String PETICION = """
			{"idCliente":1,"fechaEmision":"2026-09-14","estado":"EMITIDA","observaciones":"",
			 "conceptos":[{"descripcion":"Servicio","cantidad":2,"precioUnitario":10,"descuento":0,"porcentajeIva":21}]}
			""";

	@BeforeEach
	void preparar() {
		validadores = Validation.buildDefaultValidatorFactory();
		repositorio = mock(FacturaRepository.class);
		when(repositorio.obtenerUltimoNumero(2026)).thenReturn(8);
		when(repositorio.insertar(any(Factura.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
		PlatformTransactionManager gestor = mock(PlatformTransactionManager.class);
		when(gestor.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		FacturaServiceImpl servicio = new FacturaServiceImpl();
		ReflectionTestUtils.setField(servicio, "facturaRepository", repositorio);
		ReflectionTestUtils.setField(servicio, "gestorTransacciones", gestor);
		ReflectionTestUtils.setField(servicio, "validador", validadores.getValidator());
		FacturaController controlador = new FacturaController();
		controlador.facturaService = servicio;
		clienteHttp = MockMvcBuilders.standaloneSetup(controlador)
				.setControllerAdvice(new ManejadorExcepciones())
				.setValidator(new SpringValidatorAdapter(validadores.getValidator())).build();
	}

	@AfterEach
	void cerrar() {
		validadores.close();
	}

	@Test
	void sugerenciasDevuelvenSoloLosCuatroDatosNecesarios() throws Exception {
		when(repositorio.buscarSugerenciasConceptos("manten", 8)).thenReturn(java.util.List.of(
				new SugerenciaConcepto("Mantenimiento", new java.math.BigDecimal("120.00"),
						new java.math.BigDecimal("5.00"), new java.math.BigDecimal("21.00"))));
		clienteHttp.perform(get("/factura/conceptos/sugerencias").param("texto", " manten "))
				.andExpect(status().isOk()).andExpect(content().json("""
					[{"descripcion":"Mantenimiento","precioUnitario":120,"descuento":5,"porcentajeIva":21}]
					""", org.springframework.test.json.JsonCompareMode.STRICT));
		verify(repositorio).buscarSugerenciasConceptos("manten", 8);
		verifyNoMoreInteractions(repositorio);
	}

	void prepararBorrador(String estado, String numero, String fecha) {
		Factura anterior = new Factura(7, 1, "Cliente", numero, java.time.LocalDate.parse(fecha), estado,
				"Anterior", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
		when(repositorio.buscarPorIdParaActualizar(7)).thenReturn(anterior);
		doAnswer(invocacion -> {
			doReturn(invocacion.getArgument(0)).when(repositorio).buscarPorId(7);
			return 1;
		}).when(repositorio).actualizarBorrador(any());
	}

	@Test
	void editaBorradorConNumeroIntactoYCalculosDelServidor() throws Exception {
		prepararBorrador("BORRADOR", "F-2026-0007", "2026-01-01");
		String peticion = PETICION.replace("EMITIDA", "BORRADOR");
		clienteHttp.perform(put("/factura/7/borrador").contentType(MediaType.APPLICATION_JSON).content(peticion))
				.andExpect(status().isOk()).andExpect(jsonPath("$.idFactura").value(7))
				.andExpect(jsonPath("$.numeroFactura").value("F-2026-0007"))
				.andExpect(jsonPath("$.estado").value("BORRADOR"))
				.andExpect(jsonPath("$.subtotal").value(20)).andExpect(jsonPath("$.importeIva").value(4.2))
				.andExpect(jsonPath("$.total").value(24.2));
		verify(repositorio).eliminarConceptos(7);
		verify(repositorio).insertarConceptos(eq(7), anyList());
		verify(repositorio, never()).obtenerUltimoNumero(anyInt());
		verify(repositorio, never()).insertar(any());
	}

	@Test
	void edicionRechazaLosEstadosNoEditables() throws Exception {
		for (String estado : java.util.List.of("EMITIDA", "ANULADA")) {
			prepararBorrador(estado, "F-2026-0007", "2026-01-01");
			clienteHttp.perform(put("/factura/7/borrador").contentType(MediaType.APPLICATION_JSON)
					.content(PETICION.replace("EMITIDA", "BORRADOR")))
					.andExpect(status().isConflict()).andExpect(content().string(org.hamcrest.Matchers.containsString("BORRADOR")));
		}
		verify(repositorio, never()).actualizarBorrador(any());
		verify(repositorio, never()).eliminarConceptos(anyInt());
	}

	@Test
	void edicionConservaElAnoDelNumeroOTomaElAnoManualAnterior() throws Exception {
		for (String numero : java.util.List.of("F-2026-0007", "f-2026-0007", "MANUAL-7")) {
			prepararBorrador("BORRADOR", numero, "2026-01-01");
			clienteHttp.perform(put("/factura/7/borrador").contentType(MediaType.APPLICATION_JSON)
					.content(PETICION.replace("EMITIDA", "BORRADOR").replace("2026-09-14", "2027-09-14")))
					.andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.containsString("2026")));
		}
		verify(repositorio, never()).actualizarBorrador(any());
		prepararBorrador("BORRADOR", "F-2026-0007", "2025-01-01");
		clienteHttp.perform(put("/factura/7/borrador").contentType(MediaType.APPLICATION_JSON)
				.content(PETICION.replace("EMITIDA", "BORRADOR"))).andExpect(status().isOk());
	}

	/**
	 * Un id que no puede existir se rechaza antes de preguntar a la base de datos.
	 *
	 * <p>El {@code verifyNoInteractions} es la mitad que importa: sin él, esta prueba seguiría
	 * en verde aunque alguien moviera la comprobación del id detrás de la consulta, y entonces
	 * cada petición con un id inválido gastaría una ida a la base de datos para nada.</p>
	 */
	@Test
	void idInvalidoSeRechazaSinTocarElRepositorio() throws Exception {
		String borrador = PETICION.replace("EMITIDA", "BORRADOR");

		clienteHttp.perform(put("/factura/0/borrador").contentType(MediaType.APPLICATION_JSON).content(borrador))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(repositorio);
	}

	/**
	 * Una línea con una cantidad imposible se para en la validación, no en la base de datos.
	 *
	 * <p>Los tres valores son los tres agujeros distintos: el cero, el fraccionario —que el
	 * lector de JSON truncaría a entero si no se leyera como {@code BigDecimal}— y el que se
	 * sale de un {@code int}.</p>
	 */
	@Test
	void conceptosInvalidosSeRechazanSinTocarElRepositorio() throws Exception {
		String borrador = PETICION.replace("EMITIDA", "BORRADOR");

		for (String cantidad : java.util.List.of("0", "1.5", "2147483648")) {
			clienteHttp.perform(put("/factura/7/borrador").contentType(MediaType.APPLICATION_JSON)
					.content(borrador.replace("\"cantidad\":2", "\"cantidad\":" + cantidad)))
					.andExpect(status().isBadRequest());
		}

		verifyNoInteractions(repositorio);
	}

	/**
	 * Una factura que no está devuelve 404, y solo se pregunta por ella una vez.
	 *
	 * <p>Ojo con lo que NO comprueba esta prueba: el estado que venga en el cuerpo no se mira
	 * aquí. {@code FacturaRequest} admite {@code BORRADOR}, {@code EMITIDA} y {@code ANULADA}
	 * por igual, así que una petición con {@code EMITIDA} pasa la validación y llega al
	 * repositorio como cualquier otra. Quien decide si se puede editar es el estado
	 * <strong>guardado</strong>, y eso se comprueba en
	 * {@link #edicionRechazaFacturaQueYaNoEsBorrador()}.</p>
	 */
	@Test
	void idInexistenteDevuelve404YConsultaUnaSolaVez() throws Exception {
		String borrador = PETICION.replace("EMITIDA", "BORRADOR");

		clienteHttp.perform(put("/factura/99/borrador").contentType(MediaType.APPLICATION_JSON).content(borrador))
				.andExpect(status().isNotFound());

		verify(repositorio).buscarPorIdParaActualizar(99);
		verifyNoMoreInteractions(repositorio);
	}

	/**
	 * Editar una factura que ya está emitida no escribe nada.
	 *
	 * <p>Aquí la factura <strong>sí existe</strong>, así que el 404 no tapa el caso: lo que se
	 * comprueba es que, sabiendo que está emitida, no se llegue a escribir. El
	 * {@code never()} sobre {@code actualizarBorrador} es lo único que lo demuestra — devolver
	 * el código de error correcto y haber escrito igualmente sería indistinguible sin él.</p>
	 */
	@Test
	void edicionRechazaFacturaQueYaNoEsBorrador() throws Exception {
		prepararBorrador("EMITIDA", "F-2026-0007", "2026-01-01");

		clienteHttp.perform(put("/factura/7/borrador").contentType(MediaType.APPLICATION_JSON)
				.content(PETICION.replace("EMITIDA", "BORRADOR")))
				.andExpect(status().isConflict());

		verify(repositorio, never()).actualizarBorrador(any());
		verify(repositorio, never()).eliminarConceptos(anyInt());
	}

	@Test
	void edicionNoConfiaEnNumeroNiImportesEnviados() throws Exception {
		prepararBorrador("BORRADOR", "F-2026-0007", "2026-01-01");
		String peticion = PETICION.replace("EMITIDA", "BORRADOR")
				.replace("\"idCliente\":", "\"numeroFactura\":\"FALSO\",\"total\":999,\"idCliente\":")
				.replace("\"descripcion\":", "\"baseImponible\":999,\"importeIva\":999,\"total\":999,\"descripcion\":");
		var respuesta = clienteHttp.perform(put("/factura/7/borrador").contentType(MediaType.APPLICATION_JSON)
				.content(peticion)).andReturn().getResponse();
		assertTrue(respuesta.getStatus() == 400 || respuesta.getStatus() == 200);
		if (respuesta.getStatus() == 200) {
			var captor = org.mockito.ArgumentCaptor.forClass(Factura.class);
			verify(repositorio).actualizarBorrador(captor.capture());
			assertEquals("F-2026-0007", captor.getValue().numeroFactura());
			assertEquals(new java.math.BigDecimal("24.20"), captor.getValue().total());
		} else {
			verifyNoInteractions(repositorio);
		}
		verify(repositorio, never()).obtenerUltimoNumero(anyInt());
	}

	@Test
	void edicionNoExponeDetallesSqlAnteFallo() throws Exception {
		prepararBorrador("BORRADOR", "F-2026-0007", "2026-01-01");
		doThrow(new org.springframework.dao.DataAccessResourceFailureException("SQL secreto índice interno"))
				.when(repositorio).eliminarConceptos(7);
		clienteHttp.perform(put("/factura/7/borrador").contentType(MediaType.APPLICATION_JSON)
				.content(PETICION.replace("EMITIDA", "BORRADOR")))
				.andExpect(status().isInternalServerError())
				// El cuerpo de los errores es ahora un ProblemDetail (RFC 9457), asi que el
				// motivo va en su campo detail y no suelto. Y se comprueba ademas lo que de
				// verdad protege esta prueba: que el mensaje interno de SQL NO sale.
				.andExpect(jsonPath("$.detail").value("Error al acceder a la base de datos"))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("SQL secreto"))));
	}

	@Test
	void sugerenciasVaciasOCortasNoConsultanElRepositorio() throws Exception {
		clienteHttp.perform(get("/factura/conceptos/sugerencias"))
				.andExpect(status().isOk()).andExpect(content().json("[]"));
		for (String texto : java.util.List.of("", "   ", " m ", "x".repeat(51))) {
			clienteHttp.perform(get("/factura/conceptos/sugerencias").param("texto", texto))
					.andExpect(status().isOk()).andExpect(content().json("[]"));
		}
		FacturaServiceImpl servicio = new FacturaServiceImpl();
		assertTrue(servicio.buscarSugerenciasConceptos(null, 8).isEmpty());
		verifyNoInteractions(repositorio);
	}

	@Test
	void sugerenciasAcotanLimiteYRechazanLimiteNoNumerico() throws Exception {
		for (String limite : java.util.List.of("1000000", "0", "-10")) {
			clienteHttp.perform(get("/factura/conceptos/sugerencias").param("texto", "ma").param("limite", limite))
					.andExpect(status().isOk());
		}
		verify(repositorio).buscarSugerenciasConceptos("ma", 20);
		verify(repositorio, times(2)).buscarSugerenciasConceptos("ma", 1);
		clienteHttp.perform(get("/factura/conceptos/sugerencias").param("texto", "ma").param("limite", "abc"))
				.andExpect(status().isBadRequest());
		verifyNoMoreInteractions(repositorio);
	}

	@Test
	void aceptaContratoNuevoYDevuelveNumeroYTotalesDelServidor() throws Exception {
		clienteHttp.perform(post("/factura").contentType(MediaType.APPLICATION_JSON).content(PETICION))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.numeroFactura").value("F-2026-0009"))
				.andExpect(jsonPath("$.subtotal").value(20)).andExpect(jsonPath("$.importeIva").value(4.2))
				.andExpect(jsonPath("$.total").value(24.2));
	}

	@Test
	void importesYNumeroManipuladosNuncaSeUsanComoAutoridad() throws Exception {
		String manipulada = PETICION.replace("\"idCliente\":", "\"numeroFactura\":\"MANIPULADO\",\"subtotal\":999,\"importeIva\":999,\"total\":999,\"idCliente\":")
				.replace("\"descripcion\":", "\"baseImponible\":999,\"importeIva\":999,\"total\":999,\"descripcion\":");
		var respuesta = clienteHttp.perform(post("/factura").contentType(MediaType.APPLICATION_JSON).content(manipulada)).andReturn().getResponse();
		// Rechazar campos desconocidos o ignorarlos es válido; utilizarlos para calcular no lo es.
		assertTrue(respuesta.getStatus() == 400 || respuesta.getStatus() == 201);
		if (respuesta.getStatus() == 201) {
			var captor = org.mockito.ArgumentCaptor.forClass(Factura.class);
			verify(repositorio).insertar(captor.capture());
			assertEquals("F-2026-0009", captor.getValue().numeroFactura());
			assertEquals(new java.math.BigDecimal("24.20"), captor.getValue().total());
		} else {
			verifyNoInteractions(repositorio);
		}
	}

	@Test
	void validaConceptosObligatoriosYCamposAnidados() throws Exception {
		clienteHttp.perform(post("/factura").contentType(MediaType.APPLICATION_JSON)
				.content("{\"idCliente\":1,\"fechaEmision\":\"2026-09-14\",\"estado\":\"BORRADOR\"}"))
				.andExpect(status().isBadRequest());
		clienteHttp.perform(post("/factura").contentType(MediaType.APPLICATION_JSON)
				.content(PETICION.replace("\"cantidad\":2", "\"cantidad\":0")))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(repositorio);
	}

	@Test
	void informaDelLimiteAnualConUnErrorClaro() throws Exception {
		when(repositorio.obtenerUltimoNumero(2026)).thenReturn(9999);
		clienteHttp.perform(post("/factura").contentType(MediaType.APPLICATION_JSON).content(PETICION))
				.andExpect(status().isConflict()).andExpect(content().string(org.hamcrest.Matchers.containsString("9999")));
	}

	@Test
	void noTruncaCantidadesFraccionarias() throws Exception {
		for (String cantidad : java.util.List.of("1.5", "\"1.5\"", "2147483648", "null")) {
			clienteHttp.perform(post("/factura").contentType(MediaType.APPLICATION_JSON)
					.content(PETICION.replace("\"cantidad\":2", "\"cantidad\":" + cantidad)))
					.andExpect(status().isBadRequest());
		}
		verifyNoInteractions(repositorio);
	}
}
