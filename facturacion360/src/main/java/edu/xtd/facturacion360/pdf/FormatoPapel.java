package edu.xtd.facturacion360.pdf;

import java.util.Locale;

/**
 * Los formatos de papel en los que se puede generar el PDF de una factura.
 *
 * <p>Son los mismos que ofrece el selector del visor y se identifican con el
 * mismo texto, para que el PDF salga en el formato que el usuario está viendo
 * en pantalla sin traducir nada por el camino. La lista vive en
 * {@code factura-imprimir.js}, en la constante {@code HOJAS}.</p>
 *
 * <p>El visor tiene además una opción «Por defecto (impresora)» que aquí no
 * existe: deja el {@code @page} sin {@code size} para que mande el papel
 * configurado en el diálogo de impresión. Un PDF no tiene diálogo ni impresora
 * detrás, así que tiene que decidir un tamaño, y el que decide es el A4.</p>
 */
public enum FormatoPapel {

	/** 210 × 297 mm. El formato por defecto. */
	A4("A4", false),

	/**
	 * 148 × 210 mm. Es el único «estrecho»: con 120 mm imprimibles la cabecera
	 * no cabe en una fila y el documento se compacta. Ver {@code factura-pdf.css}.
	 */
	A5("A5", true),

	/** 215,9 × 279,4 mm. Usa la misma maquetación que el A4. */
	CARTA("letter", false);

	/** El valor tal cual lo escribe la regla {@code @page { size: … }}. */
	private final String tamanoCss;

	/** Si el ancho obliga a la maquetación compacta. */
	private final boolean estrecha;

	FormatoPapel(String tamanoCss, boolean estrecha) {
		this.tamanoCss = tamanoCss;
		this.estrecha  = estrecha;
	}

	public String tamanoCss() {
		return tamanoCss;
	}

	public boolean esEstrecha() {
		return estrecha;
	}

	/**
	 * Traduce el valor que llega en la URL al formato correspondiente.
	 *
	 * <p>Acepta tanto el nombre de la constante ({@code CARTA}) como el valor
	 * del selector del visor ({@code letter}), y no distingue mayúsculas: la
	 * URL la escribe una persona tan a menudo como el propio visor.</p>
	 *
	 * @param valor el texto del parámetro, o {@code null} si no se indicó
	 * @return el formato pedido, o {@link #A4} si no se indicó ninguno
	 * @throws IllegalArgumentException si el valor no corresponde a ningún formato
	 */
	public static FormatoPapel desde(String valor) {
		if (valor == null || valor.isBlank()) {
			return A4;
		}

		String normalizado = valor.strip().toUpperCase(Locale.ROOT);
		for (FormatoPapel formato : values()) {
			if (formato.name().equals(normalizado)
					|| formato.tamanoCss.toUpperCase(Locale.ROOT).equals(normalizado)) {
				return formato;
			}
		}

		throw new IllegalArgumentException("Formato de papel no reconocido: " + valor);
	}
}
