#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOOLS_DIR="$ROOT_DIR/tools/geo"
CACHE_DIR="$ROOT_DIR/.tools-cache"
JAR_PATH="$CACHE_DIR/maxmind-db-4.0.2.jar"
SOURCE_DB="${1:-$HOME/GeoLiteCountry.mmdb}"
RANGES_OUT="$ROOT_DIR/app/src/main/assets/geoip_country_ipv4.db"
NAMES_OUT="$ROOT_DIR/app/src/main/assets/geoip_country_names.tsv"

mkdir -p "$CACHE_DIR"

if [[ ! -f "$SOURCE_DB" ]]; then
  printf 'Source database not found: %s\n' "$SOURCE_DB" >&2
  exit 1
fi

if [[ ! -f "$JAR_PATH" ]]; then
  printf 'Downloading MaxMind DB reader jar...\n'
  curl -L --fail -o "$JAR_PATH" \
    https://repo1.maven.org/maven2/com/maxmind/db/maxmind-db/4.0.2/maxmind-db-4.0.2.jar
fi

printf 'Compiling country exporter...\n'
javac -d "$CACHE_DIR" -cp "$JAR_PATH" "$TOOLS_DIR/CountryDbExporter.java"

printf 'Generating Android country assets...\n'
java -cp "$CACHE_DIR:$JAR_PATH" tools.geo.CountryDbExporter "$SOURCE_DB" "$RANGES_OUT" "$NAMES_OUT"

printf 'Wrote:\n'
printf '  %s\n' "$RANGES_OUT"
printf '  %s\n' "$NAMES_OUT"
