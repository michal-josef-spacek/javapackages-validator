package org.fedoraproject.javapackages.validator;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ClassUtils;
import org.fedoraproject.javapackages.validator.TextDecorator.Decoration;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * The base class for checks. Classes that inherit from this must implement
 * either one of the two "check" methods.
 * @param <Config> The config class corresponding to the check. Void may be used
 * if the check is not configurable.
 */
@SuppressFBWarnings({"DMI_HARDCODED_ABSOLUTE_FILENAME"})
public abstract class Check<Config> {
    private Map<Class<?>, Object> configurations = null;
    private Path config_src_dir = Paths.get("/mnt/config/src");
    private Path config_bin_dir = Paths.get("/mnt/config/bin");
    private Class<Config> configClass;
    private Config config;
    private Logger logger = new Logger();

    protected Logger getLogger() {
        return logger;
    }

    protected static String failMessage(String pattern, Object... arguments) {
        String result = "";
        result += "[";
        result += Main.getDecorator().decorate("FAIL", Decoration.red, Decoration.bold);
        result += "] ";
        result += MessageFormat.format(pattern, arguments);
        return result;
    }

    public Class<Config> getConfigClass() {
        return configClass;
    }

    public Config getConfig() {
        return config;
    }

    protected Check(Class<Config> configClass) {
        this.configClass = configClass;
    }

    protected Check(Class<Config> configClass, Config config) {
        this(configClass);
        this.config = config;
    }

    public final Check<Config> inherit(Check<?> parent) throws IOException {
        this.configurations = Collections.unmodifiableMap(parent.configurations);
        this.config_src_dir = parent.config_src_dir;
        this.config_bin_dir = parent.config_bin_dir;
        this.logger = parent.logger;
        this.config = getConfigInstance();
        return this;
    }

    private void compileFiles(Path sourceDir, Iterable<String> compilerOptions) throws IOException {
        List<File> inputFiles = Files.find(sourceDir, Integer.MAX_VALUE,
                (path, attributes) -> !attributes.isDirectory() && path.toString().endsWith(".java"))
                .map(Path::toFile).toList();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);

        logger.debug("Compiling source configuration files: {0}", inputFiles);

        try {
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(inputFiles);
            if (!compiler.getTask(null, fileManager, null, compilerOptions, null, compilationUnits).call()) {
                throw new RuntimeException("Failed to compile configuration sources");
            }
        } finally {
            fileManager.close();
        }
    }

    public static interface NoConfig {
        public static final NoConfig INSTANCE = new NoConfig() {};
    }

    private static FileTime lastModifiedRecursive(Path root) throws IOException {
        return Files.find(root, Integer.MAX_VALUE, (path, attributes) -> true).map(path -> {
            try {
                var result = Files.getLastModifiedTime(path);
                if (Files.isSymbolicLink(path)) {
                    var targetTime = Files.getLastModifiedTime(Files.readSymbolicLink(path));
                    if (targetTime.compareTo(result) > 0) {
                        return targetTime;
                    }
                }
                return result;
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }).max((lp, rp) -> lp.compareTo(rp)).get();
    }

    @SuppressFBWarnings({"DP_CREATE_CLASSLOADER_INSIDE_DO_PRIVILEGED"})
    protected Config getConfigInstance() throws IOException {
        if (configurations == null) {
            configurations = new HashMap<>();
            configurations.put(NoConfig.class, NoConfig.INSTANCE);

            if (!Files.isDirectory(config_src_dir)) {
                logger.debug("Configuration source directory does not exist");
            } else {
                FileTime lastModifiedSrc = lastModifiedRecursive(config_src_dir);
                logger.debug("Source configuration was last modified: {0}", lastModifiedSrc);
                FileTime lastModifiedBin = null;

                if (!Files.exists(config_bin_dir) || FileUtils.isEmptyDirectory(config_bin_dir.toFile()) ||
                        lastModifiedSrc.compareTo(lastModifiedBin = lastModifiedRecursive(config_bin_dir)) > 0) {
                    if (Files.isDirectory(config_bin_dir)) {
                        FileUtils.cleanDirectory(config_bin_dir.toFile());
                    } else {
                        Files.deleteIfExists(config_bin_dir);
                    }
                    if (lastModifiedBin != null) {
                        logger.debug("Compiled configuration was last modified: {0}", lastModifiedBin);
                    }
                    Files.createDirectories(config_bin_dir);
                    compileFiles(config_src_dir, Arrays.asList("-d", config_bin_dir.toString()));
                }
            }

            var classes = Files.find(config_bin_dir, Integer.MAX_VALUE, (path, attributes) ->
                    attributes.isRegularFile() && path.toString().endsWith(".class"))
                    .map(Path::toString).toArray(String[]::new);

            logger.debug("Compiled configuration files: [{0}]", Stream.of(classes)
                    .collect(Collectors.joining(", ")));

            for (int i = 0; i != classes.length; ++i) {
                classes[i] = classes[i].substring(config_bin_dir.toString().length() + 1);
                classes[i] = classes[i].substring(0, classes[i].length() - 6);
                classes[i] = classes[i].replace('/', '.');
            }

            try (URLClassLoader cl = new URLClassLoader(new URL[] {config_bin_dir.toUri().toURL()})) {
                for (var className : classes) {
                    Class<?> cls = cl.loadClass(className);
                    for (var intrfc : ClassUtils.getAllInterfaces(cls)) {
                        Object instance = cls.getConstructor().newInstance();
                        configurations.computeIfAbsent(intrfc, (i) -> instance);
                    }
                }
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }

            logger.debug("Configurations: [{0}]", configurations.keySet().stream()
                    .map(Class::getSimpleName).collect(Collectors.joining(", ")));
        }

        return configClass.cast(configurations.get(configClass));
    }

    abstract public Collection<String> check(Iterator<RpmPathInfo> testRpms) throws IOException;

    @SuppressFBWarnings(value = {"ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD"}, justification = "Needs rework")
    public int executeCheck(String... args) throws IOException {
        List<String> argList = new ArrayList<>();

        for (int i = 0; i != args.length; ++i) {
            if (args[i].equals("--config-src")) {
                ++i;
                config_src_dir = Paths.get(args[i]);
            } else if (args[i].equals("--config-bin")) {
                ++i;
                config_bin_dir = Paths.get(args[i]);
            } else if (args[i].startsWith("-")) {
                throw new RuntimeException("Unrecognized option: " + args[i]);
            } else {
                argList.add(args[i]);
            }
        }

        logger.debug("Compile source directory: {0}", config_src_dir);
        logger.debug("Compile target directory: {0}", config_bin_dir);
        logger.debug("Arguments: {0}", argList);

        config = getConfigInstance();

        if (config == null && !NoConfig.class.equals(configClass)) {
            getLogger().info("{0}: Configuration class not found, ignoring the test", getClass().getSimpleName());
            return 0;
        }

        int result = 0;

        // TODO
        Main.TEST_RPMS = new ArrayList<>();
        for (var rpmIt = new ArgFileIterator(argList); rpmIt.hasNext();) {
            Main.TEST_RPMS.add(rpmIt.next());
        }

        var messages = check(Main.getTestRpms().iterator());
        for (var message : messages) {
            System.out.println(message);
        }

        if (messages.isEmpty()) {
            logger.info("Summary: all tests {0}", Main.getDecorator().decorate("passed", Decoration.green, Decoration.bold));
        } else {
            result = 1;
            logger.info("Summary: {0} tests {1}", messages.size(), Main.getDecorator().decorate("failed", Decoration.red, Decoration.bold));
        }

        return result;
    }
}
