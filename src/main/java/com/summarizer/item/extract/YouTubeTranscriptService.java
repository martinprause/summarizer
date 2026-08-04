package com.summarizer.item.extract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube-Transkripte über yt-dlp: erst vorhandene Untertitel (manuell oder
 * automatisch, schnell), sonst Audio herunterladen und per Whisper transkribieren.
 * yt-dlp fehlt? Dann Optional.empty — die Pipeline behandelt das Video wie
 * eine normale Webseite.
 */
@Service
public class YouTubeTranscriptService {

    private static final Logger log = LoggerFactory.getLogger(YouTubeTranscriptService.class);
    private static final Pattern VIDEO_ID = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?[^#]*v=|shorts/|live/|embed/)|youtu\\.be/)([A-Za-z0-9_-]{6,20})");
    private static final Duration SUBS_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration AUDIO_TIMEOUT = Duration.ofMinutes(10);

    private final String ytdlpPath;
    private final com.summarizer.ai.WhisperClient whisper;
    private final Path filesDir;
    private volatile Boolean available;

    public YouTubeTranscriptService(@Value("${summarizer.ytdlp.path:yt-dlp}") String ytdlpPath,
                                    com.summarizer.ai.WhisperClient whisper,
                                    @Value("${summarizer.files.dir}") String filesDir) {
        this.ytdlpPath = ytdlpPath;
        this.whisper = whisper;
        this.filesDir = Path.of(filesDir);
    }

    public static boolean isYoutubeUrl(String url) {
        return url != null && VIDEO_ID.matcher(url).find();
    }

    public static Optional<String> videoId(String url) {
        if (url == null) {
            return Optional.empty();
        }
        Matcher matcher = VIDEO_ID.matcher(url);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public static String thumbnailUrl(String videoId) {
        return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }

    public record Transcript(String title, String text, String source) {
    }

    /** Titel + Transkript. source = "untertitel" oder "whisper". */
    public Optional<Transcript> fetch(String url) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        try {
            Path workDir = Files.createTempDirectory(filesDir, "yt-");
            try {
                Optional<Transcript> subs = fromSubtitles(url, workDir);
                if (subs.isPresent()) {
                    return subs;
                }
                return fromAudio(url, workDir);
            } finally {
                cleanup(workDir);
            }
        } catch (Exception e) {
            log.warn("YouTube-Transkript für {} fehlgeschlagen: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    /** Untertitel bevorzugt: deutsch, dann englisch, dann automatisch generierte. */
    private Optional<Transcript> fromSubtitles(String url, Path workDir) throws Exception {
        // --print aktiviert sonst den Simulate-Modus und unterdrückt die Untertitel-Dateien
        Run run = run(workDir, SUBS_TIMEOUT,
                ytdlpPath, "--skip-download", "--no-simulate",
                "--write-subs", "--write-auto-subs",
                "--sub-langs", "de,de-orig,en,en-orig",
                "--sub-format", "vtt",
                "--print", "title",
                "--no-playlist",
                "-o", "%(id)s",
                url);
        String title = run.stdout().strip().lines().findFirst().orElse("").strip();
        try (var files = Files.list(workDir)) {
            Optional<Path> vtt = files
                    .filter(p -> p.getFileName().toString().endsWith(".vtt"))
                    .sorted((a, b) -> subtitlePriority(a) - subtitlePriority(b))
                    .findFirst();
            if (vtt.isEmpty()) {
                return Optional.empty();
            }
            String text = parseVtt(Files.readString(vtt.get()));
            if (text.length() < 40) {
                return Optional.empty();
            }
            return Optional.of(new Transcript(title, text, "untertitel"));
        }
    }

    private int subtitlePriority(Path path) {
        String name = path.getFileName().toString();
        if (name.contains(".de")) {
            return 0;
        }
        return name.contains(".en") ? 1 : 2;
    }

    /** Fallback: Audio (max. 60 MB) laden und mit Whisper transkribieren. */
    private Optional<Transcript> fromAudio(String url, Path workDir) throws Exception {
        Run titleRun = run(workDir, SUBS_TIMEOUT,
                ytdlpPath, "--skip-download", "--print", "title", "--no-playlist", url);
        String title = titleRun.stdout().strip().lines().findFirst().orElse("").strip();

        run(workDir, AUDIO_TIMEOUT,
                ytdlpPath, "-f", "bestaudio",
                "--max-filesize", "60M",
                "--extract-audio", "--audio-format", "mp3", "--audio-quality", "5",
                "--no-playlist",
                "-o", "audio.%(ext)s",
                url);
        Path audio = workDir.resolve("audio.mp3");
        if (!Files.exists(audio)) {
            return Optional.empty();
        }
        return whisper.transcribe(audio)
                .filter(text -> !text.isBlank())
                .map(text -> new Transcript(title, text, "whisper"));
    }

    /** VTT → Fließtext: Kopf, Zeitmarken, Cue-Tags und Wiederholungen entfernen. */
    static String parseVtt(String vtt) {
        List<String> lines = new ArrayList<>();
        String previous = "";
        for (String raw : vtt.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("WEBVTT") || line.startsWith("Kind:")
                    || line.startsWith("Language:") || line.contains("-->")
                    || line.matches("^\\d+$")) {
                continue;
            }
            line = line.replaceAll("<[^>]+>", "").strip();
            // Auto-Untertitel wiederholen Zeilen rollierend — Duplikate raus
            if (line.isEmpty() || line.equals(previous)) {
                continue;
            }
            lines.add(line);
            previous = line;
        }
        return String.join(" ", lines).replaceAll("\\s+", " ").strip();
    }

    /** yt-dlp vorhanden? Wird nur einmal geprüft und dann gecacht. */
    public boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        try {
            Run run = run(filesDir, Duration.ofSeconds(15), ytdlpPath, "--version");
            available = run.exitCode() == 0;
            log.info("yt-dlp {}: Version {}", available ? "gefunden" : "NICHT nutzbar",
                    run.stdout().strip());
        } catch (Exception e) {
            available = false;
            log.info("yt-dlp nicht verfügbar ({}) — YouTube-Videos werden wie Webseiten behandelt",
                    e.getMessage());
        }
        return available;
    }

    private record Run(int exitCode, String stdout) {
    }

    private Run run(Path workDir, Duration timeout, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(false);
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        if (!process.waitFor(timeout.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("yt-dlp Timeout nach " + timeout.toSeconds() + "s");
        }
        return new Run(process.exitValue(), stdout);
    }

    private void cleanup(Path workDir) {
        try (var files = Files.list(workDir)) {
            files.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
            Files.deleteIfExists(workDir);
        } catch (Exception ignored) {
        }
    }
}
