package edu.xtd.facturacion360.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * La normalización que hace el mapper al construir el {@link Cliente} que se va a guardar.
 *
 * <p>Importa más de lo que parece para unos espacios: la collation del esquema es
 * <strong>NO PAD</strong>, así que para MySQL «Madrid» y «Madrid&nbsp;» son dos valores
 * distintos. Y los desplegables del filtro salen de un {@code SELECT DISTINCT}, de modo que
 * cada variante invisible se convierte en una entrada duplicada que el usuario ve.</p>
 *
 * @author AngelDanielC0des
 */
class ClienteMapperTests {

	private final ClienteMapper mapper = new ClienteMapper();

	private Cliente conLugar(String poblacion, String provincia) {
		return mapper.toDomain(new ClienteRequest("Ana Paz Gil", "12345678z", "Calle Mayor 15",
				"28001", poblacion, provincia, "612345678", "ana@ejemplo.es"));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"'  Madrid  '        | Madrid",
			"'Madrid '           | Madrid",
			"'San   Sebastian'   | San Sebastian",
			"'  Las   Rozas  '   | Las Rozas",
			"'Madrid'            | Madrid"
	})
	@DisplayName("los espacios sobrantes se quitan y los interiores se colapsan a uno")
	void colapsaLosEspacios(String entrada, String esperado) {
		assertEquals(esperado, conLugar(entrada, entrada).poblacion());
		assertEquals(esperado, conLugar(entrada, entrada).provincia());
	}

	/**
	 * El caso que se escapaba, y por el que esta clase existe.
	 *
	 * <p>La expresión estaba escrita {@code "\s+"} con <strong>una</strong> barra. Desde Java 15
	 * eso no es la clase de caracteres: es el escape de un espacio, así que la expresión real
	 * era {@code " +"} y los tabuladores pasaban intactos. Se compila sin una sola advertencia,
	 * funciona con los espacios que uno teclea al probar a mano, y deja el agujero abierto para
	 * el texto que se pega desde una hoja de cálculo o un documento.</p>
	 */
	@Test
	@DisplayName("el tabulador tambien se colapsa: es lo que fallaba con una sola barra")
	void colapsaTambienLosTabuladoresYSaltos() {
		assertEquals("San Sebastian de los Reyes",
				conLugar("San   Sebastian\tde los Reyes", "Madrid").poblacion());

		assertEquals("Santa Cruz", conLugar("Santa\tCruz", "Madrid").poblacion());
		assertEquals("Santa Cruz", conLugar("Santa\nCruz", "Madrid").poblacion());
		assertEquals("Madrid", conLugar("Madrid", "\tMadrid\t").provincia());
	}

	@Test
	@DisplayName("un lugar vacio o nulo no revienta y se queda como estaba")
	void toleraElNulo() {
		assertNull(conLugar(null, null).poblacion());
		assertNull(conLugar(null, null).provincia());
		assertEquals("", conLugar("   ", "   ").poblacion());
	}

	/**
	 * La provincia NO se sube a mayúsculas, y el documento SÍ. Son dos reglas distintas y las
	 * dos son deliberadas: un NIF se escribe en mayúsculas, un nombre de sitio no.
	 */
	@Test
	@DisplayName("el documento sube a mayusculas y el lugar conserva las suyas")
	void cadaCampoConSuRegla() {
		Cliente cliente = conLugar("las rozas", "madrid");

		assertEquals("12345678Z", cliente.nifCif());
		assertEquals("las rozas", cliente.poblacion());
		assertEquals("madrid", cliente.provincia());
	}

	/**
	 * El aviso que lleva escrito el método: el id queda a 0 y la fecha de alta a null porque el
	 * {@link ClienteRequest} no trae ninguno de los dos. Propagar ese 0 en una actualización
	 * lanzaría el {@code UPDATE} con {@code WHERE idcliente = 0}.
	 */
	@Test
	@DisplayName("el id queda a cero y la fecha de alta a null: las pone quien llama")
	void noInventaNiElIdNiLaFecha() {
		Cliente cliente = conLugar("Madrid", "Madrid");

		assertEquals(0, cliente.idCliente());
		assertNull(cliente.fechaAlta());
	}
}
