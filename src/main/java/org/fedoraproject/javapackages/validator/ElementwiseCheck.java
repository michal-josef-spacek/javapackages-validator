package org.fedoraproject.javapackages.validator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.function.Predicate;

public abstract class ElementwiseCheck<Config> extends Check<Config> {
    private Predicate<RpmPathInfo> filter = rpm -> true;

    protected ElementwiseCheck(Class<Config> configClass) {
        super(configClass);
    }

    protected ElementwiseCheck(Class<Config> configClass, Config config) {
        super(configClass, config);
    }

    protected ElementwiseCheck<?> setFilter(Predicate<RpmPathInfo> filter) {
        this.filter = filter;
        return this;
    }

    abstract protected Collection<String> check(RpmPathInfo rpm) throws IOException;

    public final Collection<String> check(Path rpmPath) throws IOException {
        return check(new RpmPathInfo(rpmPath));
    }

    @Override
    public final Collection<String> check(Iterator<RpmPathInfo> testRpms) throws IOException {
        var result = new ArrayList<String>(0);

        while (testRpms.hasNext()) {
            RpmPathInfo next = testRpms.next();
            if (filter.test(next)) {
                result.addAll(check(next));
            }
        }

        return result;
    }
}
