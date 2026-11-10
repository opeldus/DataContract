package de.opeldus.datacontract.etl;

import de.opeldus.datacontract.etl.EtlModel.Job;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Reads DSX files and ISX packages. An ISX is a ZIP whose XML follows an undocumented, repository-like structure; such
 * entries are reported with their root element instead of being interpreted by guesswork.
 */
public final class EtlImporter {

    static final int MAX_ENTRIES = 50_000;
    static final long MAX_ENTRY_BYTES = 100L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 500L * 1024 * 1024;
    private static final Pattern ROOT_ELEMENT = Pattern.compile("<([A-Za-z_][\\w:.-]*)");

    private EtlImporter() {}

    public record UnreadableEntry(String path, String content) {}

    public record Result(List<Job> jobs, List<UnreadableEntry> unreadable, List<String> warnings) {}

    public static class TooLargeException extends RuntimeException {
        TooLargeException(String message) {
            super(message);
        }
    }

    public static Result read(byte[] data, String fileName) {
        var jobs = new ArrayList<Job>();
        var unreadable = new ArrayList<UnreadableEntry>();
        var warnings = new ArrayList<String>();
        if (isZip(data)) {
            readZip(data, jobs, unreadable, warnings);
        } else {
            readEntry(fileName, data, jobs, unreadable, warnings);
        }
        return new Result(jobs, unreadable, warnings);
    }

    private static boolean isZip(byte[] data) {
        return data.length > 3 && data[0] == 'P' && data[1] == 'K';
    }

    private static void readZip(byte[] data, List<Job> jobs, List<UnreadableEntry> unreadable, List<String> warnings) {
        var entries = 0;
        var total = 0L;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (++entries > MAX_ENTRIES) {
                    throw new TooLargeException("Das Paket enthält mehr als " + MAX_ENTRIES + " Dateien.");
                }
                var content = new ByteArrayOutputStream();
                var buffer = new byte[8192];
                for (var read = zip.read(buffer); read > 0; read = zip.read(buffer)) {
                    total += read;
                    if (content.size() + read > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                        throw new TooLargeException("Das Paket ist entpackt zu groß.");
                    }
                    content.write(buffer, 0, read);
                }
                // Entry names are only labels here: nothing is ever written to disk.
                readEntry(entry.getName(), content.toByteArray(), jobs, unreadable, warnings);
            }
        } catch (ZipException exception) {
            throw new DsxParser.DsxFormatException("Das Paket ist kein gültiges ZIP-Archiv.");
        } catch (IOException exception) {
            throw new DsxParser.DsxFormatException("Das Paket konnte nicht gelesen werden.");
        }
    }

    private static void readEntry(String path, byte[] data, List<Job> jobs, List<UnreadableEntry> unreadable, List<String> warnings) {
        var text = decode(data);
        if (!DsxParser.looksLikeDsx(text)) {
            unreadable.add(new UnreadableEntry(path, describe(text)));
            return;
        }
        try {
            for (var block : DsxParser.parse(text)) {
                switch (block.type()) {
                    case "DSJOB" -> jobs.add(JobAssembler.assemble(block));
                    case "DSSHAREDCONTAINER" -> warnings.add("Shared Container " + block.property("Identifier") + " wird nicht aufgelöst.");
                    default -> { }
                }
            }
        } catch (DsxParser.DsxFormatException exception) {
            warnings.add(path + ": " + exception.getMessage());
        }
    }

    /** Names only the root element, because the rest can hold connection details. */
    private static String describe(String text) {
        var body = text.stripLeading();
        if (!body.startsWith("<")) {
            return "kein DSX und kein XML";
        }
        var matcher = ROOT_ELEMENT.matcher(body.replaceFirst("(?s)^<\\?.*?\\?>", "").replaceAll("(?s)<!--.*?-->", ""));
        return matcher.find() ? "XML mit Wurzelelement " + matcher.group(1) : "XML";
    }

    /** DSX files carry the code page of the engine, usually not UTF-8. */
    private static String decode(byte[] data) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException exception) {
            return new String(data, Charset.forName("windows-1252"));
        }
    }
}
