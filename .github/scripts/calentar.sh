#!/usr/bin/env bash
#
# Despierta la aplicación de preproducción antes de medirla.
#
# POR QUÉ EXISTE ESTO. El App Service duerme la aplicación cuando lleva un rato
# sin tráfico, y la primera petición después paga el arranque de Spring más la
# primera conexión a la base de datos. Medido contra el entorno real: 5,2 s en
# frío frente a 74-113 ms en caliente, para el MISMO endpoint. Sin este paso,
# cualquier umbral razonable falla por el arranque y no por una regresión, y un
# job que falla por algo que no es culpa de nadie se acaba ignorando.
#
# Se pide /emisor y no una página estática a propósito: las estáticas las sirve
# el contenedor sin tocar Spring, así que responderían rápido con la aplicación
# todavía dormida. /emisor obliga a levantar el contexto y a ir a la base de
# datos, que es lo que de verdad tarda.
#
# Uso: calentar.sh <url-base>

set -euo pipefail

BASE="${1:?Falta la URL base}"
INTENTOS=12          # 12 x 5 s = un minuto de margen
UMBRAL_MS=500        # por debajo de esto damos la aplicación por despierta

echo "Despertando $BASE"

for intento in $(seq 1 "$INTENTOS"); do
    # %{time_total} llega en segundos con decimales; se pasa a milisegundos
    # enteros para poder compararlo en bash, que no sabe de coma flotante.
    segundos=$(curl -s -o /dev/null -w '%{time_total}' --max-time 60 "$BASE/emisor" || echo 99)
    codigo=$(curl -s -o /dev/null -w '%{http_code}' --max-time 60 "$BASE/emisor" || echo 000)
    ms=$(awk -v s="$segundos" 'BEGIN { printf "%d", s * 1000 }')

    echo "  intento $intento: HTTP $codigo en ${ms} ms"

    # Un 403 en un App Service gratuito significa cuota diaria de CPU agotada.
    # No es algo que arregle esperar, así que se para y se dice por qué.
    if [ "$codigo" = "403" ]; then
        echo "::error::La aplicación devuelve 403. En plan gratuito eso suele ser la cuota diaria de CPU agotada; no tiene sentido medir hoy."
        exit 1
    fi

    if [ "$codigo" = "200" ] && [ "$ms" -lt "$UMBRAL_MS" ]; then
        echo "Despierta y respondiendo en ${ms} ms. Se puede medir."
        exit 0
    fi

    sleep 5
done

echo "::error::La aplicación no ha bajado de ${UMBRAL_MS} ms en $INTENTOS intentos. Medir ahora daría números del arranque, no del rendimiento."
exit 1
