package edu.xtd.facturacion360.controller;

import java.io.IOException;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import edu.xtd.facturacion360.pdf.FacturaPdfService;
import edu.xtd.facturacion360.pdf.FormatoPapel;

/**
 * Sirve la factura como PDF para poder compartirla.
 *
 * <p>Va en un controlador propio y no dentro de {@link FacturaController} por lo
 * mismo que el QR tiene el suyo: es una representación del documento, no una
 * operación sobre facturas, y arrastra sus propias dependencias de render.</p>
 *
 * <p>El visor sigue imprimiendo con {@code window.print()}. Este endpoint existe
 * porque compartir necesita un <em>fichero</em>: ni {@code mailto:}, ni
 * {@code wa.me}, ni {@code t.me} pueden adjuntar nada, y el único camino que
 * entrega un PDF de verdad es {@code navigator.share({files})}, que necesita
 * los bytes.</p>
 */
@RestController
@RequestMapping("/factura")
public class FacturaPdfController {

	private final FacturaPdfService facturaPdfService;

	public FacturaPdfController(FacturaPdfService facturaPdfService) {
		this.facturaPdfService = facturaPdfService;
	}

	/**
	 * Devuelve el PDF de una factura.
	 *
	 * <p>No repite los guardianes de identificador y existencia: ya los aplica
	 * {@code FacturaService.obtenerDetalle(int)}, que responde 400 si el
	 * identificador no es mayor que cero y 404 si la factura no está.</p>
	 *
	 * <p>A diferencia del QR, esto <strong>sí</strong> se genera para borradores:
	 * son útiles para enseñar una propuesta antes de emitirla. El documento sale
	 * con su marca de agua y sin QR, y quien lo comparte añade además el aviso
	 * de que no es una factura válida.</p>
	 *
	 * <p>Sin {@code Cache-Control}: un borrador cambia cada vez que se edita, y
	 * servir el de ayer sería peor que tardar un segundo en rehacerlo.</p>
	 *
	 * @param idFactura identificador de la factura
	 * @param formato   {@code A4} (por defecto), {@code A5} o {@code letter}
	 * @return 200 con el PDF, o 400/404 con un {@code ProblemDetail}
	 * @throws IOException si falla el render del documento
	 */
	@GetMapping(value = "/{idFactura}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
	public ResponseEntity<byte[]> obtenerPdf(
			@PathVariable int idFactura,
			@RequestParam(required = false) String formato) throws IOException {

		FormatoPapel papel;
		try {
			papel = FormatoPapel.desde(formato);
		} catch (IllegalArgumentException error) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
		}

		FacturaPdfService.FacturaPdf pdf = facturaPdfService.generar(idFactura, papel);

		HttpHeaders cabeceras = new HttpHeaders();
		cabeceras.setContentType(MediaType.APPLICATION_PDF);
		// ContentDisposition escapa el nombre por nosotros; aun así el servicio ya
		// lo ha limpiado, porque el número de factura viene de la base de datos.
		cabeceras.setContentDisposition(ContentDisposition.attachment()
				.filename(pdf.nombreFichero())
				.build());

		return new ResponseEntity<>(pdf.contenido(), cabeceras, HttpStatus.OK);
	}
}
