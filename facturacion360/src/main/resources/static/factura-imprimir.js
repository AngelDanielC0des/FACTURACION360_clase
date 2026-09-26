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

/** Carga la factura indicada en la dirección de la página o por argumento explícito. */
async function cargarDetalleFactura(idExplicito) {
    const parametros = new URLSearchParams(window.location.search);
    const idFactura = idExplicito ?? Number(parametros.get("idFactura"));

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


// ── Selección múltiple ──────────────────────────────────────────────

const panelBuscador        = document.getElementById("panelBuscador");
const botonSeleccionarVarias = document.getElementById("botonSeleccionarVarias");
const botonCerrarPanel     = document.getElementById("botonCerrarPanel");
const campoBusqueda        = document.getElementById("campoBusquedaFacturas");
const filtroEstado         = document.getElementById("filtroEstado");
const listaResultados      = document.getElementById("listaResultados");
const contadorResultados   = document.getElementById("contadorResultados");
const botonAgregarSeleccion = document.getElementById("botonAgregarSeleccion");

const sidebarCola          = document.getElementById("sidebarCola");
const listaCola            = document.getElementById("listaCola");
const contadorCola         = document.getElementById("contadorCola");
const botonLimpiarCola     = document.getElementById("botonLimpiarCola");
const botonImprimirTodas   = document.getElementById("botonImprimirTodas");

/**
 * IDs de las facturas marcadas con el checkbox en el panel de búsqueda.
 * Se usa un Set para garantizar unicidad sin búsquedas lineales.
 * @type {Set<number>}
 */
const marcadas = new Set();

/**
 * Cola de impresión: facturas completas (cabecera) listas para imprimir.
 * Se usa un Map id→Factura para mantener el orden de inserción y
 * permitir eliminación por clave en O(1) sin recorrer un array.
 * @type {Map<number, object>}
 */
const colaImpresion = new Map();

/** Vincula cada nodo tarjeta con su objeto Factura sin contaminar el DOM. */
const facturasDeTarjeta = new WeakMap();

/** Abre el panel de búsqueda con transición CSS. */
function abrirPanelBuscador() {
    marcadas.clear();
    panelBuscador.classList.add("abierto");
    panelBuscador.setAttribute("aria-hidden", "false");
    campoBusqueda.value = "";
    campoBusqueda.focus();
    buscarFacturasConRetardo();
}

/** Cierra el panel de búsqueda con transición CSS. */
function cerrarPanelBuscador() {
    panelBuscador.classList.remove("abierto");
    panelBuscador.setAttribute("aria-hidden", "true");
}

botonSeleccionarVarias.addEventListener("click", abrirPanelBuscador);
botonCerrarPanel.addEventListener("click", cerrarPanelBuscador);

// Cerrar al hacer clic en el fondo oscuro (no en el contenido).
panelBuscador.addEventListener("click", function (evento) {
    if (evento.target === panelBuscador) {
        cerrarPanelBuscador();
    }
});

let temporizadorBusqueda = 0;

/**
 * Programa una búsqueda tras 300 ms de inactividad del teclado.
 * El temporizador anterior se cancela para que solo se ejecute la
 * última pulsación: sin esto, teclear «FAC» lanzaría tres peticiones.
 */
function buscarFacturasConRetardo() {
    clearTimeout(temporizadorBusqueda);
    temporizadorBusqueda = setTimeout(ejecutarBusqueda, 300);
}

/** Llama al endpoint con el texto y el filtro de estado actuales. */
async function ejecutarBusqueda() {
    const texto = campoBusqueda.value.trim();
    const estado = filtroEstado.value;

    const parametros = new URLSearchParams();
    parametros.set("busqueda", texto);
    if (estado) {
        parametros.set("estado", estado);
    }

    try {
        const respuesta = await fetch("/factura/buscar?" + parametros);
        if (!respuesta.ok) {
            contadorResultados.textContent = "Error al buscar facturas.";
            return;
        }
        const facturas = await respuesta.json();
        mostrarResultadosBusqueda(facturas);
    } catch {
        contadorResultados.textContent = "No se pudo conectar con el servidor.";
    }
}

campoBusqueda.addEventListener("input", buscarFacturasConRetardo);
filtroEstado.addEventListener("change", buscarFacturasConRetardo);

/**
 * Pinta la lista de facturas devueltas por el buscador.
 * Cada tarjeta lleva un checkbox que se sincroniza con el Set `marcadas`.
 *
 * @param {Array<object>} facturas — lista de facturas del backend
 */
function mostrarResultadosBusqueda(facturas) {
    listaResultados.replaceChildren();
    
    // Contar cuántas se van a mostrar (las que no están en cola)
    let mostradas = 0;

    for (const factura of facturas) {
        // Las que ya están en la cola no se muestran: no tiene sentido
        // seleccionar algo que ya va a imprimirse.
        if (colaImpresion.has(factura.idFactura)) continue;

        const tarjeta = crearTarjetaResultado(factura);
        listaResultados.appendChild(tarjeta);
        mostradas++;
    }

    contadorResultados.textContent = facturas.length + " factura(s) encontrada(s)" 
        + (facturas.length > mostradas ? ` (${facturas.length - mostradas} ya en cola)` : "");
    actualizarBotonAgregar();
}

/**
 * Construye el nodo DOM de una tarjeta de resultado con su checkbox.
 *
 * @param {object} factura — cabecera de la factura
 * @return {HTMLElement} la tarjeta lista para insertar
 */
function crearTarjetaResultado(factura) {
    const tarjeta = document.createElement("label");
    tarjeta.className = "tarjeta-resultado";

    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.className = "form-check-input flex-shrink-0";
    checkbox.checked = marcadas.has(factura.idFactura);

    checkbox.addEventListener("change", function () {
        if (this.checked) {
            marcadas.add(factura.idFactura);
            tarjeta.classList.add("seleccionada");
        } else {
            marcadas.delete(factura.idFactura);
            tarjeta.classList.remove("seleccionada");
        }
        actualizarBotonAgregar();
    });

    const info = document.createElement("div");
    info.className = "tarjeta-resultado-info";
    info.innerHTML =
        `<div class="tarjeta-resultado-numero">${factura.numeroFactura}</div>` +
        `<div class="tarjeta-resultado-cliente">${factura.nombreCliente || "—"}</div>`;

    const badge = document.createElement("span");
    badge.className = "badge-estado badge-estado-" + factura.estado.toLowerCase();
    badge.textContent = factura.estado;

    const importe = document.createElement("span");
    importe.className = "tarjeta-resultado-importe";
    importe.textContent = formatearImporte(factura.total);

    tarjeta.append(checkbox, info, badge, importe);

    if (marcadas.has(factura.idFactura)) {
        tarjeta.classList.add("seleccionada");
    }

    facturasDeTarjeta.set(tarjeta, factura);

    return tarjeta;
}

/** Habilita o deshabilita el botón "Añadir" según haya marcadas. */
function actualizarBotonAgregar() {
    botonAgregarSeleccion.disabled = marcadas.size === 0;
}

/**
 * Mueve las facturas marcadas a la cola de impresión, cierra el panel
 * y muestra la sidebar.
 */
function agregarMarcadasACola() {
    for (const tarjeta of listaResultados.querySelectorAll(".tarjeta-resultado")) {
        const checkbox = tarjeta.querySelector("input[type=checkbox]");
        if (!checkbox.checked) continue;

        const factura = facturasDeTarjeta.get(tarjeta);
        colaImpresion.set(factura.idFactura, factura);
    }

    marcadas.clear();
    cerrarPanelBuscador();
    mostrarSidebar();
}

botonAgregarSeleccion.addEventListener("click", agregarMarcadasACola);

/** Muestra la sidebar y actualiza el cuerpo para dejar hueco. */
function mostrarSidebar() {
    sidebarCola.classList.remove("d-none");
    document.body.classList.add("con-sidebar");
    actualizarSidebar();
}

/** Oculta la sidebar y restaura el cuerpo. */
function ocultarSidebar() {
    sidebarCola.classList.add("d-none");
    document.body.classList.remove("con-sidebar");
}

/** Redibuja el contenido de la sidebar según el estado de la cola. */
function actualizarSidebar() {
    listaCola.replaceChildren();
    contadorCola.textContent = String(colaImpresion.size);
    botonImprimirTodas.disabled = colaImpresion.size === 0;

    if (colaImpresion.size === 0) {
        ocultarSidebar();
        return;
    }

    // Leemos el idFactura de la URL actual para resaltarlo
    const idActual = Number(new URLSearchParams(window.location.search).get("idFactura"));

    for (const [id, factura] of colaImpresion) {
        listaCola.appendChild(crearTarjetaCola(factura));
    }
    
    if (idActual) {
        resaltarTarjetaActiva(idActual);
    }
}

/**
 * Crea una tarjeta de la cola con botón de quitar y clic para previsualizar.
 *
 * @param {object} factura — cabecera de la factura
 * @return {HTMLElement} la tarjeta
 */
function crearTarjetaCola(factura) {
    const tarjeta = document.createElement("div");
    tarjeta.className = "tarjeta-cola";
    tarjeta.dataset.id = factura.idFactura;

    const info = document.createElement("div");
    info.className = "tarjeta-cola-info";
    info.innerHTML =
        `<div class="tarjeta-cola-numero">${factura.numeroFactura}</div>` +
        `<div class="tarjeta-cola-importe">${formatearImporte(factura.total)}</div>`;

    const botonQuitar = document.createElement("button");
    botonQuitar.type = "button";
    botonQuitar.className = "btn btn-sm btn-outline-danger flex-shrink-0";
    botonQuitar.innerHTML = '<i class="bi bi-x-lg"></i>';
    botonQuitar.title = "Quitar de la cola";

    // Clic en la tarjeta → previsualizar esa factura en el visor principal.
    info.addEventListener("click", function () {
        window.history.replaceState(null, "", "?idFactura=" + factura.idFactura);
        cargarDetalleFactura(factura.idFactura);
        resaltarTarjetaActiva(factura.idFactura);
    });

    // Clic en el botón × → quitar de la cola.
    botonQuitar.addEventListener("click", function (evento) {
        evento.stopPropagation();
        colaImpresion.delete(factura.idFactura);
        actualizarSidebar();
    });

    tarjeta.append(info, botonQuitar);
    return tarjeta;
}

/** Marca visualmente la tarjeta de la factura que se está previsualizando. */
function resaltarTarjetaActiva(idFactura) {
    for (const tarjeta of listaCola.children) {
        tarjeta.classList.toggle("activa",
                tarjeta.dataset.id === String(idFactura));
    }
}

botonLimpiarCola.addEventListener("click", function () {
    colaImpresion.clear();
    actualizarSidebar();
});


/**
 * Construye el HTML completo de la factura.
 */
function construirHtmlFactura(detalle) {
    const f = detalle.factura;
    const c = detalle.cliente;
    const emisor = window.__datosEmisor || {}; // Guardado en cargarEmisor
    const conceptos = detalle.conceptos || [];
    const desglose = detalle.desglose || [];

    const esBorrador = f.estado === "BORRADOR";
    const marcaAgua = esBorrador ? `<p class="marca-agua" aria-hidden="true">BORRADOR</p>` : '';

    const qr = !esBorrador ? `
        <figure class="bloque-verifactu" aria-label="Código QR de verificación fiscal">
            <p class="rotulo-qr">QR tributario:</p>
            <img class="qr-verifactu" src="/verifactu/qr/${f.idFactura}" alt="Código QR de validación" onerror="this.closest('.bloque-verifactu').style.display='none'">
            <figcaption class="leyenda-verifactu">
                VERI*FACTU<br><small>Factura verificable en la sede electrónica de la AEAT</small>
            </figcaption>
        </figure>
    ` : '';

    let conceptosHtml = '';
    if (conceptos.length === 0) {
        conceptosHtml = `<tr><td colspan="7" class="text-center text-muted">Esta factura no tiene conceptos registrados.</td></tr>`;
    } else {
        conceptosHtml = conceptos.map(co => `
            <tr>
                <td>${co.descripcion || "—"}</td>
                <td class="text-end">${co.cantidad ?? "—"}</td>
                <td class="text-end">${formatearImporte(co.precioUnitario)}</td>
                <td class="text-end">${formatearPorcentaje(co.descuento)}</td>
                <td class="text-end">${formatearImporte(co.baseImponible)}</td>
                <td class="text-end">${formatearImporte(co.importeIva)}</td>
                <td class="text-end">${formatearImporte(co.total)}</td>
            </tr>
        `).join('');
    }

    let desgloseHtml = '';
    if (desglose.length > 0) {
        desgloseHtml = desglose.map(d => `
            <tr>
                <td class="text-end">${formatearImporte(d.baseImponible)}</td>
                <td class="text-end">${formatearPorcentaje(d.tipoImpositivo)}</td>
                <td class="text-end">${formatearImporte(d.cuotaRepercutida)}</td>
            </tr>
        `).join('');
    }

    return `
        ${marcaAgua}
        <header class="cabecera-factura d-flex justify-content-between align-items-start gap-4">
            <div class="d-flex align-items-center gap-3">
                <img src="/emisor/logo" alt="Logo emisor" class="logo-emisor" style="height: 100px; width: auto; max-height: 100px;" onerror="this.style.display='none'">
                <div>
                    <h1 class="h4 mb-0 text-primary">Facturación 360</h1>
                    <p class="mb-0 text-muted small">Documento de factura</p>
                </div>
            </div>
            <div class="d-flex align-items-start gap-4 ms-auto">
                <div class="text-end">
                    <h2 class="h5 mb-1">${f.numeroFactura}</h2>
                    <p class="mb-0 small">Fecha: ${formatearFecha(f.fechaEmision)}</p>
                    <p class="mb-0 small text-muted">Estado: ${f.estado}</p>
                </div>
                ${qr}
            </div>
        </header>

        <div class="datos-partes mb-4">
            <div class="row">
                <div class="col-6 border-end">
                    <section>
                        <h2 class="h6 fw-bold border-bottom pb-2 mb-2">Emisor</h2>
                        <p class="fw-bold mb-1">${emisor.nombre || "—"}</p>
                        <p class="mb-1">NIF/CIF: ${emisor.cif || emisor.nifCif || "—"}</p>
                        <p class="mb-1">${emisor.direccion || "—"}</p>
                        <p class="mb-0">${[emisor.telefono, emisor.email].filter(Boolean).join(" · ") || "Sin contacto"}</p>
                    </section>
                </div>
                <div class="col-6">
                    <section class="ps-2">
                        <h2 class="h6 fw-bold border-bottom pb-2 mb-2">Cliente</h2>
                        <p class="fw-bold mb-1">${c.nombre}</p>
                        <p class="mb-1">NIF/CIF: ${c.nifCif}</p>
                        <p class="mb-1">${formarDireccion(c)}</p>
                        <p class="mb-0">${formarContacto(c)}</p>
                    </section>
                </div>
            </div>
        </div>

        <section>
            <h2 class="h5">Conceptos</h2>
            <div class="table-responsive">
                <table class="table table-bordered align-middle">
                    <thead>
                        <tr>
                            <th scope="col">Descripción</th>
                            <th scope="col" class="text-end">Cantidad</th>
                            <th scope="col" class="text-end">Precio</th>
                            <th scope="col" class="text-end">Dto.</th>
                            <th scope="col" class="text-end">Base</th>
                            <th scope="col" class="text-end">IVA</th>
                            <th scope="col" class="text-end">Total</th>
                        </tr>
                    </thead>
                    <tbody>${conceptosHtml}</tbody>
                </table>
            </div>
        </section>

        <div class="d-flex justify-content-between align-items-start mt-4 mb-4 gap-4 flex-wrap flex-sm-nowrap fila-desglose-totales">
            <section class="desglose-factura flex-grow-1 ${desglose.length > 0 ? '' : 'd-none'}">
                <h2 class="h6">Desglose del IVA</h2>
                <table class="table table-sm tabla-desglose">
                    <thead>
                        <tr>
                            <th scope="col" class="text-end">Base imponible</th>
                            <th scope="col" class="text-end">Tipo</th>
                            <th scope="col" class="text-end">Cuota</th>
                        </tr>
                    </thead>
                    <tbody>${desgloseHtml}</tbody>
                </table>
            </section>
            <div class="totales-factura flex-shrink-0" style="width: 320px; max-width: 100%;">
                <div><span>Subtotal</span><strong>${formatearImporte(f.subtotal)}</strong></div>
                <div><span>IVA</span><strong>${formatearImporte(f.importeIva)}</strong></div>
                <div class="total-final"><span>Total</span><strong>${formatearImporte(f.total)}</strong></div>
            </div>
        </div>

        ${f.observaciones && f.observaciones.trim() ? `
        <section class="observaciones-factura">
            <h2 class="h5">Observaciones</h2>
            <p class="mb-0">${f.observaciones}</p>
        </section>` : ''}
    `;
}

/**
 * Crea un <div> con la misma estructura que el visor pero con los datos
 * de una factura concreta, listo para imprimir.
 */
function crearClonParaImpresion(detalle) {
    const contenedor = document.createElement("div");
    contenedor.className = "documento-factura factura-lote";
    
    // Todas las hojas clonadas heredan la clase de hoja-estrecha si aplica
    const hojaEstrecha = documentoFactura.classList.contains("hoja-estrecha");
    if (hojaEstrecha) {
        contenedor.classList.add("hoja-estrecha");
    }

    contenedor.innerHTML = construirHtmlFactura(detalle);
    return contenedor;
}

/**
 * Imprime en lote todas las facturas de la cola.
 */
async function imprimirEnLote() {
    botonImprimirTodas.disabled = true;
    const textoOriginal = botonImprimirTodas.innerHTML;
    botonImprimirTodas.innerHTML = '<span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span> Preparando…';

    const ids = Array.from(colaImpresion.keys());
    const peticiones = ids.map(id =>
        fetch("/factura/" + id + "/detalle").then(r => r.ok ? r.json() : null)
    );

    const resultados = await Promise.allSettled(peticiones);
    const detalles = resultados
        .filter(r => r.status === "fulfilled" && r.value !== null)
        .map(r => r.value);

    if (detalles.length === 0) {
        botonImprimirTodas.innerHTML = textoOriginal;
        botonImprimirTodas.disabled = false;
        fijar("No se pudieron cargar los datos para imprimir.", { esError: true });
        return;
    }

    // Ocultar el visor original y la sidebar durante la impresión.
    documentoFactura.classList.add("d-none");
    sidebarCola.classList.add("d-none");

    const clones = [];
    for (const detalle of detalles) {
        const clon = crearClonParaImpresion(detalle);
        document.body.appendChild(clon);
        clones.push(clon);
    }

    // Pequeña espera para que el navegador renderice los clones
    await new Promise(resolve => requestAnimationFrame(resolve));

    window.print();

    // Restaurar: quitar clones, volver a mostrar el visor.
    for (const clon of clones) {
        clon.remove();
    }
    
    // Como el documentoFactura vuelve a mostrarse, necesitamos repaginarlo
    documentoFactura.classList.remove("d-none");
    sidebarCola.classList.remove("d-none");
    botonImprimirTodas.innerHTML = textoOriginal;
    botonImprimirTodas.disabled = false;
    
    // Repaginar el documento principal
    programarPaginar();
}

botonImprimirTodas.addEventListener("click", imprimirEnLote);