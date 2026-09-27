package edu.xtd.facturacion360.dto;


import org.springframework.stereotype.Component;

/**
 * Traduce entre los distintos objetos de Cliente.
 * Con @Component Spring lo crea y lo inyecta donde haga falta (p. ej. en el controller).
 */
@Component
public class ClienteMapper {

	// OJO al construir el Cliente: el id queda a 0 y la fecha de alta a null, porque el
	// ClienteRequest no trae ninguno de los dos (el id viaja en la URL y la fecha es un dato
	// histórico que no se edita). Quien llame a este método tiene que poner los dos por su
	// cuenta: en actualizar(), propagar ese 0 lanzaría el UPDATE con "WHERE idcliente = 0" y
	// copiar ese null borraría la fecha de la respuesta.
	//
	// Esto estuvo fijado con pruebas en ClienteServiceImplTest, que se perdió en el merge
	// f706594 del 14/09 junto con las otras tres clases de prueba de clientes. Hoy no hay
	// nada que lo sujete: si alguien cambia este método, la única defensa es este aviso.
	public Cliente toDomain(ClienteRequest clienteRequest) {
		Cliente cliente = null;
			
			cliente =  new Cliente(
					0,
					clienteRequest.nombre(),
					normalizar(clienteRequest.nifCif()),
					clienteRequest.direccion(),
					clienteRequest.codigoPostal(),
					limpiarTexto(clienteRequest.poblacion()),
					limpiarTexto(clienteRequest.provincia()),
					clienteRequest.telefono(),
					clienteRequest.email(),
					null);
		
			System.out.println("ClienteRequest2Cliente "+ cliente);
		
		return cliente;
	}


	
	/**
	 * Convierte el {@link Cliente} de dominio (lo que sale de la BD) en un {@link ClienteResponse}
	 * (lo que viaja al navegador como JSON). Tener un DTO de salida separado desacopla la entidad
	 * interna del contrato con el frontend: podemos cambiar el modelo por dentro sin romper la API.
	 *
	 * @param cliente el cliente de dominio a convertir; puede ser {@code null}
	 * @return el {@link ClienteResponse} equivalente, o {@code null} si {@code cliente} es null
	 */

	public ClienteResponse toResponse (Cliente cliente)
	{
		ClienteResponse clienteResponse = null;

			if (cliente!=null)
			{
				clienteResponse = new ClienteResponse(
						cliente.idCliente(),
						cliente.nombre(),
						cliente.nifCif(),
						cliente.direccion(),
						cliente.codigoPostal(),
						cliente.poblacion(),
						cliente.provincia(),
						cliente.telefono(),
						cliente.email(),
						cliente.fechaAlta());
			}

		return clienteResponse;
	}

	/**
	 * Deja el documento en mayúsculas y sin espacios de los lados.
	 *
	 * El validador acepta "12345678z" porque obligar a pulsar mayúsculas para teclear un NIF
	 * es una molestia sin motivo, pero guardarlo así haría que la ficha lo enseñara en
	 * minúscula y que dos altas del mismo cliente se vieran distintas. El índice UNIQUE de
	 * nif_cif no se ve afectado —su collation ya ignora mayúsculas—, o sea que esto es
	 * cuestión de lo que se ve, no de integridad.
	 *
	 * @param documento lo que vino en la petición
	 * @return el mismo documento listo para guardar, o null si no venía
	 */
	private static String normalizar(String documento) {
		return documento == null ? null : documento.trim().toUpperCase();
	}

	/**
	 * Deja un nombre de lugar sin espacios sobrantes, respetando cómo se escribió.
	 *
	 * <p>Se aplica a población y provincia porque son las dos que alimentan los desplegables
	 * del filtro, que salen de un {@code SELECT DISTINCT}: un espacio de más convierte
	 * «Madrid&nbsp;» en una provincia distinta de «Madrid», y las dos aparecen en la lista.</p>
	 *
	 * <p>De las mayúsculas NO se encarga esto, y es a propósito: la collation de la tabla es
	 * {@code utf8mb4_0900_ai_ci}, que ya compara ignorando mayúsculas y acentos, así que
	 * «madrid» y «Madrid» se agrupan solos. Lo que esas collations <strong>no</strong> ignoran
	 * es el espacio final —son NO PAD, al revés que las antiguas—, y por eso el {@code trim} sí
	 * hace falta. Tampoco se sube a mayúsculas como en {@link #normalizar(String)}: ahí tiene
	 * sentido porque un NIF se escribe así, pero una provincia en mayúsculas quedaría gritando
	 * en la ficha.</p>
	 *
	 * <p><strong>Las dos barras de {@code "\\s+"} no son opcionales.</strong> Desde Java 15,
	 * {@code \s} dentro de un literal es un escape que vale <em>un espacio</em>, así que
	 * {@code "\s+"} —con una sola— se compila sin error y produce la expresión {@code " +"},
	 * que no toca los tabuladores. Y un tabulador hace exactamente lo mismo que el espacio de
	 * más con una collation NO PAD: duplica la entrada del desplegable. Está fijado en
	 * {@code ClienteMapperTests}.</p>
	 *
	 * <p>No se aplica a nombre ni dirección aunque también puedan traer espacios: esos dos no
	 * se usan como clave de agrupación ni de filtro, así que un espacio de más se ve feo pero
	 * no duplica nada. Si algún día se filtra por ellos, tendrán que pasar por aquí.</p>
	 *
	 * @param valor lo que vino en la petición
	 * @return el mismo texto sin espacios en los extremos y sin repetirlos dentro, o
	 *         {@code null} si no venía
	 */
	private static String limpiarTexto(String valor) {
		return valor == null ? null : valor.trim().replaceAll("\\s+", " ");
	}

}
