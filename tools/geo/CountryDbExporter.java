package tools.geo;

import com.maxmind.db.DatabaseRecord;
import com.maxmind.db.Network;
import com.maxmind.db.Networks;
import com.maxmind.db.Reader;

import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CountryDbExporter {
    private static final byte[] MAGIC = new byte[] {'Y', 'G', 'C', '1'};

    private record Range(long start, long end, String isoCode) {}

    private record CountryNames(String isoCode, String english, String russian, String japanese) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: CountryDbExporter <source.mmdb> <ranges.db> <names.tsv>");
            System.exit(1);
        }

        File source = new File(args[0]);
        File rangesOutput = new File(args[1]);
        File namesOutput = new File(args[2]);

        rangesOutput.getParentFile().mkdirs();
        namesOutput.getParentFile().mkdirs();

        List<Range> ranges = new ArrayList<>();
        Map<String, CountryNames> namesByIso = new LinkedHashMap<>();

        try (Reader reader = new Reader(source)) {
            Networks<Map> networks = reader.networks(Map.class);
            while (networks.hasNext()) {
                DatabaseRecord<Map> record = networks.next();
                Network network = record.network();
                InetAddress address = network.networkAddress();
                if (!(address instanceof Inet4Address)) {
                    continue;
                }

                Map<String, Object> data = record.data();
                Map<String, Object> country = nestedMap(data, "country");
                if (country == null) {
                    country = nestedMap(data, "registered_country");
                }
                if (country == null) {
                    continue;
                }

                String isoCode = upperIso(country.get("iso_code"));
                if (isoCode == null) {
                    continue;
                }

                long start = ipv4ToLong(address);
                long end = rangeEnd(start, network.prefixLength());
                mergeOrAppend(ranges, start, end, isoCode);

                Map<String, Object> localizedNames = nestedMap(country, "names");
                String english = localizedName(localizedNames, "en", isoCode);
                String russian = localizedName(localizedNames, "ru", english);
                String japanese = localizedName(localizedNames, "ja", english);
                namesByIso.putIfAbsent(isoCode, new CountryNames(isoCode, english, russian, japanese));
            }
        }

        writeRanges(rangesOutput, ranges);
        writeNames(namesOutput, namesByIso);
        System.out.printf(Locale.ROOT, "Exported %d IPv4 ranges and %d countries.%n", ranges.size(), namesByIso.size());
    }

    private static void mergeOrAppend(List<Range> ranges, long start, long end, String isoCode) {
        if (!ranges.isEmpty()) {
            Range previous = ranges.get(ranges.size() - 1);
            if (previous.isoCode.equals(isoCode) && previous.end + 1 == start) {
                ranges.set(ranges.size() - 1, new Range(previous.start, end, isoCode));
                return;
            }
        }
        ranges.add(new Range(start, end, isoCode));
    }

    private static void writeRanges(File output, List<Range> ranges) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(output))) {
            out.write(MAGIC);
            out.writeInt(ranges.size());
            for (Range range : ranges) {
                out.writeInt((int) range.start);
                out.writeInt((int) range.end);
                out.writeByte(range.isoCode.charAt(0));
                out.writeByte(range.isoCode.charAt(1));
            }
        }
    }

    private static void writeNames(File output, Map<String, CountryNames> namesByIso) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(output), StandardCharsets.UTF_8))) {
            for (CountryNames names : namesByIso.values()) {
                writer.write(escape(names.isoCode));
                writer.write('\t');
                writer.write(escape(names.english));
                writer.write('\t');
                writer.write(escape(names.russian));
                writer.write('\t');
                writer.write(escape(names.japanese));
                writer.newLine();
            }
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\t", " ").replace("\n", " ");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        if (value instanceof Map<?, ?> nested) {
            return (Map<String, Object>) nested;
        }
        return null;
    }

    private static String localizedName(Map<String, Object> names, String key, String fallback) {
        if (names == null) {
            return fallback;
        }
        Object value = names.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return fallback;
    }

    private static String upperIso(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        return normalized.length() == 2 ? normalized : null;
    }

    private static long ipv4ToLong(InetAddress address) {
        byte[] bytes = address.getAddress();
        return ((long) bytes[0] & 0xFF) << 24
            | ((long) bytes[1] & 0xFF) << 16
            | ((long) bytes[2] & 0xFF) << 8
            | ((long) bytes[3] & 0xFF);
    }

    private static long rangeEnd(long start, int prefixLength) {
        long hostCount = 1L << (32 - prefixLength);
        return start + hostCount - 1L;
    }
}
