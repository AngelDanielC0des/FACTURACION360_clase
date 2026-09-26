/*
 * Prueba de humo de rendimiento contra la preproducción.
 *
 * QUÉ ES Y QUÉ NO ES. Esto no es un test de carga: son cinco usuarios virtuales
 * durante treinta segundos, lo justo para detectar que algo se ha degradado de
 * forma evidente. El App Service de preproducción tiene una cuota diaria de CPU
 * en plan gratuito, y agotarla deja la aplicación devolviendo 403 el resto del
 * día: una prueba de carga de verdad tumbaría el entorno que queremos vigilar.
 *
 * SOLO LECTURAS, A PROPÓSITO. No hay ningún POST. Dar de alta facturas en bucle
 * no deja basura que se pueda barrer después: CONSUME LA NUMERACIÓN. Cada
 * ejecución se comería números de una serie de facturación, y un salto en la
 * numeración de una factura no es un dato de prueba, es un problema.
 */

import http from "k6/http";
import { check, group, sleep } from "k6";

const BASE = __ENV.BASE_URL;

export const options = {
    vus: 5,
    duration: "30s",

    /*
     * UMBRALES, SACADOS DE CUATRO MEDICIONES REALES.
     *
     * Esta prueba se ejecutó cuatro veces seguidas contra la preproducción
     * antes de subirla. Los p(95) obtenidos, sin tocar nada entre ejecuciones:
     *
     *     global          168 · 281 · 392 · 544 ms
     *     listar-pagina   311 · 590 · 750 ms
     *
     * Es decir, MÁS DE 3× de variación entre ejecuciones idénticas. No es que
     * la aplicación cambie: es un App Service pequeño compartiendo máquina.
     * Ese ruido es el dato que manda aquí, y tiene dos consecuencias:
     *
     * 1. Los umbrales se fijan sobre el PEOR caso medido y con margen, no
     *    sobre la media. Con un umbral ajustado a la media, el job fallaría
     *    la mitad de las veces sin que nadie hubiera roto nada, y un job que
     *    cría lobos se acaba ignorando.
     * 2. Esto detecta degradaciones GRANDES —una consulta N+1 nueva, un índice
     *    que se cae— pero NO un 30 % de bajada: se pierde en el ruido. Para
     *    medir eso haría falta un entorno dedicado, que hoy no hay.
     *
     * Además, las cifras de arriba se midieron desde España contra Spain
     * Central; el runner de GitHub añade su propia latencia. Con la primera
     * ejecución en CI se puede revisar si estos números siguen valiendo.
     *
     * El 5 % de errores es por lo mismo: con unas 500 peticiones, exigir menos
     * del 1 % convierte cinco fallos sueltos en un job rojo.
     */
    thresholds: {
        http_req_duration: ["p(95)<1200"],
        http_req_failed: ["rate<0.05"],

        // Declarar el umbral por etiqueta tiene un segundo efecto útil: k6
        // incluye esa métrica desglosada en el resumen exportado, así que el
        // artefacto enseña cómo va este endpoint por separado y no solo el
        // agregado, que es donde se esconde la regresión de una consulta.
        // Es el único que pagina contra la base de datos, y el más lento.
        "http_req_duration{endpoint:listar-pagina}": ["p(95)<1500"],
    },
};

/** Pide una URL y comprueba que responde 200. */
function pedir(nombre, ruta) {
    const respuesta = http.get(`${BASE}${ruta}`, {
        // La etiqueta agrupa las métricas por endpoint en el resumen; sin ella
        // solo se ve el agregado y no se sabe cuál de los cinco se ha degradado.
        tags: { endpoint: nombre },
    });

    check(respuesta, {
        [`${nombre} responde 200`]: (r) => r.status === 200,
    });

    return respuesta;
}

export default function () {
    // Se recorren las tres pantallas como las recorrería una persona: la página
    // y después la llamada que esa página hace al cargarse.
    group("inicio", () => {
        pedir("index", "/index.html");
    });

    group("clientes", () => {
        pedir("clientes.html", "/clientes.html");
        // Los nombres de los parámetros salen de CriteriosCliente.java, que
        // declara «pagina» y «tamano». Con otros nombres Spring enlazaría null
        // y la respuesta seguiría siendo 200, así que el test pasaría midiendo
        // una consulta que no es la que hace la pantalla.
        pedir("listar-pagina", "/cliente/listar-pagina?pagina=0&tamano=10");
    });

    group("facturas", () => {
        pedir("facturas.html", "/facturas.html");
        pedir("buscar-facturas", "/factura/buscar?busqueda=");
    });

    // Un segundo entre iteraciones para parecerse a una persona leyendo la
    // pantalla, en vez de martillear el servidor.
    sleep(1);
}
