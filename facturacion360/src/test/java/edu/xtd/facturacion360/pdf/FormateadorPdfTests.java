package edu.xtd.facturacion360.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El formateador del PDF es el espejo en Java de tres funciones que el visor ya
 * tiene en JavaScript. Lo que se comprueba aquí es justamente eso: que un mismo
 * dato se lea igual en la pantalla y en el papel.
 */
class FormateadorPdfTests {

	private FormateadorPdf fmt;

	@BeforeEach
	void crearFormateador() {
		fmt = new FormateadorPdf();
	}

	/**
	 * El formato de moneda español separa el importe del símbolo con un espacio
	 * DURO (U+00A0), no con uno normal. Se normaliza para que las comparaciones
	 * de este test digan lo que parece que dicen.
	 */
	private String normalizar(String texto) {
		return texto.replace(' ', ' ');
	}

	@Test
	@DisplayName("Los importes salen en euros y con el punto de los miles")
	void importesEnFormatoEspanol() {
		assertEquals("1.512,50 €", normalizar(fmt.importe(new BigDecimal("1512.50"))));
		assertEquals("0,00 €",     normalizar(fmt.importe(BigDecimal.ZERO)));
		assertEquals("1.234.567,89 €",
				normalizar(fmt.importe(new BigDecimal("1234567.89"))));
	}

	@Test
	@DisplayName("Un importe nulo cuenta como cero, igual que en el visor")
	void importeNuloCuentaComoCero() {
		assertEquals("0,00 €", normalizar(fmt.importe(null)));
	}

	@Test
	@DisplayName("Los porcentajes llevan su símbolo separado, como en la factura impresa")
	void porcentajesConSuSimbolo() {
		assertEquals("21 %",  fmt.porcentaje(new BigDecimal("21")));
		assertEquals("10,5 %", fmt.porcentaje(new BigDecimal("10.5")));
		assertEquals("0 %",   fmt.porcentaje(null));
	}

	@Test
	@DisplayName("Las fechas se escriben como se leen en España")
	void fechasEnFormatoEspanol() {
		assertEquals("20/09/2026", fmt.fecha(LocalDate.of(2026, 9, 20)));
		assertEquals("01/01/2026", fmt.fecha(LocalDate.of(2026, 1, 1)));
	}

	@Test
	@DisplayName("Lo que falta se pinta con una raya y no con un hueco vacío")
	void loQueFaltaSePintaConUnaRaya() {
		assertEquals("—", fmt.fecha(null));
		assertEquals("—", fmt.oRaya(null));
		assertEquals("—", fmt.oRaya(""));
		assertEquals("—", fmt.oRaya("   "));
	}

	@Test
	@DisplayName("Un texto con contenido se respeta tal cual, solo sin espacios de sobra")
	void elTextoConContenidoSeRespeta() {
		assertEquals("Señores Muñoz & Peña S.L.", fmt.oRaya("  Señores Muñoz & Peña S.L.  "));
		// La cantidad de un concepto es un número, no una cadena.
		assertEquals("3", fmt.oRaya(3));
	}
}
