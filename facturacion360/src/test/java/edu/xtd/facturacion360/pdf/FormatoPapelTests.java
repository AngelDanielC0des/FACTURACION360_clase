package edu.xtd.facturacion360.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * El formato de papel es lo único que llega por la URL, así que es lo único que
 * puede escribir cualquiera a mano. Estas pruebas fijan qué acepta y qué no.
 */
class FormatoPapelTests {

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   " })
	@DisplayName("Sin formato se usa A4: un PDF no tiene diálogo de impresión donde elegirlo")
	void sinFormatoSeUsaA4(String valor) {
		assertEquals(FormatoPapel.A4, FormatoPapel.desde(valor));
	}

	@ParameterizedTest
	@ValueSource(strings = { "A5", "a5", "  A5  " })
	@DisplayName("El nombre del formato no distingue mayúsculas ni espacios de más")
	void aceptaElNombreDelFormato(String valor) {
		assertEquals(FormatoPapel.A5, FormatoPapel.desde(valor));
	}

	@ParameterizedTest
	@ValueSource(strings = { "letter", "LETTER", "CARTA", "carta" })
	@DisplayName("Carta se acepta por su nombre y por el valor que usa el selector del visor")
	void cartaSeAceptaPorLosDosNombres(String valor) {
		assertEquals(FormatoPapel.CARTA, FormatoPapel.desde(valor));
	}

	@ParameterizedTest
	@ValueSource(strings = { "A3", "folio", "A5; DROP TABLE facturas", "../../etc/passwd" })
	@DisplayName("Un formato que no existe se rechaza en vez de caer en un valor por defecto")
	void formatoDesconocidoSeRechaza(String valor) {
		assertThrows(IllegalArgumentException.class, () -> FormatoPapel.desde(valor));
	}

	@Test
	@DisplayName("Solo el A5 lleva la maquetación estrecha")
	void soloElA5EsEstrecho() {
		assertTrue(FormatoPapel.A5.esEstrecha());
		assertFalse(FormatoPapel.A4.esEstrecha());
		assertFalse(FormatoPapel.CARTA.esEstrecha());
	}

	@Test
	@DisplayName("El tamaño que se escribe en @page es el que entiende el CSS")
	void elTamanoCssEsElQueEntiendeLaRegla() {
		// «letter» en minúscula: es el identificador de la especificación de
		// paged media, no el nombre de la constante de Java.
		assertEquals("A4",     FormatoPapel.A4.tamanoCss());
		assertEquals("A5",     FormatoPapel.A5.tamanoCss());
		assertEquals("letter", FormatoPapel.CARTA.tamanoCss());
	}
}
