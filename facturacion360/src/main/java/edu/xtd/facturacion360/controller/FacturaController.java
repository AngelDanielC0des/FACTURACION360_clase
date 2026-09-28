package edu.xtd.facturacion360.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import edu.xtd.facturacion360.dto.DetalleFactura;
import edu.xtd.facturacion360.dto.Factura;
import edu.xtd.facturacion360.dto.FacturaRequest;
import edu.xtd.facturacion360.dto.ResumenTrimestralFactura;
import edu.xtd.facturacion360.dto.SugerenciaConcepto;
import edu.xtd.facturacion360.service.FacturaService;
import jakarta.validation.Valid;

/**
 * Recibe las peticiones HTTP para crear y buscar facturas.
 */
@RestController
@RequestMapping("/factura")
public class FacturaController {

	private static final Logger log = LoggerFactory.getLogger(FacturaController.class);

	@Autowired
	FacturaService facturaService;

	@PostMapping
	public ResponseEntity<Factura> crear(@Valid @RequestBody FacturaRequest facturaRequest) {
		// Sin BindingResult al lado del @Valid a propósito: con él, Spring mete los errores
		// en ese objeto y no lanza nada, y hay que repetir el mismo bloque en cada método.
		// Sin él lanza MethodArgumentNotValidException, que ManejadorExcepciones convierte en
		// un 400 con el motivo de CADA campo, no solo el del primero que falló.
		Factura facturaNueva = facturaService.crear(facturaRequest);

		log.info("POST /factura -> 201, factura {}", facturaNueva.numeroFactura());

		return ResponseEntity.status(HttpStatus.CREATED).body(facturaNueva);
	}

	@PutMapping("/{idFactura}/borrador")
	public ResponseEntity<Factura> editarBorrador(@PathVariable int idFactura,
			@Valid @RequestBody FacturaRequest facturaRequest) {
		return ResponseEntity.ok(facturaService.editarBorrador(idFactura, facturaRequest));
	}

	@GetMapping("/{idFactura}/detalle")
	public ResponseEntity<DetalleFactura> obtenerDetalle(@PathVariable int idFactura) {
		return ResponseEntity.ok(facturaService.obtenerDetalle(idFactura));
	}

	@GetMapping("/buscar")
	public ResponseEntity<List<Factura>> buscar(
			@RequestParam(required = false, defaultValue = "") String busqueda,
			@RequestParam(required = false, defaultValue = "") String estado) {

		List<Factura> facturas = estado.isBlank()
				? facturaService.buscar(busqueda)
				: facturaService.buscar(busqueda, estado);

		return ResponseEntity.ok(facturas);
	}


	@GetMapping("/conceptos/sugerencias")
	public ResponseEntity<List<SugerenciaConcepto>> buscarSugerenciasConceptos(
			@RequestParam(defaultValue = "") String texto,
			@RequestParam(defaultValue = "8") int limite) {
		return ResponseEntity.ok(facturaService.buscarSugerenciasConceptos(texto, limite));
	}

	@GetMapping("/trimestral")
	public ResponseEntity<ResumenTrimestralFactura> listarTrimestre(
			@RequestParam int anio,
			@RequestParam int trimestre) {
		ResumenTrimestralFactura resumen = facturaService.listarTrimestre(anio, trimestre);
		return ResponseEntity.ok(resumen);
	}


}
