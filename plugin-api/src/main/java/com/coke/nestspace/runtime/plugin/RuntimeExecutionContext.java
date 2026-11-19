package com.coke.nestspace.runtime.plugin;

import android.app.Activity;
import android.app.Application;
import android.content.Context;

import java.util.Objects;

/**
 * Immutable values available while one runtime program stage is executing.
 */
public final class RuntimeExecutionContext {
    private final RuntimePhase phase;
    private final RuntimeLaunchSpec spec;
    private final ClassLoader loader;
    private final Context context;
    private final Application application;
    private final Class<? extends Activity> activityClass;

    RuntimeExecutionContext(
        RuntimePhase phase,
        RuntimeLaunchSpec spec,
        ClassLoader loader,
        Context context,
        Application application,
        Class<? extends Activity> activityClass
    ) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.context = context;
        this.application = application;
        this.activityClass = activityClass;
    }

    public RuntimePhase getPhase() {
        return phase;
    }

    public RuntimeLaunchSpec getSpec() {
        return spec;
    }

    public ClassLoader getLoader() {
        return loader;
    }

    public Context getContext() {
        return context;
    }

    public Application getApplication() {
        return application;
    }

    public Class<? extends Activity> getActivityClass() {
        return activityClass;
    }

    public Object resolve(Object value) throws Exception {
        return value instanceof RuntimeValue
            ? ((RuntimeValue) value).resolve(this)
            : value;
    }
}
