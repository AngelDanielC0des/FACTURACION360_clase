package edu.xtd.facturacion360.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Vigila que la hoja del PDF y la del visor sigan diciendo lo mismo.
 *
 * <p><strong>Por qué existe este test.</strong> El visor imprime con el motor
 * del navegador y su maquetación está hecha de flexbox, grid y custom
 * properties. OpenHTMLtoPDF es CSS 2.1 y no entiende ninguna de las tres, así
 * que el PDF tiene forzosamente su propia hoja. Dos hojas separadas se
 * desincronizan: alguien afina un margen en el visor, nadie toca el PDF, y a
 * partir de ese día el documento que el cliente recibe deja de parecerse al que
 * se ve en pantalla. Nadie se entera hasta que un cliente lo dice.</p>
 *
 * <p>La convención que hace esto comprobable: en {@code factura-pdf.css} cada
 * medida que también existe en el visor lleva al lado un comentario
 * {@code @token --nombre}. Este test lee esas marcas, busca el token en
 * {@code factura-imprimir.css} y compara. Si no cuadran, falla nombrando el
 * token, el formato y los dos valores.</p>
 *
 * <p>Las unidades se normalizan a píxeles CSS porque OpenHTMLtoPDF interpreta
 * {@code px} a 96 dpi igual que el navegador. El visor escribe algunas medidas
 * en {@code rem}; el PDF no las admite, así que allí van en su equivalente
 * ({@code 1rem = 16px}) y la conversión la hace este test.</p>
 *
 * <p>Lo que este test <em>no</em> mira, a propósito: el tamaño del QR en A5.
 * Ahí hay una divergencia deliberada y documentada —el PDF usa 35 mm donde el
 * visor usa 30— porque en papel los 30 mm solo se decodifican con un lector
 * tolerante. Como no lleva marca {@code @token}, no entra en la comparación.</p>
 */
class FacturaPdfEstilosTests {

	private static final Path HOJA_VISOR =
			Path.of("src/main/resources/static/factura-imprimir.css");

	private static final Path HOJA_PDF =
			Path.of("src/main/resources/static/factura-pdf.css");

	/** Una medida con su valor ya normalizado a píxeles CSS. */
	private record Medida(String token, double px, String literal) {
	}

	/**
	 * Selector con el que el visor declara los tokens del A5.
	 *
	 * <p>El A4 los declara en {@code .documento-factura} y el A5 los redefine en
	 * {@code .documento-factura.hoja-estrecha}. En el PDF el equivalente es
	 * {@code body} y {@code body.a5}.</p>
	 */
	private static final String BLOQUE_A5_VISOR = ".documento-factura.hoja-estrecha";

	private static final String BLOQUE_A4_VISOR = ".documento-factura";

	@Test
	@DisplayName("Los tokens del A4 valen lo mismo en la hoja del visor y en la del PDF")
	void tokensDelA4Coinciden() throws IOException {
		comprobarFormato("A4", false);
	}

	@Test
	@DisplayName("Los tokens del A5 valen lo mismo en la hoja del visor y en la del PDF")
	void tokensDelA5Coinciden() throws IOException {
		comprobarFormato("A5", true);
	}

	@Test
	@DisplayName("La hoja del PDF no usa nada que su motor CSS 2.1 no entienda")
	void laHojaDelPdfNoUsaCssModerno() throws IOException {
		String pdf = sinComentarios(Files.readString(HOJA_PDF, StandardCharsets.UTF_8));

		// Estas tres son justo las que obligan a que el PDF tenga hoja propia.
		// Si alguien las cuela aquí, no fallará nada: simplemente se ignoran y el
		// documento sale descuadrado, que es mucho peor que un error.
		assertFalse(pdf.contains("display:flex") || pdf.contains("display: flex"),
				"factura-pdf.css usa flexbox, que OpenHTMLtoPDF ignora en silencio");
		assertFalse(pdf.contains("display:grid") || pdf.contains("display: grid"),
				"factura-pdf.css usa grid, que OpenHTMLtoPDF ignora en silencio");
		assertFalse(pdf.contains("var(--"),
				"factura-pdf.css usa custom properties, que OpenHTMLtoPDF no resuelve");
	}

	@Test
	@DisplayName("La hoja del PDF no contiene caracteres que rompan el parser XML")
	void laHojaDelPdfNoRompeElParser() throws IOException {
		String pdf = Files.readString(HOJA_PDF, StandardCharsets.UTF_8);

		// El contenido de un <style> lo lee un parser XML, para el que un '<'
		// abre una etiqueta aunque esté dentro de un comentario CSS. Ya pasó una
		// vez. El servicio lo envuelve en CDATA, pero esto lo deja dicho.
		assertFalse(pdf.contains("<"),
				"factura-pdf.css contiene un '<': dentro de un <style> el parser XML "
				+ "lo lee como el principio de una etiqueta");
	}

	/** Compara todos los tokens marcados de un formato. */
	private void comprobarFormato(String formato, boolean estrecha) throws IOException {
		String visor = Files.readString(HOJA_VISOR, StandardCharsets.UTF_8);
		String pdf   = Files.readString(HOJA_PDF, StandardCharsets.UTF_8);

		Map<String, Medida> delVisor = tokensDelVisor(visor, estrecha);
		List<Medida> delPdf = medidasMarcadasDelPdf(pdf, estrecha);

		assertFalse(delPdf.isEmpty(),
				"No hay ninguna marca «@token» en la sección " + formato + " de factura-pdf.css: "
				+ "sin marcas este test no comprueba nada y la protección es falsa");

		for (Medida enElPdf : delPdf) {
			Medida enElVisor = delVisor.get(enElPdf.token());
			assertTrue(enElVisor != null,
					"factura-pdf.css marca «" + enElPdf.token() + "» en " + formato
					+ ", pero ese token no existe en factura-imprimir.css");

			assertEquals(enElVisor.px(), enElPdf.px(), 0.01,
					"El token " + enElPdf.token() + " ha divergido en " + formato
					+ ": el visor dice " + enElVisor.literal()
					+ " y el PDF dice " + enElPdf.literal()
					+ ". Cambia los dos, o documenta la diferencia quitando la marca @token.");
		}
	}

	/**
	 * Lee los tokens de la hoja del visor.
	 *
	 * @param estrecha si se quieren los del A5; si no, los del A4
	 */
	private Map<String, Medida> tokensDelVisor(String css, boolean estrecha) {
		String bloque = estrecha
				? bloqueDe(css, BLOQUE_A5_VISOR)
				: bloqueDe(css, BLOQUE_A4_VISOR);

		Map<String, Medida> tokens = new LinkedHashMap<>();

		// En A5 solo se redefine lo que cambia: el resto se hereda del A4.
		if (estrecha) {
			tokens.putAll(tokensDelVisor(css, false));
		}

		Matcher m = Pattern.compile("(--[a-z-]+)\\s*:\\s*([^;]+);").matcher(bloque);
		while (m.find()) {
			String valor = m.group(2).trim();
			Double px = aPixeles(valor);
			if (px != null) {
				tokens.put(m.group(1), new Medida(m.group(1), px, valor));
			}
		}
		return tokens;
	}

	/**
	 * Extrae el primer bloque de reglas de un selector.
	 *
	 * <p>Se busca el selector seguido de «{» al principio de línea para no
	 * confundirlo con una mención dentro de un comentario.</p>
	 */
	private String bloqueDe(String css, String selector) {
		Matcher m = Pattern.compile("(?m)^\\Q" + selector + "\\E\\s*\\{([^}]*)\\}").matcher(css);
		assertTrue(m.find(), "No se encuentra el bloque «" + selector + "» en factura-imprimir.css");
		return m.group(1);
	}

	/**
	 * Lee del PDF las declaraciones marcadas con «@token», separando las de A5
	 * (que van bajo selectores {@code body.a5 …}) de las del A4.
	 */
	private List<Medida> medidasMarcadasDelPdf(String css, boolean estrecha) {
		List<Medida> medidas = new ArrayList<>();

		// propiedad: valor;   /* @token --nombre */
		//
		// Se captura la declaración ENTERA y no solo el número pegado al
		// comentario: en una abreviada como «padding: 7.2px 0» el valor que
		// interesa es el primero, y quedarse con el último comparaba el «0».
		Matcher m = Pattern.compile(
				":\\s*([^;{}]+);\\s*/\\*\\s*@token\\s+(--[a-z-]+)\\s*\\*/")
				.matcher(css);

		while (m.find()) {
			boolean esDeA5 = css.lastIndexOf("body.a5", m.start()) > css.lastIndexOf("\n}", m.start());
			if (esDeA5 != estrecha) {
				continue;
			}

			String primerValor = m.group(1).trim().split("\\s+")[0];
			Double px = aPixeles(primerValor);
			if (px != null) {
				medidas.add(new Medida(m.group(2), px, primerValor));
			}
		}
		return medidas;
	}

	/**
	 * Pasa una medida CSS a píxeles, que es la unidad común de comparación.
	 *
	 * @return el valor en píxeles, o {@code null} si no es una medida (un color,
	 *         una palabra clave…)
	 */
	private Double aPixeles(String valor) {
		Matcher m = Pattern.compile("^([\\d.]+)(px|pt|mm|rem)?$").matcher(valor.trim());
		if (!m.matches()) {
			return null;
		}

		double numero = Double.parseDouble(m.group(1));
		String unidad = m.group(2);

		if (unidad == null) {
			return numero;              // sin unidad: line-height y similares
		}
		return switch (unidad) {
			case "px"  -> numero;
			case "pt"  -> numero * 96.0 / 72.0;
			case "mm"  -> numero * 96.0 / 25.4;
			case "rem" -> numero * 16.0;
			default    -> null;
		};
	}

	/** Quita los comentarios, para no buscar reglas dentro de la prosa. */
	private String sinComentarios(String css) {
		return css.replaceAll("(?s)/\\*.*?\\*/", "");
	}
}
