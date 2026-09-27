package edu.xtd.facturacion360.dto;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * El mismo dato descrito en tres sitios tiene que decir lo mismo en los tres.
 *
 * <p>Un cliente atraviesa <strong>el formulario</strong> ({@code clientes.html}), <strong>el
 * servidor</strong> ({@link ClienteRequest}) y <strong>la columna</strong>
 * ({@code backupFacturacion360v3.sql}). Cada capa lleva su propio límite escrito a mano, y nada
 * obliga a que coincidan: se escriben en ficheros distintos, en lenguajes distintos y las tocan
 * personas distintas.</p>
 *
 * <p>Cuando dejan de coincidir no salta ningún error. Lo que pasa es peor: el usuario teclea y
 * el campo <strong>deja de admitir letras sin decir por qué</strong> —si el formulario es el más
 * corto—, o rellena un campo que el servidor rechaza después con un 400 —si es el más largo—.
 * Esta clase convierte los tres sitios en uno solo comparándolos.</p>
 *
 * <p>Encontró de una pasada que la dirección aceptaba 90 en la columna y en el servidor y solo
 * 60 en el formulario: treinta caracteres que no se podían escribir y nadie había notado.</p>
 *
 * @author AngelDanielC0des
 */
class ContratoClienteTests {

	private static final Path RAIZ = Path.of("src", "main", "resources");
	private static final Path FORMULARIO = RAIZ.resolve(Path.of("static", "clientes.html"));
	private static final Path ESQUEMA = RAIZ.resolve(Path.of("docu", "backupFacturacion360v3.sql"));

	/** Los campos del formulario se llaman igual que los componentes del record salvo estos. */
	private static final Map<String, String> COLUMNA_DE = Map.of(
			"nifCif", "nif_cif",
			"codigoPostal", "codigopostal");

	// ── Lo que dice cada capa ────────────────────────────────────────────────────────────

	/** Un {@code <input>} de la plantilla, con lo que el navegador va a exigir. */
	private record Campo(String nombre, Integer maxlength, boolean required, boolean tienePatron) {
	}

	private static Map<String, Campo> leerFormulario() throws IOException {
		String html = Files.readString(FORMULARIO, StandardCharsets.UTF_8);
		Map<String, Campo> campos = new LinkedHashMap<>();

		Matcher entrada = Pattern.compile("<input\\b[^>]*>", Pattern.DOTALL).matcher(html);
		while (entrada.find()) {
			String etiqueta = entrada.group();
			String nombre = atributo(etiqueta, "name");
			// Solo la plantilla de edicion: los demas <input> son buscadores y filtros
			if (nombre == null || !etiqueta.contains("-PLANTILLA")) {
				continue;
			}
			String max = atributo(etiqueta, "maxlength");
			campos.putIfAbsent(nombre, new Campo(nombre,
					max == null ? null : Integer.valueOf(max),
					etiqueta.contains("required"),
					etiqueta.contains("pattern=")));
		}
		return campos;
	}

	private static String atributo(String etiqueta, String nombre) {
		Matcher m = Pattern.compile(nombre + "\\s*=\\s*\"([^\"]*)\"").matcher(etiqueta);
		return m.find() ? m.group(1) : null;
	}

	/**
	 * La anotación de un componente de record, buscada donde de verdad acaba.
	 *
	 * <p>Esto no es una comodidad: es el motivo de que la primera versión de esta clase pasara
	 * sin comprobar nada. {@code RecordComponent.getAnnotation()} solo devuelve lo que el
	 * {@code @Target} de la anotación permita poner en un {@code RECORD_COMPONENT}, y ni
	 * {@link Size} ni {@link NotBlank} lo incluyen — sus destinos son {@code METHOD},
	 * {@code FIELD}, {@code PARAMETER} y poco más. Escritas sobre el componente, el compilador
	 * las reparte al <strong>campo</strong>, al <strong>accesor</strong> y al
	 * <strong>parámetro del constructor</strong>, pero <em>no</em> al componente.</p>
	 *
	 * <p>Resultado: la comprobación encontraba {@code null} en todos los campos, se los saltaba
	 * con su {@code continue} y terminaba en verde sin haber mirado uno solo. Una prueba que
	 * pasa porque no inspecciona nada es peor que no tenerla, porque además tranquiliza.</p>
	 */
	private static <A extends java.lang.annotation.Annotation> A anotacionDe(
			RecordComponent componente, Class<A> tipo) {

		A enElAccesor = componente.getAccessor().getAnnotation(tipo);
		if (enElAccesor != null) {
			return enElAccesor;
		}
		try {
			return componente.getDeclaringRecord()
					.getDeclaredField(componente.getName()).getAnnotation(tipo);
		} catch (NoSuchFieldException imposible) {
			throw new AssertionError("un record siempre tiene el campo de su componente", imposible);
		}
	}

	/** El ancho del {@code varchar(n)} de cada columna de la tabla clientes. */
	private static Map<String, Integer> leerEsquema() throws IOException {
		String sql = Files.readString(ESQUEMA, StandardCharsets.UTF_8);
		int desde = sql.indexOf("CREATE TABLE `clientes`");
		int hasta = sql.indexOf("ENGINE=", desde);
		assertTrue(desde >= 0 && hasta > desde, "no se encuentra la tabla clientes en " + ESQUEMA);

		Map<String, Integer> anchos = new LinkedHashMap<>();
		Matcher m = Pattern.compile("`(\\w+)`\\s+varchar\\((\\d+)\\)").matcher(sql.substring(desde, hasta));
		while (m.find()) {
			anchos.put(m.group(1), Integer.valueOf(m.group(2)));
		}
		return anchos;
	}

	// ── Las tres comprobaciones ──────────────────────────────────────────────────────────

	@Test
	@DisplayName("el maxlength del formulario coincide con el @Size(max) del servidor")
	void formularioYServidorDicenLoMismo() throws IOException {
		Map<String, Campo> formulario = leerFormulario();
		List<String> fallos = new ArrayList<>();

		for (RecordComponent componente : ClienteRequest.class.getRecordComponents()) {
			Size size = anotacionDe(componente, Size.class);
			Campo campo = formulario.get(componente.getName());
			if (size == null || campo == null || campo.maxlength() == null) {
				continue;
			}
			// Un patron mas estricto que el @Size es legitimo: el CP admite 6 en el servidor
			// y el patron obliga a 5, que es lo correcto para un CP espanol.
			if (campo.tienePatron() && campo.maxlength() <= size.max()) {
				continue;
			}
			if (campo.maxlength() != size.max()) {
				fallos.add(String.format("%s: formulario maxlength=%d, servidor @Size(max=%d)",
						componente.getName(), campo.maxlength(), size.max()));
			}
		}
		assertTrue(fallos.isEmpty(), () -> "El formulario y el servidor no dicen lo mismo:\n  "
				+ String.join("\n  ", fallos));
	}

	@Test
	@DisplayName("ningun @Size(max) del servidor supera el ancho de su columna")
	void elServidorCabeEnLaTabla() throws IOException {
		Map<String, Integer> columnas = leerEsquema();
		List<String> fallos = new ArrayList<>();

		for (RecordComponent componente : ClienteRequest.class.getRecordComponents()) {
			Size size = anotacionDe(componente, Size.class);
			if (size == null) {
				continue;
			}
			String columna = COLUMNA_DE.getOrDefault(componente.getName(), componente.getName());
			Integer ancho = columnas.get(columna);
			if (ancho == null) {
				fallos.add(componente.getName() + ": no hay columna `" + columna + "` en el esquema");
			} else if (size.max() > ancho) {
				fallos.add(String.format("%s: @Size(max=%d) NO CABE en varchar(%d) — al guardar"
						+ " se trunca o revienta", componente.getName(), size.max(), ancho));
			}
		}
		assertTrue(fallos.isEmpty(), () -> "Hay datos que no caben en su columna:\n  "
				+ String.join("\n  ", fallos));
	}

	@Test
	@DisplayName("lo obligatorio en el formulario lo es tambien en el servidor")
	void loObligatorioLoEsEnLosDosLados() throws IOException {
		Map<String, Campo> formulario = leerFormulario();
		List<String> fallos = new ArrayList<>();

		for (RecordComponent componente : ClienteRequest.class.getRecordComponents()) {
			Campo campo = formulario.get(componente.getName());
			if (campo == null) {
				continue;
			}
			boolean servidor = anotacionDe(componente, NotBlank.class) != null;
			if (campo.required() && !servidor) {
				fallos.add(componente.getName() + ": el formulario lo exige y el servidor NO."
						+ " Cualquiera lo salta con curl");
			}
			if (!campo.required() && servidor) {
				fallos.add(componente.getName() + ": el servidor lo exige y el formulario NO."
						+ " El usuario envia y recibe un 400 sin saber por que");
			}
		}
		assertTrue(fallos.isEmpty(), () -> "Obligatoriedad descuadrada:\n  "
				+ String.join("\n  ", fallos));
	}
}
