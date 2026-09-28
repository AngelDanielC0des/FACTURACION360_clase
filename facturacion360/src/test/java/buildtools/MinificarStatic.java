package buildtools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.Result;
import com.google.javascript.jscomp.SourceFile;
import com.googlecode.htmlcompressor.compressor.HtmlCompressor;
import com.helger.css.decl.CascadingStyleSheet;
import com.helger.css.reader.CSSReader;
import com.helger.css.writer.CSSWriter;
import com.helger.css.writer.CSSWriterSettings;

/**
 * Minifica la copia de static que Maven incluirá en el JAR.
 * No modifica src/main/resources/static.
 */
public final class MinificarStatic {

    private static final Pattern SCRIPTS_HTML = Pattern.compile(
            "(?is)<script\\b[^>]*>.*?</script\\s*>");

    private MinificarStatic() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "Se esperaba la ruta de target/classes/static");
        }

        Path directorio = Path.of(args[0]);

        if (!Files.isDirectory(directorio)) {
            throw new IllegalStateException(
                    "No existe el directorio: " + directorio);
        }

        List<Path> archivos;

        try (Stream<Path> recorrido = Files.walk(directorio)) {
            archivos = recorrido
                    .filter(Files::isRegularFile)
                    .filter(MinificarStatic::esArchivoMinificable)
                    .sorted()
                    .toList();
        }

        // Leer todos los originales antes de escribir resultados.
        Map<Path, String> originales = new LinkedHashMap<>();
        Map<Path, String> resultados = new LinkedHashMap<>();

        for (Path archivo : archivos) {
            originales.put(
                    archivo,
                    Files.readString(archivo, StandardCharsets.UTF_8));
        }

        for (Path archivo : archivos) {
            String nombre = archivo.getFileName().toString()
                    .toLowerCase(Locale.ROOT);
            String original = originales.get(archivo);

            try {
                if (nombre.endsWith(".html")) {
                    resultados.put(
                            archivo,
                            minificarHtml(original, archivo));
                } else if (nombre.endsWith(".css")) {
                    resultados.put(
                            archivo,
                            minificarCss(original, archivo));
                }
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Falló la minificación de " + archivo, e);
            }
        }

        // Closure recibe todos los JS para resolver los import entre archivos.
        resultados.putAll(minificarJs(directorio, originales));

        for (Path archivo : archivos) {
            String original = originales.get(archivo);
            String resultado = resultados.get(archivo);

            if (resultado == null || resultado.isBlank()) {
                throw new IllegalStateException(
                        "La minificación produjo un archivo vacío: "
                                + archivo);
            }
        }

        int procesados = 0;

        for (Path archivo : archivos) {
            String original = originales.get(archivo);
            String resultado = resultados.get(archivo);

            Files.writeString(
                    archivo,
                    resultado,
                    StandardCharsets.UTF_8);

            procesados++;

            System.out.printf(
                    "%s: %d -> %d caracteres%n",
                    directorio.relativize(archivo),
                    original.length(),
                    resultado.length());
        }

        System.out.println("Archivos minificados: " + procesados);
    }

    private static boolean esArchivoMinificable(Path archivo) {
        String nombre = archivo.getFileName().toString()
                .toLowerCase(Locale.ROOT);

        return nombre.endsWith(".html")
                || nombre.endsWith(".css")
                || nombre.endsWith(".js");
    }

    private static String minificarHtml(
            String original,
            Path archivo) {

        HtmlCompressor compresor = new HtmlCompressor();

        compresor.setRemoveComments(true);
        compresor.setRemoveMultiSpaces(true);
        compresor.setCompressJavaScript(false);
        compresor.setCompressCss(false);
        compresor.setRemoveIntertagSpaces(false);
        compresor.setRemoveQuotes(false);

        // Proteger cada bloque <script> completo, incluido su contenido.
        Matcher matcher = SCRIPTS_HTML.matcher(original);
        StringBuilder htmlProtegido = new StringBuilder();
        List<String> bloques = new ArrayList<>();
        List<String> marcadores = new ArrayList<>();

        String prefijo = "ZZZBLOQUESCRIPT"
                + UUID.randomUUID().toString().replace("-", "")
                + "NUM";

        while (matcher.find()) {
            String marcador = prefijo + bloques.size() + "ZZZ";

            bloques.add(matcher.group());
            marcadores.add(marcador);

            matcher.appendReplacement(
                    htmlProtegido,
                    Matcher.quoteReplacement(marcador));
        }

        matcher.appendTail(htmlProtegido);

        String resultado = compresor.compress(
                htmlProtegido.toString());

        // Restaurar literalmente los bloques <script> originales.
        for (int i = 0; i < bloques.size(); i++) {
            String marcador = marcadores.get(i);
            int posicion = resultado.indexOf(marcador);

            if (posicion < 0
                    || resultado.indexOf(
                            marcador,
                            posicion + marcador.length()) >= 0) {
                throw new IllegalStateException(
                        "No se pudo restaurar un <script> de "
                                + archivo);
            }

            resultado = resultado.replace(
                    marcador,
                    bloques.get(i));
        }

        return resultado;
    }

    private static String minificarCss(
            String original,
            Path archivo) {

        CascadingStyleSheet hoja = CSSReader.readFromString(original);

        if (hoja == null) {
            throw new IllegalStateException(
                    "No se pudo analizar el CSS de " + archivo);
        }

        CSSWriterSettings ajustes = new CSSWriterSettings();
        ajustes.setOptimizedOutput(true);

        String resultado = new CSSWriter(ajustes)
                .getCSSAsString(hoja);

        if (resultado == null) {
            throw new IllegalStateException(
                    "No se pudo generar el CSS de " + archivo);
        }

        return resultado;
    }

    private static Map<Path, String> minificarJs(
            Path directorio,
            Map<Path, String> originales) {

        Map<Path, String> nombres = new LinkedHashMap<>();
        List<SourceFile> fuentes = new ArrayList<>();

        for (Map.Entry<Path, String> entrada : originales.entrySet()) {
            Path archivo = entrada.getKey();

            if (!archivo.getFileName().toString()
                    .toLowerCase(Locale.ROOT)
                    .endsWith(".js")) {
                continue;
            }

            // Las rutas de los módulos deben usar / también en Windows.
            String nombre = directorio.relativize(archivo)
                    .toString()
                    .replace('\\', '/');

            nombres.put(archivo, nombre);
            fuentes.add(
                    SourceFile.fromCode(
                            nombre,
                            entrada.getValue()));
        }

        if (fuentes.isEmpty()) {
            return Map.of();
        }

        CompilerOptions ajustes = new CompilerOptions();

        CompilationLevel.WHITESPACE_ONLY
                .setOptionsForCompilationLevel(ajustes);

        ajustes.setLanguage(
                CompilerOptions.LanguageMode.ECMASCRIPT_NEXT);

        // Conservar import/export y las rutas originales.
        ajustes.setEs6ModuleTranspilation(
                CompilerOptions.Es6ModuleTranspilation.NONE);

        com.google.javascript.jscomp.Compiler compilador =
                new com.google.javascript.jscomp.Compiler();

        Result compilacion = compilador.compile(
                List.of(),
                fuentes,
                ajustes);

        if (!compilacion.success) {
            throw new IllegalStateException(
                    "Closure Compiler rechazó los JS de "
                            + directorio
                            + ". Errores: "
                            + compilador.getErrors());
        }

        Map<Path, String> resultados = new LinkedHashMap<>();

        for (Map.Entry<Path, String> entrada : nombres.entrySet()) {
            com.google.javascript.rhino.Node modulo =
                    compilador.getScriptNode(entrada.getValue());

            if (modulo == null) {
                throw new IllegalStateException(
                        "Closure no devolvió el módulo "
                                + entrada.getValue());
            }

            resultados.put(
                    entrada.getKey(),
                    compilador.toSource(modulo));
        }

        return resultados;
    }
}