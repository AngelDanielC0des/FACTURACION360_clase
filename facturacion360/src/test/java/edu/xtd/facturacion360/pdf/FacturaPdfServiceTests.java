package edu.xtd.facturacion360.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import edu.xtd.facturacion360.dto.ClienteFactura;
import edu.xtd.facturacion360.dto.ConceptoFactura;
import edu.xtd.facturacion360.dto.DesgloseImpositivo;
import edu.xtd.facturacion360.dto.DetalleFactura;
import edu.xtd.facturacion360.dto.Emisor;
import edu.xtd.facturacion360.dto.Factura;
import edu.xtd.facturacion360.service.EmisorService;
import edu.xtd.facturacion360.service.FacturaService;

/**
 * Genera PDFs de verdad y los abre para ver qué ha salido.
 *
 * <p><strong>Por qué vale la pena.</strong> La plantilla y la hoja del PDF no
 * las compila nadie: un fragmento mal referenciado, una etiqueta sin cerrar o un
 * {@code <} suelto en un comentario del CSS no dan ningún error hasta que
 * alguien pide un PDF y se encuentra un 500. Durante el desarrollo pasaron las
 * tres cosas. Aquí se renderiza el documento entero en cada build.</p>
 *
 * <p>Levanta el contexto de Spring y dobla solo los dos servicios de los que se
 * lee la factura. Podria montarse un motor de plantillas a mano y ahorrarse el
 * arranque, pero seria OTRO motor: fuera de Spring, Thymeleaf evalua con OGNL y
 * dentro con SpEL. Un test que pasara con OGNL no diria nada sobre la plantilla
 * que se renderiza en produccion. No hace falta base de datos: el pool no se
 * conecta hasta que alguien le pide una conexion, y aqui nadie se la pide.</p>
 */
@SpringBootTest
class FacturaPdfServiceTests {

	@Autowired
	private FacturaPdfService servicio;

	@MockitoBean
	private FacturaService facturaService;

	@MockitoBean
	private EmisorService emisorService;

	@Test
	@DisplayName("Una factura de un concepto en A5 cabe en un folio")
	void unConceptoEnA5CabeEnUnFolio() throws Exception {
		darPorHechaUnaFactura("EMITIDA", 1);

		try (PDDocument pdf = abrir(servicio.generar(1, FormatoPapel.A5).contenido())) {
			assertEquals(1, pdf.getNumberOfPages(),
					"El A5 de un concepto tiene que caber en una pagina: es el criterio "
					+ "que se arreglo en el visor y el PDF no puede desdecirlo");
		}
	}

	@Test
	@DisplayName("Una factura larga se reparte en varias páginas")
	void unaFacturaLargaSeParte() throws Exception {
		darPorHechaUnaFactura("EMITIDA", 40);

		try (PDDocument pdf = abrir(servicio.generar(1, FormatoPapel.A5).contenido())) {
			assertTrue(pdf.getNumberOfPages() > 1,
					"40 conceptos no caben en un A5; si sale una sola pagina es que la "
					+ "tabla se esta desbordando fuera del papel");
		}
	}

	@Test
	@DisplayName("Los acentos y el euro salen en el documento sin embeber ninguna fuente")
	void losAcentosYElEuroSalen() throws Exception {
		darPorHechaUnaFactura("EMITIDA", 1);

		String texto = textoDe(servicio.generar(1, FormatoPapel.A4).contenido());

		// Van juntos en el mismo test porque comparten causa: si la fuente base
		// no cubriera el juego de caracteres, fallarian todos a la vez.
		assertTrue(texto.contains("Diseño de cañería"), "se han perdido la eñe o las tildes");
		assertTrue(texto.contains("Señores Muñoz"), "se ha perdido el nombre del cliente");
		assertTrue(texto.contains("€"), "se ha perdido el simbolo del euro");
	}

	@Test
	@DisplayName("Lo que viene del servidor se escapa: un nombre con HTML es texto, no marcado")
	void loQueVieneDelServidorSeEscapa() throws Exception {
		darPorHechaUnaFactura("EMITIDA", 1, "<img src=x onerror=alert(1)>");

		String texto = textoDe(servicio.generar(1, FormatoPapel.A4).contenido());

		assertTrue(texto.contains("<img src=x onerror=alert(1)>"),
				"El nombre del cliente tiene que verse tal cual, como texto");
	}

	@Test
	@DisplayName("Una factura emitida lleva su QR; un borrador ni lo lleva ni puede llevarlo")
	void soloLasEmitidasLlevanQr() throws Exception {
		darPorHechaUnaFactura("EMITIDA", 1);
		try (PDDocument pdf = abrir(servicio.generar(1, FormatoPapel.A4).contenido())) {
			assertEquals(1, cuantasImagenes(pdf), "falta el QR de una factura emitida");
		}

		// Un borrador no tiene registro de facturacion, asi que no tiene URL en
		// la sede de la AEAT: es la misma regla que el endpoint del PNG.
		darPorHechaUnaFactura("BORRADOR", 1);
		try (PDDocument pdf = abrir(servicio.generar(1, FormatoPapel.A4).contenido())) {
			assertEquals(0, cuantasImagenes(pdf), "un borrador no puede llevar QR");
			assertTrue(textoDe(servicio.generar(1, FormatoPapel.A4).contenido()).contains("BORRADOR"),
					"al borrador le falta su marca de agua");
		}
	}

	@Test
	@DisplayName("Sin emisor configurado el PDF sale igual, porque el QR necesita su NIF")
	void sinEmisorElPdfSaleSinQr() throws Exception {
		darPorHechaUnaFactura("EMITIDA", 1);
		when(emisorService.find()).thenReturn(null);

		try (PDDocument pdf = abrir(servicio.generar(1, FormatoPapel.A4).contenido())) {
			assertEquals(1, pdf.getNumberOfPages());
			assertEquals(0, cuantasImagenes(pdf));
		}
	}

	@Test
	@DisplayName("El nombre del fichero no puede partir la cabecera de descarga")
	void elNombreDelFicheroSeLimpia() {
		// El numero viene de la base de datos y acaba en un Content-Disposition:
		// unas comillas o un salto de linea permitirian partir la cabecera.
		assertEquals("F-2026-0001.pdf", FacturaPdfService.nombreDeFichero("F-2026-0001"));
		assertEquals("F_2026__0001_.pdf", FacturaPdfService.nombreDeFichero("F\"2026\r\n0001;"));
	}

	@Test
	@DisplayName("Los tres formatos se generan y ninguno sale vacío")
	void losTresFormatosSeGeneran() throws Exception {
		darPorHechaUnaFactura("EMITIDA", 1);

		for (FormatoPapel formato : FormatoPapel.values()) {
			FacturaPdfService.FacturaPdf pdf = servicio.generar(1, formato);
			assertNotNull(pdf.contenido());
			assertTrue(pdf.contenido().length > 1000, "el PDF de " + formato + " sale casi vacio");
			assertTrue(new String(pdf.contenido(), 0, 5).startsWith("%PDF"),
					"lo generado para " + formato + " no es un PDF");
		}
	}

	@Test
	@DisplayName("Si la factura no existe, el fallo llega tal cual desde el servicio")
	void siLaFacturaNoExisteElFalloSube() {
		when(facturaService.obtenerDetalle(99))
				.thenThrow(new IllegalStateException("no existe"));

		assertThrows(IllegalStateException.class,
				() -> servicio.generar(99, FormatoPapel.A4));
	}

	// ── Utilidades ──────────────────────────────────────────────────

	private void darPorHechaUnaFactura(String estado, int conceptos) {
		darPorHechaUnaFactura(estado, conceptos, "Señores Muñoz & Peña S.L.");
	}

	/** Deja preparado el detalle que devolvera el servicio doblado. */
	private void darPorHechaUnaFactura(String estado, int cuantosConceptos, String nombreCliente) {
		Factura factura = new Factura(1, 1, nombreCliente, "F-2026-0001",
				LocalDate.of(2026, 9, 20), estado, "Pago a 30 días.",
				new BigDecimal("1250.00"), new BigDecimal("262.50"), new BigDecimal("1512.50"));

		ClienteFactura cliente = new ClienteFactura(1, nombreCliente, "B12345674",
				"Avenida de la Constitución 148", "41004", "Sevilla", "Sevilla",
				"+34 612345678", "cliente@ejemplo.es");

		List<ConceptoFactura> conceptos = java.util.stream.IntStream.range(0, cuantosConceptos)
				.mapToObj(i -> new ConceptoFactura(i, "Diseño de cañería " + i, 1,
						new BigDecimal("1250.00"), BigDecimal.ZERO, new BigDecimal("21.00"),
						new BigDecimal("262.50"), new BigDecimal("1250.00"),
						new BigDecimal("1512.50"), "01", "S1"))
				.toList();

		List<DesgloseImpositivo> desglose = List.of(new DesgloseImpositivo("01", "01", "S1",
				new BigDecimal("21.00"), new BigDecimal("1250.00"), new BigDecimal("262.50")));

		when(facturaService.obtenerDetalle(1))
				.thenReturn(new DetalleFactura(factura, cliente, conceptos, desglose));
		when(emisorService.find()).thenReturn(new Emisor("FUNDACIÓN ONCE", "G78661923",
				"Sebastián Herrera 15", "emisor@ejemplo.es", "915068888", null));
	}

	private PDDocument abrir(byte[] pdf) throws Exception {
		return PDDocument.load(new ByteArrayInputStream(pdf));
	}

	private String textoDe(byte[] pdf) throws Exception {
		try (PDDocument documento = abrir(pdf)) {
			return new PDFTextStripper().getText(documento);
		}
	}

	/** Cuenta las imágenes incrustadas, que en este documento solo puede ser el QR. */
	private int cuantasImagenes(PDDocument pdf) throws Exception {
		int total = 0;
		for (PDPage pagina : pdf.getPages()) {
			for (var nombre : pagina.getResources().getXObjectNames()) {
				if (pagina.getResources().getXObject(nombre) instanceof PDImageXObject) {
					total++;
				}
			}
		}
		return total;
	}
}
