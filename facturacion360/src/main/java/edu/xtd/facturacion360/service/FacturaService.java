package edu.xtd.facturacion360.service;

import java.util.List;

import edu.xtd.facturacion360.dto.DetalleFactura;
import edu.xtd.facturacion360.dto.Factura;
import edu.xtd.facturacion360.dto.FacturaRequest;
import edu.xtd.facturacion360.dto.ResumenTrimestralFactura;
import edu.xtd.facturacion360.dto.SugerenciaConcepto;

/**
 * Operaciones que ofrece la aplicación para trabajar con facturas.
 */
public interface FacturaService {

	public Factura crear(FacturaRequest facturaRequest);

	public Factura editarBorrador(int idFactura, FacturaRequest facturaRequest);

	public List<Factura> buscar(String busqueda);
	
	public List<Factura> buscar(String busqueda, String estado);

	public List<SugerenciaConcepto> buscarSugerenciasConceptos(String texto, int limite);

	public DetalleFactura obtenerDetalle(int idFactura);

	public ResumenTrimestralFactura listarTrimestre(int anio, int trimestre);


}
