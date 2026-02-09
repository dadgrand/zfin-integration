import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class ZfinBridge {
    private static final DateTimeFormatter LOG_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ARCHIVE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final Logger LOGGER = Logger.getLogger("ZfinBridge");
    private static volatile boolean loggingConfigured = false;
    private static FileChannel lockChannel;
    private static FileLock processLock;

    private final Config config;
    private final Flow bankToZfin;
    private final Flow zfinToBank;
    private LocalDate lastRetentionCleanupDate;

    public static void main(String[] args) {
        try {
            CliArgs cliArgs = CliArgs.parse(args);
            Config config = Config.load(cliArgs.getConfigPath());

            configureLogging(config);
            acquireLock(config.getLockFile());

            ZfinBridge bridge = new ZfinBridge(config);
            bridge.initDirectories();

            logInfo("Config loaded from " + cliArgs.getConfigPath().toAbsolutePath());
            logInfo("Mode: " + (cliArgs.isRunOnce() ? "single pass" : "daemon"));

            if (cliArgs.isRunOnce()) {
                bridge.runCycle();
                return;
            }

            while (true) {
                bridge.runCycle();
                Thread.sleep(config.getPollIntervalSeconds() * 1000L);
            }
        } catch (IllegalArgumentException e) {
            bootstrap("Config/arguments error: " + e.getMessage());
            printUsage();
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logInfo("Interrupted, stopping.");
        } catch (Exception e) {
            logError("Fatal error", e);
            System.exit(1);
        } finally {
            releaseLock();
        }
    }

    private ZfinBridge(Config config) {
        this.config = config;
        this.bankToZfin = new Flow(
                "BANK->ZFIN",
                resolve(config.getBankRoot(), config.getBankOutDir()),
                normalizeExt(config.getBankToZfinSourceExt()),
                resolve(config.getZfinRoot(), config.getZfinInDir()),
                normalizeExt(config.getBankToZfinTargetExt()),
                resolve(config.getBankRoot(), config.getBankArchiveDir())
        );
        this.zfinToBank = new Flow(
                "ZFIN->BANK",
                resolve(config.getZfinRoot(), config.getZfinOutDir()),
                normalizeExt(config.getZfinToBankSourceExt()),
                resolve(config.getBankRoot(), config.getBankInDir()),
                normalizeExt(config.getZfinToBankTargetExt()),
                resolve(config.getZfinRoot(), config.getZfinArchiveDir())
        );
    }

    private static void configureLogging(Config config) throws IOException {
        if (loggingConfigured) {
            return;
        }

        Path logDir = config.getLogDir();
        if (Files.notExists(logDir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(logDir);
        }

        LOGGER.setUseParentHandlers(false);
        for (Handler handler : LOGGER.getHandlers()) {
            LOGGER.removeHandler(handler);
            try {
                handler.close();
            } catch (Exception ignored) {
                // no-op
            }
        }

        Formatter formatter = new SimpleLogFormatter();

        ConsoleHandler console = new ConsoleHandler();
        console.setFormatter(formatter);
        console.setLevel(Level.INFO);
        LOGGER.addHandler(console);

        int rotateBytes = Math.max(1024, config.getLogRotateBytes());
        int rotateFiles = Math.max(1, config.getLogRotateFiles());
        String pattern = logDir.resolve("zfin-bridge.%g.log").toString();

        FileHandler file = new FileHandler(pattern, rotateBytes, rotateFiles, true);
        file.setEncoding(StandardCharsets.UTF_8.name());
        file.setFormatter(formatter);
        file.setLevel(Level.ALL);
        LOGGER.addHandler(file);

        LOGGER.setLevel(Level.ALL);
        loggingConfigured = true;
    }

    private static void acquireLock(Path lockFile) throws IOException {
        Path parent = lockFile.getParent();
        if (parent != null && Files.notExists(parent, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(parent);
        }

        lockChannel = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        );

        try {
            processLock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            throw new IllegalStateException("Another instance is already running (lock file: " + lockFile + ")");
        }

        if (processLock == null) {
            throw new IllegalStateException("Another instance is already running (lock file: " + lockFile + ")");
        }

        long pid = -1L;
        try {
            pid = ProcessHandle.current().pid();
        } catch (Exception ignored) {
            // ProcessHandle should exist on Java 11, but keep fallback safe.
        }

        String lockInfo = "pid=" + pid + System.lineSeparator()
                + "started=" + LocalDateTime.now().format(LOG_TS) + System.lineSeparator();
        lockChannel.truncate(0);
        lockChannel.position(0);
        lockChannel.write(ByteBuffer.wrap(lockInfo.getBytes(StandardCharsets.UTF_8)));
        lockChannel.force(true);

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                releaseLock();
            }
        }, "zfin-bridge-lock-release"));
    }

    private static void releaseLock() {
        try {
            if (processLock != null && processLock.isValid()) {
                processLock.release();
            }
        } catch (Exception ignored) {
            // no-op
        } finally {
            processLock = null;
        }

        try {
            if (lockChannel != null && lockChannel.isOpen()) {
                lockChannel.close();
            }
        } catch (Exception ignored) {
            // no-op
        } finally {
            lockChannel = null;
        }
    }

    private static Path resolve(Path root, String child) {
        if (child == null || child.trim().isEmpty()) {
            return root;
        }
        return root.resolve(child);
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar zfin-bridge.jar [--config <path>] [--once]");
    }

    private static void bootstrap(String msg) {
        System.out.println(LocalDateTime.now().format(LOG_TS) + " " + msg);
    }

    private static void logInfo(String msg) {
        if (!loggingConfigured) {
            bootstrap(msg);
            return;
        }
        LOGGER.log(Level.INFO, msg);
    }

    private static void logWarn(String msg) {
        if (!loggingConfigured) {
            bootstrap("WARN " + msg);
            return;
        }
        LOGGER.log(Level.WARNING, msg);
    }

    private static void logError(String msg, Throwable error) {
        if (!loggingConfigured) {
            bootstrap("ERROR " + msg + ": " + error.getMessage());
            error.printStackTrace(System.err);
            return;
        }
        LOGGER.log(Level.SEVERE, msg, error);
    }

    private void initDirectories() throws IOException {
        List<Path> directories = new ArrayList<Path>();
        directories.add(bankToZfin.getSourceDir());
        directories.add(bankToZfin.getTargetDir());
        directories.add(bankToZfin.getArchiveRoot());
        directories.add(zfinToBank.getSourceDir());
        directories.add(zfinToBank.getTargetDir());
        directories.add(zfinToBank.getArchiveRoot());

        for (Path dir : directories) {
            if (Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(dir);
                logInfo("Created directory: " + dir);
            }
        }
    }

    private void runCycle() {
        processFlow(bankToZfin);
        processFlow(zfinToBank);
        cleanupArchivesIfNeeded();
    }

    private void processFlow(Flow flow) {
        try {
            List<Path> candidates = listFiles(flow.getSourceDir(), flow.getSourceExt(), config.getMinFileAgeSeconds());
            if (candidates.isEmpty()) {
                return;
            }

            int total = candidates.size();
            if (config.getMaxFilesPerCycle() > 0 && candidates.size() > config.getMaxFilesPerCycle()) {
                candidates = candidates.subList(0, config.getMaxFilesPerCycle());
            }

            logInfo("[" + flow.getName() + "] found " + total + " file(s), processing " + candidates.size());

            Path archiveDir = flow.getArchiveRoot()
                    .resolve(LocalDate.now(ZoneId.systemDefault()).format(ARCHIVE_DATE))
                    .resolve(safeDirName(flow.getSourceDir()));
            Files.createDirectories(archiveDir);

            for (Path sourceFile : candidates) {
                moveOne(flow, sourceFile, archiveDir);
            }
        } catch (Exception e) {
            logError("[" + flow.getName() + "] flow error", e);
        }
    }

    private void cleanupArchivesIfNeeded() {
        int retentionDays = config.getArchiveRetentionDays();
        if (retentionDays <= 0) {
            return;
        }

        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        if (today.equals(lastRetentionCleanupDate)) {
            return;
        }

        cleanupArchiveRoot(bankToZfin.getArchiveRoot(), retentionDays);
        cleanupArchiveRoot(zfinToBank.getArchiveRoot(), retentionDays);
        lastRetentionCleanupDate = today;
    }

    private void cleanupArchiveRoot(Path archiveRoot, int retentionDays) {
        if (Files.notExists(archiveRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        LocalDate threshold = LocalDate.now(ZoneId.systemDefault()).minusDays(retentionDays);

        try (Stream<Path> stream = Files.list(archiveRoot)) {
            List<Path> dateDirs = stream
                    .filter(Files::isDirectory)
                    .collect(Collectors.toCollection(ArrayList::new));

            for (Path dateDir : dateDirs) {
                Path namePart = dateDir.getFileName();
                if (namePart == null) {
                    continue;
                }

                LocalDate folderDate;
                try {
                    folderDate = LocalDate.parse(namePart.toString(), ARCHIVE_DATE);
                } catch (DateTimeParseException e) {
                    continue;
                }

                if (folderDate.isBefore(threshold)) {
                    deleteRecursively(dateDir);
                    logInfo("[RETENTION] deleted archive folder: " + dateDir);
                }
            }
        } catch (Exception e) {
            logError("[RETENTION] cleanup error for " + archiveRoot, e);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        List<Path> allPaths;
        try (Stream<Path> walk = Files.walk(root)) {
            allPaths = walk
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        for (Path path : allPaths) {
            Files.deleteIfExists(path);
        }
    }

    private static String safeDirName(Path sourceDir) {
        Path fileName = sourceDir.getFileName();
        if (fileName == null) {
            return "files";
        }
        return fileName.toString();
    }

    private void moveOne(Flow flow, Path sourceFile, Path archiveDir) {
        String sourceName = sourceFile.getFileName().toString();
        String destinationName = buildDestinationName(sourceName, flow.getTargetExt());
        Path destination = flow.getTargetDir().resolve(destinationName);
        Path archiveTarget = archiveDir.resolve(sourceName);

        try {
            if (Files.notExists(flow.getTargetDir(), LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(flow.getTargetDir());
            }

            if (config.isOverwriteExisting()) {
                Files.copy(sourceFile, destination, StandardCopyOption.REPLACE_EXISTING);
                Files.move(sourceFile, archiveTarget, StandardCopyOption.REPLACE_EXISTING);
            } else {
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    logWarn("[" + flow.getName() + "] skipped (destination exists): " + destination.getFileName());
                    return;
                }
                Files.copy(sourceFile, destination);
                Files.move(sourceFile, archiveTarget);
            }

            logInfo("[" + flow.getName() + "] moved " + sourceName + " -> " + destination.getFileName()
                    + " (archive: " + archiveTarget + ")");
        } catch (Exception e) {
            logError("[" + flow.getName() + "] failed for " + sourceName, e);
        }
    }

    private static String buildDestinationName(String sourceName, String targetExt) {
        if (targetExt.trim().isEmpty()) {
            return sourceName;
        }

        int dotPos = sourceName.lastIndexOf('.');
        String base = dotPos <= 0 ? sourceName : sourceName.substring(0, dotPos);
        return base + "." + targetExt;
    }

    private static List<Path> listFiles(Path sourceDir, String sourceExt, int minFileAgeSeconds) throws IOException {
        if (Files.notExists(sourceDir, LinkOption.NOFOLLOW_LINKS)) {
            return new ArrayList<Path>();
        }

        final long minAgeMs = Math.max(0, minFileAgeSeconds) * 1000L;
        final long now = System.currentTimeMillis();

        List<Path> result;
        try (Stream<Path> stream = Files.list(sourceDir)) {
            result = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> hasExtension(path, sourceExt))
                    .filter(path -> isOldEnough(path, now, minAgeMs))
                    .sorted(Comparator
                            .comparingLong(ZfinBridge::safeLastModified)
                            .thenComparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        return result;
    }

    private static boolean hasExtension(Path path, String sourceExt) {
        if (sourceExt == null || sourceExt.trim().isEmpty()) {
            return true;
        }

        String fileName = path.getFileName().toString();
        int dotPos = fileName.lastIndexOf('.');
        if (dotPos < 0 || dotPos == fileName.length() - 1) {
            return false;
        }

        String fileExt = fileName.substring(dotPos + 1).toLowerCase(Locale.ROOT);
        return fileExt.equals(sourceExt);
    }

    private static boolean isOldEnough(Path path, long nowMs, long minAgeMs) {
        if (minAgeMs <= 0) {
            return true;
        }

        long modified = safeLastModified(path);
        if (modified <= 0L || modified == Long.MAX_VALUE) {
            return false;
        }

        return nowMs - modified >= minAgeMs;
    }

    private static long safeLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private static String normalizeExt(String ext) {
        String value = ext == null ? "" : ext.trim().toLowerCase(Locale.ROOT);
        while (value.startsWith(".")) {
            value = value.substring(1);
        }
        return value;
    }

    private static final class SimpleLogFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            StringBuilder sb = new StringBuilder();
            sb.append(LocalDateTime.now().format(LOG_TS));
            sb.append(' ');
            sb.append(record.getLevel().getName());
            sb.append(' ');
            sb.append(formatMessage(record));
            sb.append(System.lineSeparator());

            Throwable thrown = record.getThrown();
            if (thrown != null) {
                sb.append("    ").append(thrown.toString()).append(System.lineSeparator());
                StackTraceElement[] stackTrace = thrown.getStackTrace();
                for (StackTraceElement element : stackTrace) {
                    sb.append("        at ").append(element.toString()).append(System.lineSeparator());
                }
            }
            return sb.toString();
        }
    }

    private static final class Flow {
        private final String name;
        private final Path sourceDir;
        private final String sourceExt;
        private final Path targetDir;
        private final String targetExt;
        private final Path archiveRoot;

        private Flow(String name, Path sourceDir, String sourceExt, Path targetDir, String targetExt, Path archiveRoot) {
            this.name = name;
            this.sourceDir = sourceDir;
            this.sourceExt = sourceExt;
            this.targetDir = targetDir;
            this.targetExt = targetExt;
            this.archiveRoot = archiveRoot;
        }

        private String getName() {
            return name;
        }

        private Path getSourceDir() {
            return sourceDir;
        }

        private String getSourceExt() {
            return sourceExt;
        }

        private Path getTargetDir() {
            return targetDir;
        }

        private String getTargetExt() {
            return targetExt;
        }

        private Path getArchiveRoot() {
            return archiveRoot;
        }
    }

    private static final class CliArgs {
        private final Path configPath;
        private final boolean runOnce;

        private CliArgs(Path configPath, boolean runOnce) {
            this.configPath = configPath;
            this.runOnce = runOnce;
        }

        private static CliArgs parse(String[] args) {
            Path configPath = Paths.get("config.ini");
            boolean once = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--once".equals(arg)) {
                    once = true;
                    continue;
                }

                if (arg.startsWith("--config=")) {
                    String value = arg.substring("--config=".length()).trim();
                    if (value.isEmpty()) {
                        throw new IllegalArgumentException("Empty value for --config");
                    }
                    configPath = Paths.get(value);
                    continue;
                }

                if ("--config".equals(arg)) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing value for --config");
                    }
                    configPath = Paths.get(args[++i]);
                    continue;
                }

                throw new IllegalArgumentException("Unknown argument: " + arg);
            }

            return new CliArgs(configPath, once);
        }

        private Path getConfigPath() {
            return configPath;
        }

        private boolean isRunOnce() {
            return runOnce;
        }
    }

    private static final class Config {
        private final Path bankRoot;
        private final String bankOutDir;
        private final String bankInDir;
        private final String bankArchiveDir;
        private final Path zfinRoot;
        private final String zfinInDir;
        private final String zfinOutDir;
        private final String zfinArchiveDir;
        private final String bankToZfinSourceExt;
        private final String bankToZfinTargetExt;
        private final String zfinToBankSourceExt;
        private final String zfinToBankTargetExt;
        private final int pollIntervalSeconds;
        private final int minFileAgeSeconds;
        private final int maxFilesPerCycle;
        private final boolean overwriteExisting;
        private final int archiveRetentionDays;
        private final Path lockFile;
        private final Path logDir;
        private final int logRotateBytes;
        private final int logRotateFiles;

        private Config(
                Path bankRoot,
                String bankOutDir,
                String bankInDir,
                String bankArchiveDir,
                Path zfinRoot,
                String zfinInDir,
                String zfinOutDir,
                String zfinArchiveDir,
                String bankToZfinSourceExt,
                String bankToZfinTargetExt,
                String zfinToBankSourceExt,
                String zfinToBankTargetExt,
                int pollIntervalSeconds,
                int minFileAgeSeconds,
                int maxFilesPerCycle,
                boolean overwriteExisting,
                int archiveRetentionDays,
                Path lockFile,
                Path logDir,
                int logRotateBytes,
                int logRotateFiles
        ) {
            this.bankRoot = bankRoot;
            this.bankOutDir = bankOutDir;
            this.bankInDir = bankInDir;
            this.bankArchiveDir = bankArchiveDir;
            this.zfinRoot = zfinRoot;
            this.zfinInDir = zfinInDir;
            this.zfinOutDir = zfinOutDir;
            this.zfinArchiveDir = zfinArchiveDir;
            this.bankToZfinSourceExt = bankToZfinSourceExt;
            this.bankToZfinTargetExt = bankToZfinTargetExt;
            this.zfinToBankSourceExt = zfinToBankSourceExt;
            this.zfinToBankTargetExt = zfinToBankTargetExt;
            this.pollIntervalSeconds = pollIntervalSeconds;
            this.minFileAgeSeconds = minFileAgeSeconds;
            this.maxFilesPerCycle = maxFilesPerCycle;
            this.overwriteExisting = overwriteExisting;
            this.archiveRetentionDays = archiveRetentionDays;
            this.lockFile = lockFile;
            this.logDir = logDir;
            this.logRotateBytes = logRotateBytes;
            this.logRotateFiles = logRotateFiles;
        }

        private static Config load(Path configPath) throws IOException {
            Path absoluteConfig = configPath.toAbsolutePath().normalize();
            Path baseDir = absoluteConfig.getParent();
            if (baseDir == null) {
                baseDir = Paths.get(".").toAbsolutePath().normalize();
            }

            Map<String, String> values = readKeyValueConfig(absoluteConfig);

            Path bankRoot = parsePath(required(values, "bank_root"), baseDir);
            Path zfinRoot = parsePath(required(values, "zfin_root"), baseDir);

            return new Config(
                    bankRoot,
                    get(values, "bank_out_dir", "OUT"),
                    get(values, "bank_in_dir", "IN"),
                    get(values, "bank_archive_dir", "ARH"),
                    zfinRoot,
                    get(values, "zfin_in_dir", "in"),
                    get(values, "zfin_out_dir", "out"),
                    get(values, "zfin_archive_dir", "arc"),
                    get(values, "bank_to_zfin_source_ext", "txt"),
                    get(values, "bank_to_zfin_target_ext", "occ"),
                    get(values, "zfin_to_bank_source_ext", "ifm"),
                    get(values, "zfin_to_bank_target_ext", "ifm"),
                    getInt(values, "poll_interval_seconds", 60, 1),
                    getInt(values, "min_file_age_seconds", 2, 0),
                    getInt(values, "max_files_per_cycle", 0, 0),
                    getBoolean(values, "overwrite_existing", true),
                    getInt(values, "archive_retention_days", 90, 0),
                    parsePath(get(values, "lock_file", "runtime/zfin-bridge.lock"), baseDir),
                    parsePath(get(values, "log_dir", "logs"), baseDir),
                    getInt(values, "log_rotate_bytes", 10485760, 1024),
                    getInt(values, "log_rotate_files", 10, 1)
            );
        }

        private static Path parsePath(String rawPath, Path baseDir) {
            Path path = Paths.get(rawPath);
            if (!path.isAbsolute()) {
                path = baseDir.resolve(path).normalize();
            }
            return path;
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("Missing required key in config.ini: " + key);
            }
            return value;
        }

        private static String get(Map<String, String> values, String key, String defaultValue) {
            String value = values.get(key);
            if (value == null || value.trim().isEmpty()) {
                return defaultValue;
            }
            return value;
        }

        private static int getInt(Map<String, String> values, String key, int defaultValue, int minValue) {
            String raw = values.get(key);
            if (raw == null || raw.trim().isEmpty()) {
                return defaultValue;
            }

            try {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed < minValue) {
                    throw new IllegalArgumentException(
                            "Invalid value for " + key + ": must be >= " + minValue + ", got " + parsed
                    );
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid integer value for " + key + ": " + raw);
            }
        }

        private static boolean getBoolean(Map<String, String> values, String key, boolean defaultValue) {
            String raw = values.get(key);
            if (raw == null || raw.trim().isEmpty()) {
                return defaultValue;
            }

            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
                return false;
            }

            throw new IllegalArgumentException("Invalid boolean value for " + key + ": " + raw);
        }

        private static Map<String, String> readKeyValueConfig(Path configPath) throws IOException {
            if (Files.notExists(configPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Config file not found: " + configPath.toAbsolutePath());
            }

            List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
            Map<String, String> values = new LinkedHashMap<String, String>();

            int lineNo = 0;
            for (String line : lines) {
                lineNo++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                    continue;
                }
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    continue;
                }

                int eqPos = trimmed.indexOf('=');
                if (eqPos <= 0) {
                    throw new IllegalArgumentException(
                            "Invalid config line " + lineNo + ": expected key=value, got: " + line
                    );
                }

                String key = trimmed.substring(0, eqPos).trim();
                String value = unquote(trimmed.substring(eqPos + 1).trim());

                if (key.isEmpty()) {
                    throw new IllegalArgumentException("Invalid config line " + lineNo + ": empty key");
                }
                values.put(key, value);
            }

            return values;
        }

        private static String unquote(String value) {
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1);
            }
            if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }

        private Path getBankRoot() {
            return bankRoot;
        }

        private String getBankOutDir() {
            return bankOutDir;
        }

        private String getBankInDir() {
            return bankInDir;
        }

        private String getBankArchiveDir() {
            return bankArchiveDir;
        }

        private Path getZfinRoot() {
            return zfinRoot;
        }

        private String getZfinInDir() {
            return zfinInDir;
        }

        private String getZfinOutDir() {
            return zfinOutDir;
        }

        private String getZfinArchiveDir() {
            return zfinArchiveDir;
        }

        private String getBankToZfinSourceExt() {
            return bankToZfinSourceExt;
        }

        private String getBankToZfinTargetExt() {
            return bankToZfinTargetExt;
        }

        private String getZfinToBankSourceExt() {
            return zfinToBankSourceExt;
        }

        private String getZfinToBankTargetExt() {
            return zfinToBankTargetExt;
        }

        private int getPollIntervalSeconds() {
            return pollIntervalSeconds;
        }

        private int getMinFileAgeSeconds() {
            return minFileAgeSeconds;
        }

        private int getMaxFilesPerCycle() {
            return maxFilesPerCycle;
        }

        private boolean isOverwriteExisting() {
            return overwriteExisting;
        }

        private int getArchiveRetentionDays() {
            return archiveRetentionDays;
        }

        private Path getLockFile() {
            return lockFile;
        }

        private Path getLogDir() {
            return logDir;
        }

        private int getLogRotateBytes() {
            return logRotateBytes;
        }

        private int getLogRotateFiles() {
            return logRotateFiles;
        }
    }
}
