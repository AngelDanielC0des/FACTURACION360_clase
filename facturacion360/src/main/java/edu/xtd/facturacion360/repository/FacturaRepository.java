package edu.xtd.facturacion360.repository;

import java.time.LocalDate;
import java.util.List;

import edu.xtd.facturacion360.dto.ClienteFactura;
import edu.xtd.facturacion360.dto.ConceptoFactura;
import edu.xtd.facturacion360.dto.DesgloseImpositivo;
import edu.xtd.facturacion360.dto.Factura;
import edu.xtd.facturacion360.dto.SugerenciaConcepto;

/**
 * Operaciones de base de datos que podemos realizar con las facturas.
 */
public interface FacturaRepository {

	public Factura insertar(Factura factura);

	public int obtenerUltimoNumero(int anio);

	public void insertarConceptos(int idFactura, List<ConceptoFactura> conceptos);

	/** Solo representa la colisión del índice único del número al insertar la cabecera. */
	class NumeroFacturaDuplicadoException extends RuntimeException {
		public NumeroFacturaDuplicadoException(Throwable causa) {
			super("El número de factura ya está ocupado", causa);
		}
	}

	/**
	 * Se factura a un cliente que ya no está en la base de datos.
	 *
	 * <p>Pasa de verdad: entre que se elige el cliente y se guarda la factura cabe que alguien
	 * lo haya borrado, así que al guardar viaja un identificador que ya no existe. Sin
	 * traducirlo, el manejador global respondía «no se puede realizar la operación porque hay
	 * datos relacionados», que dice justo lo contrario de lo que ha ocurrido: el problema no es
	 * que haya datos relacionados, es que faltan.</p>
	 *
	 * <p>El mensaje no nombra ningún control de la pantalla —ni desplegable, ni buscador, ni
	 * lista— a propósito. El repositorio no sabe con qué interfaz le están hablando, y de hecho
	 * ya ha cambiado una vez: decir «recarga la lista» dejó de tener sentido en cuanto el campo
	 * pasó a ser un buscador. Se cuenta el hecho y se deja la instrucción a quien pinta.</p>
	 */
	class ClienteInexistenteException extends RuntimeException {
		public ClienteInexistenteException(Throwable causa) {
			super("El cliente al que se factura ya no existe. Búscalo otra vez o vuelve a "
					+ "darlo de alta", causa);
		}
	}

	public List<Factura> buscar(String busqueda);
	
	public List<Factura> buscar(String busqueda, String estado);
	
	public Factura buscarPorId(int idFactura);

	public Factura buscarPorIdParaActualizar(int idFactura);

	public int actualizarBorrador(Factura factura);

	public void eliminarConceptos(int idFactura);

	/**
	 * Guarda el desglose del IVA tal y como ha quedado al calcularlo.
	 *
	 * <p>Se guarda en vez de recalcularse al leer porque el dia que esto se comunique a
	 * Hacienda, lo declarado no puede cambiar aunque despues alguien corrija una linea.</p>
	 */
	public void insertarDesglose(int idFactura, List<DesgloseImpositivo> desglose);

	public void eliminarDesglose(int idFactura);

	/**
	 * El desglose guardado de una factura.
	 *
	 * <p>Devuelve <strong>lista vacía</strong> para las facturas anteriores a la migración,
	 * que no tienen ninguno guardado. No es un fallo y no hay que tratarlo como tal: quien
	 * llama lo calcula al vuelo a partir de los conceptos, que es la misma operación con la
	 * que se guardó el de las nuevas.</p>
	 */
	public List<DesgloseImpositivo> buscarDesglose(int idFactura);

	public ClienteFactura buscarCliente(int idCliente);

	public List<ConceptoFactura> buscarConceptos(int idFactura);

	public List<SugerenciaConcepto> buscarSugerenciasConceptos(String texto, int limite);

	public List<Factura> buscarPorTrimestre(LocalDate fechaInicio, LocalDate fechaFin);

}
