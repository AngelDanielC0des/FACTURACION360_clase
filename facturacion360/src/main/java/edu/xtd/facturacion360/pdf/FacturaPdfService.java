package edu.xtd.facturacion360.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import edu.xtd.facturacion360.dto.DetalleFactura;
import edu.xtd.facturacion360.dto.Emisor;
import edu.xtd.facturacion360.service.EmisorService;
import edu.xtd.facturacion360.service.FacturaService;
import edu.xtd.facturacion360.verifactu.qr.GeneradorQr;

/**
 * Genera el PDF de una factura para poder compartirla.
 *
 * <p><strong>Por qué el PDF no reutiliza la hoja del visor.</strong> El visor
 * imprime con el motor del navegador, y su maquetación está construida sobre
 * flexbox, grid y custom properties. El motor de OpenHTMLtoPDF es CSS 2.1 y no
 * entiende ninguna de las tres cosas, así que el PDF necesita plantilla
 * ({@code templates/factura-pdf.html}) y hoja ({@code static/factura-pdf.css})
 * propias, maquetadas con tablas.</p>
 *
 * <p>Que las dos hojas digan lo mismo no se deja a la disciplina: lo comprueba
 * {@code FacturaPdfEstilosTests}, que compara los tokens de densidad del visor
 * con los literales del PDF y falla nombrando el que haya divergido.</p>
 *
 * <p>El QR no se pide por HTTP al endpoint que ya existe: se genera aquí con el
 * mismo {@link GeneradorQr} y se incrusta como {@code data:} en la plantilla.
 * Así el PDF no depende de que el servidor pueda llamarse a sí mismo.</p>
 */
@Service
public class FacturaPdfService {

	private static final Logger log = LoggerFactory.getLogger(FacturaPdfService.class);

	/** El margen de página, el mismo que usa el visor en su regla {@code @page}. */
	private static final String MARGEN_PAGINA = "14mm";

	/** Dónde vive la hoja de estilos del PDF dentro del jar. */
	private static final String RUTA_CSS = "static/factura-pdf.css";

	/** Nombre de la plantilla, que Thymeleaf resuelve a {@code templates/factura-pdf.html}. */
	private static final String PLANTILLA = "factura-pdf";

	private final FacturaService  facturaService;
	private final EmisorService   emisorService;
	private final GeneradorQr     generadorQr;
	private final ITemplateEngine motorPlantillas;

	public FacturaPdfService(FacturaService facturaService,
	                         EmisorService emisorService,
	                         GeneradorQr generadorQr,
	                         ITemplateEngine motorPlantillas) {
		this.facturaService  = facturaService;
		this.emisorService   = emisorService;
		this.generadorQr     = generadorQr;
		this.motorPlantillas = motorPlantillas;
	}

	/**
	 * Un PDF recién generado y el nombre con el que debe descargarse.
	 *
	 * <p>Van juntos porque el nombre sale del número de factura, que solo se
	 * conoce después de cargar el detalle: devolver únicamente los bytes
	 * obligaría a quien llama a consultar la factura una segunda vez.</p>
	 *
	 * @param nombreFichero nombre seguro para la cabecera de descarga
	 * @param contenido     los bytes del documento
	 */
	public record FacturaPdf(String nombreFichero, byte[] contenido) {
	}

	/**
	 * Construye el PDF de una factura en el formato de papel indicado.
	 *
	 * <p>La existencia de la factura no se comprueba aquí: de eso ya se encarga
	 * {@link FacturaService#obtenerDetalle(int)}, que lanza 400 si el
	 * identificador no es válido y 404 si la factura no está.</p>
	 *
	 * @param idFactura identificador de la factura
	 * @param formato   el papel en el que se maqueta
	 * @return el documento y su nombre de fichero
	 * @throws IOException si falla el render o la escritura en memoria
	 */
	public FacturaPdf generar(int idFactura, FormatoPapel formato) throws IOException {
		DetalleFactura detalle = facturaService.obtenerDetalle(idFactura);
		Emisor emisor = emisorService.find();

		String html = componerHtml(detalle, emisor, formato);

		ByteArrayOutputStream salida = new ByteArrayOutputStream();
		PdfRendererBuilder constructor = new PdfRendererBuilder();
		constructor.useFastMode();
		constructor.withHtmlContent(html, null);
		constructor.toStream(salida);
		constructor.run();

		byte[] pdf = salida.toByteArray();
		log.debug("PDF generado para la factura {} en {} ({} bytes)",
				detalle.factura().numeroFactura(), formato, pdf.length);

		return new FacturaPdf(nombreDeFichero(detalle.factura().numeroFactura()), pdf);
	}

	/**
	 * El nombre con el que se descarga el fichero.
	 *
	 * <p>Se limpia de todo lo que no sea letra, dígito, guion o punto: el número
	 * de factura viene de la base de datos y acaba dentro de una cabecera
	 * {@code Content-Disposition}, donde unas comillas o un salto de línea
	 * permitirían partir la cabecera en dos.</p>
	 *
	 * @param numeroFactura el número tal cual está guardado
	 * @return un nombre de fichero seguro, por ejemplo {@code F-2026-0001.pdf}
	 */
	public static String nombreDeFichero(String numeroFactura) {
		return numeroFactura.replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf";
	}

	/** Rellena la plantilla con los datos de la factura. */
	private String componerHtml(DetalleFactura detalle, Emisor emisor, FormatoPapel formato)
			throws IOException {

		boolean esBorrador = "BORRADOR".equals(detalle.factura().estado());

		Context contexto = new Context();
		contexto.setVariable("factura",    detalle.factura());
		contexto.setVariable("cliente",    detalle.cliente());
		contexto.setVariable("conceptos",  detalle.conceptos());
		contexto.setVariable("desglose",   detalle.desglose());
		contexto.setVariable("emisor",     emisor);
		contexto.setVariable("esBorrador", esBorrador);
		contexto.setVariable("estrecha",   formato.esEstrecha());
		contexto.setVariable("css",        enCdata(leerCss()));
		contexto.setVariable("reglaPagina", enCdata(reglaPagina(formato)));
		contexto.setVariable("logo",       datosDelLogo(emisor));
		contexto.setVariable("qr",         datosDelQr(detalle, emisor, esBorrador));
		// Uno por documento: NumberFormat no es seguro entre hilos y este
		// servicio es un singleton al que pueden entrar dos peticiones a la vez.
		contexto.setVariable("fmt",        new FormateadorPdf());

		return motorPlantillas.process(PLANTILLA, contexto);
	}

	/**
	 * La regla {@code @page} con el tamaño elegido.
	 *
	 * <p>Va aparte de la hoja de estilos, igual que en el visor: allí también es
	 * un bloque {@code <style>} que el selector de formato reescribe
	 * ({@code actualizarFormatoPapel()} en {@code factura-imprimir.js}).</p>
	 */
	private String reglaPagina(FormatoPapel formato) {
		return "@page { size: " + formato.tamanoCss() + "; margin: " + MARGEN_PAGINA + "; }";
	}

	/**
	 * Envuelve una hoja de estilos para que pueda ir dentro de un {@code <style>}.
	 *
	 * <p>OpenHTMLtoPDF parsea el documento con un parser XML, y para XML el
	 * contenido de {@code <style>} es marcado, no texto: un solo {@code <} en un
	 * comentario del CSS abre una etiqueta y rompe el documento entero. Ya pasó
	 * una vez, con un comentario que mencionaba la propia etiqueta, y el error
	 * que da el parser —«el elemento style no está cerrado»— no señala la línea
	 * culpable, así que cuesta encontrarlo.</p>
	 *
	 * <p>Los delimitadores CDATA van dentro de comentarios CSS ({@code /*…*}{@code /})
	 * para que la hoja siga siendo CSS válido: es el modismo de toda la vida de
	 * XHTML. Así nadie tiene que acordarse de esta regla al escribir estilos.</p>
	 */
	private String enCdata(String css) {
		return "/*<![CDATA[*/\n" + css + "\n/*]]>*/";
	}

	/**
	 * Lee la hoja de estilos del PDF.
	 *
	 * <p>Se lee en cada llamada y no se guarda en memoria a propósito: generar
	 * un PDF no es una operación frecuente, el fichero son unos pocos kilobytes,
	 * y así un cambio en la hoja se ve sin reiniciar la aplicación.</p>
	 */
	private String leerCss() throws IOException {
		try (var flujo = new ClassPathResource(RUTA_CSS).getInputStream()) {
			return new String(flujo.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * El logo del emisor como {@code data:}, o {@code null} si no hay ninguno.
	 *
	 * <p>Se incrusta en vez de enlazarse porque el renderizador no tiene sesión
	 * ni sabe en qué URL está desplegada la aplicación.</p>
	 */
	private String datosDelLogo(Emisor emisor) {
		if (emisor == null || emisor.logo() == null || emisor.logo().length == 0) {
			return null;
		}
		return "data:image/png;base64," + Base64.getEncoder().encodeToString(emisor.logo());
	}

	/**
	 * El QR como {@code data:}, o {@code null} si esta factura no lleva.
	 *
	 * <p>Un borrador no tiene registro de facturación y por tanto no tiene URL en
	 * la sede de la AEAT: es la misma regla que aplica el visor en
	 * {@code mostrarQr()} y la que hace que el endpoint del PNG responda 404.
	 * Sin emisor configurado tampoco hay QR, porque su NIF forma parte de la
	 * URL que se codifica.</p>
	 *
	 * <p>Un fallo generando el QR no tumba el PDF: se registra y la factura sale
	 * sin él. Preferimos un documento incompleto a ninguno, porque quien lo pide
	 * casi siempre lo quiere para mirarlo, no para presentarlo.</p>
	 */
	private String datosDelQr(DetalleFactura detalle, Emisor emisor, boolean esBorrador) {
		if (esBorrador || emisor == null || emisor.cif() == null) {
			return null;
		}

		try {
			String url = generadorQr.construirUrl(detalle.factura(), emisor.cif());
			return "data:image/png;base64,"
					+ Base64.getEncoder().encodeToString(generadorQr.generarPng(url));
		} catch (Exception error) {
			log.warn("No se pudo generar el QR de la factura {}; el PDF sale sin él",
					detalle.factura().numeroFactura(), error);
			return null;
		}
	}
}
