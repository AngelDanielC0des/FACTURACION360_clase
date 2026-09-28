package edu.xtd.facturacion360.pdf;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Da formato a los importes, porcentajes y fechas del PDF.
 *
 * <p>Es el espejo en Java de las tres funciones que ya hace el visor en
 * {@code factura-imprimir.js} ({@code formatearImporte}, {@code formatearPorcentaje}
 * y {@code formatearFecha}). Están duplicadas porque el PDF se compone en el
 * servidor y no puede llamar al JavaScript de la pantalla, pero producen lo
 * mismo: si un total se lee «1.512,50 €» en el visor, se lee igual en el PDF.</p>
 *
 * <p>Vive aquí y no en la plantilla porque Thymeleaf formatea con la
 * configuración regional de la petición, y una factura española no debe
 * cambiar de aspecto porque el navegador del usuario esté en inglés.</p>
 *
 * <p><strong>No es seguro entre hilos</strong>: los {@code NumberFormat} que
 * envuelve no lo son. Hay que crear uno por documento, que es lo que hace
 * {@link FacturaPdfService}.</p>
 */
public class FormateadorPdf {

	/** El texto que se pinta donde no hay dato, igual que en el visor. */
	private static final String SIN_DATO = "—";

	private static final Locale ESPANA = Locale.forLanguageTag("es-ES");

	private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private final NumberFormat moneda;
	private final NumberFormat numero;

	public FormateadorPdf() {
		this.moneda = NumberFormat.getCurrencyInstance(ESPANA);
		this.numero = NumberFormat.getNumberInstance(ESPANA);
	}

	/**
	 * Un importe en euros. {@code null} cuenta como cero, igual que en el visor.
	 *
	 * @param importe la cantidad, o {@code null}
	 * @return por ejemplo {@code 1.512,50 €}
	 */
	public String importe(BigDecimal importe) {
		return moneda.format(importe == null ? BigDecimal.ZERO : importe);
	}

	/**
	 * Un tipo impositivo o un descuento.
	 *
	 * @param porcentaje el valor, o {@code null}
	 * @return por ejemplo {@code 21 %}
	 */
	public String porcentaje(BigDecimal porcentaje) {
		return numero.format(porcentaje == null ? BigDecimal.ZERO : porcentaje) + " %";
	}

	/**
	 * Una fecha en el formato que se lee en España.
	 *
	 * @param fecha la fecha, o {@code null}
	 * @return por ejemplo {@code 20/09/2026}
	 */
	public String fecha(LocalDate fecha) {
		return fecha == null ? SIN_DATO : fecha.format(FECHA);
	}

	/**
	 * Un texto que puede faltar.
	 *
	 * @param texto el valor, que puede ser {@code null} o estar en blanco
	 * @return el propio texto, o la raya que usa el visor cuando no hay nada
	 */
	public String oRaya(Object texto) {
		if (texto == null) {
			return SIN_DATO;
		}
		String valor = texto.toString().strip();
		return valor.isEmpty() ? SIN_DATO : valor;
	}
}
