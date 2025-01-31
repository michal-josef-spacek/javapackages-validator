package org.fedoraproject.javapackages.validator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry;
import org.fedoraproject.javadeptools.rpm.RpmArchiveInputStream;
import org.fedoraproject.javadeptools.rpm.RpmInfo;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class Common {
    public static final IOException INCOMPLETE_READ = new IOException("Incomplete read in RPM stream");

    private static String getPackageName(String sourceRpmFileName) {
        String result = sourceRpmFileName;
        result = result.substring(0, result.lastIndexOf('-'));
        result = result.substring(0, result.lastIndexOf('-'));

        if (result.isEmpty()) {
            throw new RuntimeException("Could not read package name for source RPM: " + sourceRpmFileName);
        }

        return result;
    }

    public static String getPackageName(RpmInfo rpm) {
        if (rpm.isSourcePackage()) {
            return rpm.getName();
        } else {
            return Common.getPackageName(rpm.getSourceRPM());
        }
    }

    @SuppressFBWarnings({"DMI_HARDCODED_ABSOLUTE_FILENAME"})
    public static Path getEntryPath(CpioArchiveEntry entry) {
        return Paths.get("/").resolve(Paths.get("/").relativize(Paths.get("/").resolve(Paths.get(entry.getName()))));
    }

    /**
     * @param rpmPath The rpm file to inspect.
     * @return A map of file paths mapped to either the target of the symlink
     * or null, if the file path is not a symlink.
     * @throws IOException
     */
    public static SortedMap<CpioArchiveEntry, Path> rpmFilesAndSymlinks(Path rpmPath) throws IOException {
        var result = new TreeMap<CpioArchiveEntry, Path>((lhs, rhs) -> lhs.getName().compareTo(rhs.getName()));

        try (var is = new RpmArchiveInputStream(rpmPath)) {
            for (CpioArchiveEntry rpmEntry; (rpmEntry = is.getNextEntry()) != null;) {
                Path target = null;

                if (rpmEntry.isSymbolicLink()) {
                    var content = new byte[(int) rpmEntry.getSize()];

                    if (is.read(content) != content.length) {
                        throw Common.INCOMPLETE_READ;
                    }

                    target = Paths.get(new String(content, StandardCharsets.UTF_8));
                }

                result.put(rpmEntry, target);
            }
        }

        return result;
    }

    public static Collection<RpmInfo> transitiveDependencies(RpmInfo rpm, Collection<? extends RpmInfo> rpms) {
        var result = new HashSet<RpmInfo>();

        var providers = new TreeMap<String, RpmInfo>();
        for (var otherRpm : rpms) {
            for (var provider : otherRpm.getProvides()) {
                providers.put(provider, otherRpm);
            }
        }

        var satisfiedRequirements = new TreeSet<String>();
        var unsatisfiedRequirements = new TreeSet<String>(rpm.getRequires());

        boolean change = true;
        while (change) {
            change = false;
            var newRequirements = new TreeSet<String>();
            for (var req : unsatisfiedRequirements) {
                var provider = providers.get(req);
                if (provider != null) {
                    change = true;
                    result.add(provider);
                    satisfiedRequirements.add(req);
                    newRequirements.addAll(provider.getRequires());
                }
            }
            unsatisfiedRequirements.addAll(newRequirements);
            unsatisfiedRequirements.removeAll(satisfiedRequirements);
        }

        return result;
    }
}
