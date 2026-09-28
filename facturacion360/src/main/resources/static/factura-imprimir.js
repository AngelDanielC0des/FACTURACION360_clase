import { crearAvisos } from "./js/notificaciones.js";
import { motivoDe } from "./js/problema.js";

const estadoVisor = document.getElementById("estadoVisor");
const contenidoFactura = document.getElementById("contenidoFactura");
const documentoFactura = document.getElementById("documentoFactura");
const botonImprimir = document.getElementById("botonImprimir");
const tablaConceptos = document.getElementById("tablaConceptos");
const tablaDesglose = document.getElementById("tablaDesglose");
const bloqueDesglose = document.getElementById("bloqueDesglose");
const seccionConceptos = document.getElementById("bloqueConceptos");

const bloqueQr      = document.getElementById("bloqueQr");
const imagenQr      = document.getElementById("imagenQr");
const marcaAgua     = document.getElementById("marcaAgua");
const selectFormato = document.getElementById("selectFormatoPapel");
const estiloHoja    = document.getElementById("estiloFormatoPapel");

// Los avisos de esta pantalla. Aquí la franja es el propio rótulo de estado del visor: el
// "Cargando factura..." que ya viene escrito en el HTML es un estado, no un evento, así que
// se queda hasta que la factura carga (limpiar) o hasta que falla (fijar).
const { fijar, limpiar } = crearAvisos({
    franja: estadoVisor,
    region: document.getElementById("anuncios"),
});

/** Carga la factura indicada en la dirección de la página. */
async function cargarDetalleFactura() {
    const parametros = new URLSearchParams(window.location.search);
    const idFactura = Number(parametros.get("idFactura"));

    if (Number.isInteger(idFactura) && idFactura > 0) {
        try {
            const respuesta = await fetch("/factura/" + idFactura + "/detalle");
            if (respuesta.ok) {
                const detalle = await respuesta.json();
                mostrarDetalle(detalle);
            } else {
                mostrarError(await motivoDe(respuesta, "No se pudo cargar la factura."));
            }
        } catch (error) {
            console.error("Error al cargar el detalle de la factura", error);
            mostrarError("No se pudo conectar con el servidor.");
        }
    } else {
        mostrarError("El identificador de factura no es válido.");
    }
}

/** Muestra la cabecera, el cliente, los conceptos y los totales. */
function mostrarDetalle(detalle) {
    const factura = detalle.factura;
    const cliente = detalle.cliente;
    const editar = document.getElementById("botonEditarBorrador");
    editar.classList.toggle("d-none", factura.estado != "BORRADOR");
    if (factura.estado == "BORRADOR") {
        editar.href = "facturas.html?editar=" + factura.idFactura;
    }

    document.getElementById("numeroFactura").textContent = factura.numeroFactura;
    document.getElementById("fechaFactura").textContent = "Fecha: " + formatearFecha(factura.fechaEmision);
    document.getElementById("estadoFactura").textContent = "Estado: " + factura.estado;
    document.getElementById("nombreCliente").textContent = cliente.nombre;
    document.getElementById("nifCliente").textContent = "NIF/CIF: " + cliente.nifCif;
    document.getElementById("direccionCliente").textContent = formarDireccion(cliente);
    document.getElementById("contactoCliente").textContent = formarContacto(cliente);
    document.getElementById("subtotalFactura").textContent = formatearImporte(factura.subtotal);
    document.getElementById("ivaFactura").textContent = formatearImporte(factura.importeIva);
    document.getElementById("totalFactura").textContent = formatearImporte(factura.total);

    mostrarConceptos(detalle.conceptos);
    mostrarDesglose(detalle.desglose);

    if (factura.observaciones != null && factura.observaciones.trim() != "") {
        document.getElementById("observacionesFactura").textContent = factura.observaciones;
        document.getElementById("bloqueObservaciones").classList.remove("d-none");
    }

    limpiar();
    contenidoFactura.classList.remove("d-none");
    documentoFactura.setAttribute("aria-busy", "false");
    botonImprimir.disabled = false;

    const esBorrador = factura.estado === "BORRADOR";
    marcaAgua.classList.toggle("d-none", !esBorrador);
    mostrarQr(esBorrador, factura.idFactura);

    // El contenido acaba de entrar: se recalculan los cortes de página del
    // borrador para que enseñe los mismos que saldrán impresos.
    programarPaginar();
}

/**
 * Pinta el cuadro del IVA agrupado por tipo.
 *
 * El desglose lo calcula el SERVIDOR y aqui solo se pinta. No es pereza: la regla de
 * redondeo -redondear la cuota de cada linea y luego sumar- es la misma que acabara dentro
 * de la huella que se comunica a Hacienda, y tenerla escrita en dos idiomas es tenerla
 * escrita dos veces para que se desalineen.
 *
 * Si no hay desglose -una factura sin conceptos- el cuadro no se ensena en vez de salir
 * vacio: un recuadro con cabeceras y nada debajo parece un fallo de carga.
 *
 * @param {Array} desglose una linea por tipo impositivo, o nada
 */
function mostrarDesglose(desglose) {
    tablaDesglose.replaceChildren();

    if (!desglose || desglose.length == 0) {
        bloqueDesglose.classList.add("d-none");
        return;
    }

    for (const linea of desglose) {
        const fila = document.createElement("tr");
        agregarCelda(fila, formatearImporte(linea.baseImponible), "text-end");
        agregarCelda(fila, formatearPorcentaje(linea.tipoImpositivo), "text-end");
        agregarCelda(fila, formatearImporte(linea.cuotaRepercutida), "text-end");
        tablaDesglose.appendChild(fila);
    }

    bloqueDesglose.classList.remove("d-none");
}

/** Rellena la tabla o muestra una fila informativa si la factura no tiene conceptos. */
function mostrarConceptos(conceptos) {
    tablaConceptos.replaceChildren();

    if (conceptos.length == 0) {
        const fila = document.createElement("tr");
        const celda = document.createElement("td");
        celda.colSpan = 7;
        celda.className = "text-center text-muted";
        celda.textContent = "Esta factura no tiene conceptos registrados.";
        fila.appendChild(celda);
        tablaConceptos.appendChild(fila);
    } else {
        for (const concepto of conceptos) {
            const fila = document.createElement("tr");
            agregarCelda(fila, concepto.descripcion || "—");
            agregarCelda(fila, concepto.cantidad ?? "—", "text-end");
            agregarCelda(fila, formatearImporte(concepto.precioUnitario), "text-end");
            agregarCelda(fila, formatearPorcentaje(concepto.descuento), "text-end");
            agregarCelda(fila, formatearImporte(concepto.baseImponible), "text-end");
            agregarCelda(fila, formatearImporte(concepto.importeIva), "text-end");
            agregarCelda(fila, formatearImporte(concepto.total), "text-end");
            tablaConceptos.appendChild(fila);
        }
    }
}

/**
 * Muestra el bloque QR si la factura está emitida, o lo oculta si es borrador.
 *
 * Los borradores no generan registro de facturación y por tanto no tienen URL
 * en la sede de la AEAT. Intentar cargar la imagen daría un 404.
 *
 * @param {boolean} esBorrador - true si la factura es un borrador
 * @param {number}  idFactura  - identificador de la factura
 */
function mostrarQr(esBorrador, idFactura) {
    if (esBorrador) {
        bloqueQr.classList.add("d-none");
        return;
    }
    imagenQr.src = `/verifactu/qr/${idFactura}`;
    bloqueQr.classList.remove("d-none");
}

/** Carga los datos de la informacion del emisor */
async function cargarEmisor() {
    try {
        const respuesta = await fetch("/emisor");
        if (respuesta.ok) {
            const emisor = await respuesta.json();
            mostrarEmisor(emisor);
        }
    } catch (error) {
        console.error("Error al cargar los datos del emisor", error);
    }
}
/** Muestra los datos de la informacion del emisor */
function mostrarEmisor(emisor) {
    if (!emisor) return;
    
    // Asignamos los datos únicamente a la sección del recuadro "Emisor"
    document.getElementById("nombreEmisor").textContent = emisor.nombre || "—";
    document.getElementById("cifEmisor").textContent = "NIF/CIF: " + (emisor.cif || emisor.nifCif || "—");
    document.getElementById("direccionEmisor").textContent = emisor.direccion || "—";
    
    const tel = emisor.telefono || "";
    const email = emisor.email || "";
    document.getElementById("contactoEmisor").textContent = [tel, email].filter(Boolean).join(" · ") || "Sin contacto";
}

function agregarCelda(fila, texto, clases) {
    const celda = document.createElement("td");
    celda.textContent = texto;
    if (clases != null) {
        celda.className = clases;
    }
    fila.appendChild(celda);
}

function formarDireccion(cliente) {
    const codigoPostal = cliente.codigoPostal || "";
    return cliente.direccion + ", " + codigoPostal + " " + cliente.poblacion + " (" + cliente.provincia + ")";
}

function formarContacto(cliente) {
    const telefono = cliente.telefono || "Sin teléfono";
    const email = cliente.email || "Sin email";
    return telefono + " · " + email;
}

function formatearFecha(fecha) {
    const partes = fecha.split("-");
    return partes[2] + "/" + partes[1] + "/" + partes[0];
}

function formatearImporte(importe) {
    const valor = importe == null ? 0 : importe;
    return Number(valor).toLocaleString("es-ES", { style: "currency", currency: "EUR" });
}

function formatearPorcentaje(porcentaje) {
    const valor = porcentaje == null ? 0 : porcentaje;
    return Number(valor).toLocaleString("es-ES") + " %";
}

function mostrarError(mensaje) {
    fijar(mensaje, { esError: true });
    documentoFactura.setAttribute("aria-busy", "false");
}

botonImprimir.addEventListener("click", function () {
    window.print();
});

/**
 * Dimensiones de la hoja del borrador en pantalla, por formato.
 * "" (por defecto) no puede saber el papel de la impresora, así que el
 * borrador se queda como referencia en A4.
 * "estrecha" marca la hoja que necesita la maquetación de columna
 * (la misma que la impresión aplica con @media contra el papel).
 */
const HOJAS = {
    "":     { ancho: "210mm",   alto: "297mm" },
    A4:     { ancho: "210mm",   alto: "297mm" },
    A5:     { ancho: "148mm",   alto: "210mm", estrecha: true },
    letter: { ancho: "215.9mm", alto: "279.4mm" },
};

/**
 * Paginación del borrador.
 *
 * El objetivo es que la hoja que se ve en pantalla enseñe exactamente los
 * mismos cortes que saldrán impresos: el contenido se parte en varias hojas
 * .documento-factura (una por página) en el mismo sitio en que el navegador
 * partiría el papel, y en impresión cada hoja arranca con break-before: page.
 * Si todo cabe en una sola hoja no se crea ninguna y la vista queda como
 * siempre, así que A4 y Carta cortos no cambian ni un píxel.
 */
let nodosOriginales = null;   // hijos de #contenidoFactura, capturados una vez
let paginando = false;        // guarda contra reentrada (ResizeObserver)
let pedidoPaginar = 0;

/** Convierte una medida CSS (mm) a píxeles con la conversión que usa el navegador. */
function medirCss(valor) {
    const sonda = document.createElement("div");
    sonda.style.cssText = "position:absolute;visibility:hidden;height:" + valor;
    document.body.appendChild(sonda);
    const px = sonda.offsetHeight;
    sonda.remove();
    return px;
}

function hojasExtra() {
    return Array.from(document.querySelectorAll(".documento-factura.hoja-extra"));
}

/**
 * Altura que ocupa la hoja sin el min-height, es decir, su contenido real más
 * su padding. Sin quitar el min-height la medición se quedaría en el alto del
 * papel aunque el contenido se desbordara, y nunca se paginaría.
 */
function altoUsado(hoja) {
    const minimo = hoja.style.minHeight;
    hoja.style.minHeight = "0";
    const alto = hoja.offsetHeight;
    hoja.style.minHeight = minimo;
    return alto;
}

/** Los bloques de la hoja principal viven en #contenidoFactura; los de las
 *  hojas extra, directamente en la hoja. */
function contenedorDe(hoja) {
    return hoja === documentoFactura ? contenidoFactura : hoja;
}

function crearHojaDespues(despues) {
    const hoja = document.createElement("div");
    hoja.className = "documento-factura hoja-extra";
    hoja.classList.toggle("hoja-estrecha", documentoFactura.classList.contains("hoja-estrecha"));
    despues.after(hoja);
    return hoja;
}

/**
 * Deshace la paginación: cada nodo original vuelve a #contenidoFactura en su
 * orden y las hojas extra se borran. Es el primer paso de cada paginación,
 * para partir siempre del mismo estado.
 */
function fusionar() {
    if (!nodosOriginales) return;

    // Las filas de las tablas de continuación son nodos originales movidos:
    // vuelven a #tablaConceptos (en orden de hoja) antes de que desaparezcan
    // las hojas que las contienen.
    document.querySelectorAll(".tabla-continuacion tbody").forEach(cuerpo => {
        tablaConceptos.append(...cuerpo.children);
    });

    contenidoFactura.append(...nodosOriginales);
    hojasExtra().forEach(hoja => hoja.remove());
}

/**
 * Crea la sección de conceptos de una hoja de continuación: mismo aspecto y
 * mismo thead que la original, con las filas que lleguen después.
 */
function crearContinuacion(origen) {
    const seccion = document.createElement("section");
    const titulo = document.createElement("h2");
    titulo.className = "h5 visually-hidden";
    titulo.textContent = "Conceptos (continuación)";
    const contenedor = document.createElement("div");
    contenedor.className = "table-responsive";
    const tabla = origen.querySelector("table").cloneNode(false);
    tabla.classList.add("tabla-continuacion");
    tabla.append(origen.querySelector("thead").cloneNode(true));
    const cuerpo = document.createElement("tbody");
    tabla.append(cuerpo);
    contenedor.append(tabla);
    seccion.append(titulo, contenedor);
    return seccion;
}

/**
 * Reparte las filas de los conceptos entre la sección original (que rellena
 * lo que queda de la hoja en la que está) y tantas continuaciones como
 * hagan falta. Es el equivalente en pantalla de lo que el navegador hace solo
 * al imprimir (tr { break-inside: avoid }): el corte siempre cae entre filas
 * y la página no se queda con un hueco sin rellenar.
 *
 * @param {number} limite alto máximo en píxeles de una hoja con contenido
 * @return {{hoja: Element, filas: number}} última hoja usada y filas en ella
 */
function partirConceptos(limite) {
    const filas = Array.from(tablaConceptos.children);
    tablaConceptos.replaceChildren();

    let hojaActual = seccionConceptos.closest(".documento-factura");

    // Ni la sección sin filas cabe en lo que queda: se va sola a una hoja
    // nueva (el papel empujaría la tabla entera igual).
    if (altoUsado(hojaActual) > limite) {
        seccionConceptos.remove();
        hojaActual = crearHojaDespues(hojaActual);
        hojaActual.append(seccionConceptos);
    }

    let tablaActual = tablaConceptos;
    let filasEnPagina = 0;

    for (const fila of filas) {
        tablaActual.append(fila);
        filasEnPagina += 1;

        if (altoUsado(hojaActual) <= limite) continue;

        // Ni quitando esta fila cabe la hoja: la fila no se puede partir y
        // se queda donde está (la impresión se encontraría el mismo muro).
        if (altoUsado(hojaActual) - fila.offsetHeight > limite) continue;

        fila.remove();
        filasEnPagina -= 1;

        hojaActual = crearHojaDespues(hojaActual);
        const continuacion = crearContinuacion(seccionConceptos);
        hojaActual.append(continuacion);
        tablaActual = continuacion.querySelector("tbody");
        tablaActual.append(fila);
        filasEnPagina = 1;
    }

    return { hoja: hojaActual, filas: filasEnPagina };
}

/**
 * Parte el contenido en tantas hojas como necesite el papel elegido para que
 * el borrador muestre los mismos cortes que la impresión.
 */
function partirEnHojas() {
    const formato = HOJAS[selectFormato.value];
    if (!formato) return;

    if (!nodosOriginales) nodosOriginales = Array.from(contenidoFactura.children);

    // 2px de margen: absorben la diferencia de redondeo mm→px entre la
    // maquetación en pantalla y la caja de la página impresa.
    const limite = medirCss(formato.alto) - 2;

    // Se empieza con la hoja principal casi vacía: la marca de agua se queda
    // (está fijada al viewport y no ocupa sitio en el flujo).
    nodosOriginales.forEach(nodo => {
        if (nodo !== marcaAgua) nodo.remove();
    });

    let hojaActual = documentoFactura;
    let enPagina = 0;

    for (const nodo of nodosOriginales) {
        if (nodo === marcaAgua) continue;

        contenedorDe(hojaActual).append(nodo);

        if (altoUsado(hojaActual) <= limite) {
            enPagina += 1;
            continue;
        }

        if (enPagina === 0) {
            // No cabe ni solo: no hay otra hoja a la que moverlo.
            enPagina = 1;
            continue;
        }

        if (nodo === seccionConceptos) {
            // La tabla no cabe entera en lo que queda de la página: se
            // rellena con las filas que quepan (como haría el papel, que no
            // deja un hueco sin rellenar) y el resto sigue en hojas de
            // continuación.
            const reparto = partirConceptos(limite);
            hojaActual = reparto.hoja;
            enPagina = Math.max(reparto.filas, 1);
            continue;
        }

        nodo.remove();
        hojaActual = crearHojaDespues(hojaActual);
        contenedorDe(hojaActual).append(nodo);
        enPagina = 1;
    }

    hojasExtra().forEach((hoja, i) => { hoja.dataset.pagina = String(i + 2); });
}

/** Recalcula los cortes de página en el siguiente fotograma. */
function programarPaginar() {
    cancelAnimationFrame(pedidoPaginar);
    pedidoPaginar = requestAnimationFrame(paginar);
}

function paginar() {
    if (paginando) return;
    paginando = true;
    try {
        fusionar();
        if (!contenidoFactura.classList.contains("d-none")) {
            partirEnHojas();
        }
    } finally {
        paginando = false;
    }
}

/**
 * Inyecta o elimina la regla @page dinámicamente en el documento y
 * redimensiona la hoja del borrador para que la vista en pantalla
 * coincida con el formato que se imprimirá.
 * Si el usuario no elige formato, se deja vacío para respetar la configuración
 * por defecto del cuadro de diálogo de impresión del navegador.
 */
function actualizarFormatoPapel() {
    estiloHoja.textContent = selectFormato.value
        ? `@page { size: ${selectFormato.value}; margin: 14mm; }`
        : "";

    const hoja = HOJAS[selectFormato.value];
    if (hoja) {
        document.documentElement.style.setProperty("--hoja-ancho", hoja.ancho);
        document.documentElement.style.setProperty("--hoja-alto", hoja.alto);
        // Todas las hojas (principal y las que haya creado la paginación)
        // comparten la maquetación de columna del A5.
        document.querySelectorAll(".documento-factura").forEach(hojaDom =>
            hojaDom.classList.toggle("hoja-estrecha", Boolean(hoja.estrecha)));
        // El límite de página cambia con el formato: se vuelve a partir.
        programarPaginar();
    }
}

selectFormato.addEventListener("change", actualizarFormatoPapel);
actualizarFormatoPapel();

// Cualquier cambio de tamaño (texto largo, logo que no carga, cambio de
// formato) vuelve a partir el contenido en hojas.
new ResizeObserver(programarPaginar).observe(document.body);

cargarDetalleFactura();
cargarEmisor();