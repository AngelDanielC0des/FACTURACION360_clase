package edu.xtd.facturacion360.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import edu.xtd.facturacion360.pdf.FacturaPdfService;
import edu.xtd.facturacion360.pdf.FormatoPapel;

/**
 * El endpoint que sirve el PDF.
 *
 * <p>Aquí no se comprueba el documento —de eso se encarga el servicio— sino el
 * contrato HTTP: qué se devuelve, con qué cabeceras y qué pasa cuando lo que
 * llega en la URL no vale.</p>
 */
@WebMvcTest(FacturaPdfController.class)
class FacturaPdfControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private FacturaPdfService facturaPdfService;

	private static final String RUTA = "/factura/{idFactura}/pdf";

	/** Unos bytes cualesquiera que empiecen como empieza un PDF de verdad. */
	private static final byte[] PDF = "%PDF-1.4 contenido".getBytes(StandardCharsets.ISO_8859_1);

	@Test
	@DisplayName("Devuelve el PDF con su tipo y su nombre de descarga")
	void devuelveElPdfComoDescarga() throws Exception {
		when(facturaPdfService.generar(7, FormatoPapel.A4))
				.thenReturn(new FacturaPdfService.FacturaPdf("F-2026-0001.pdf", PDF));

		mockMvc.perform(get(RUTA, 7))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_PDF))
				.andExpect(header().string("Content-Disposition",
						"attachment; filename=\"F-2026-0001.pdf\""))
				.andExpect(content().bytes(PDF));
	}

	@Test
	@DisplayName("Sin formato en la URL se pide el A4")
	void sinFormatoSePideElA4() throws Exception {
		when(facturaPdfService.generar(anyInt(), any()))
				.thenReturn(new FacturaPdfService.FacturaPdf("F.pdf", PDF));

		mockMvc.perform(get(RUTA, 7)).andExpect(status().isOk());

		verify(facturaPdfService).generar(7, FormatoPapel.A4);
	}

	@ParameterizedTest
	@ValueSource(strings = { "A5", "a5" })
	@DisplayName("El formato de la URL llega al servicio tal cual lo pidió el visor")
	void elFormatoDeLaUrlLlegaAlServicio(String formato) throws Exception {
		when(facturaPdfService.generar(anyInt(), any()))
				.thenReturn(new FacturaPdfService.FacturaPdf("F.pdf", PDF));

		mockMvc.perform(get(RUTA, 7).param("formato", formato))
				.andExpect(status().isOk());

		verify(facturaPdfService).generar(7, FormatoPapel.A5);
	}

	@Test
	@DisplayName("Un formato inventado da 400 y no llega a generar nada")
	void formatoInventadoDa400() throws Exception {
		mockMvc.perform(get(RUTA, 7).param("formato", "A3"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(facturaPdfService);
	}

	@Test
	@DisplayName("El 404 de una factura que no existe sale del servicio y se respeta")
	void facturaInexistenteDa404() throws Exception {
		// El controlador no repite los guardianes: obtenerDetalle ya lanza este
		// 404, y duplicarlo aquí sería tener la misma regla escrita dos veces.
		when(facturaPdfService.generar(99, FormatoPapel.A4))
				.thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,
						"La factura 99 ya no existe"));

		mockMvc.perform(get(RUTA, 99)).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("Un identificador que no es un número da 404 y nunca llega a generar nada")
	void identificadorNoNumericoDa404() throws Exception {
		// 404 y no 400: con un segmento que no es un entero la ruta sencillamente
		// no existe, así que no hay ningún controlador al que llegar. Lo que
		// importa de esta prueba es la segunda línea: que no se intente generar
		// un PDF con una entrada que no se ha podido interpretar.
		mockMvc.perform(get("/factura/pepe/pdf")).andExpect(status().isNotFound());

		verifyNoInteractions(facturaPdfService);
	}
}
