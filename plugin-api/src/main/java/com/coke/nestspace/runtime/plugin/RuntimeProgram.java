package com.coke.nestspace.runtime.plugin;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Data-driven runtime profile. Lifecycle plumbing, class-loader scope, errors
 * and reverse-order cleanup are shared; target profiles mostly declare steps.
 */
public final class RuntimeProgram implements RuntimeProfile {
    private static final String TAG = "RuntimeProgram";

    private final String id;
    private final Map<RuntimePhase, List<Step>> stages;
    private final Set<String> requiredClasses;
    private final boolean continueOnError;
    private final RuntimeErrorListener errorListener;
    private final List<AutoCloseable> cleanup = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private RuntimeProgram(Builder builder) {
        this.id = builder.id;
        Map<RuntimePhase, List<Step>> copy = new EnumMap<>(RuntimePhase.class);
        for (Map.Entry<RuntimePhase, List<BuilderStep>> entry : builder.stages.entrySet()) {
            List<Step> values = new ArrayList<>();
            for (BuilderStep step : entry.getValue()) {
                values.add(step.build());
            }
            copy.put(entry.getKey(), Collections.unmodifiableList(values));
        }
        this.stages = Collections.unmodifiableMap(copy);
        this.requiredClasses = Collections.unmodifiableSet(
            new LinkedHashSet<>(builder.requiredClasses)
        );
        this.continueOnError = builder.continueOnError;
        this.errorListener = builder.errorListener;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public boolean isApplicable(ClassLoader loader) {
        for (String className : requiredClasses) {
            try {
                Class<?> type = Class.forName(className, false, loader);
                if (type.getClassLoader() != loader) return false;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void beforeApplicationAttach(
        RuntimeLaunchSpec spec,
        ClassLoader loader,
        Context context
    ) {
        run(
            RuntimePhase.BEFORE_APPLICATION_ATTACH,
            spec,
            loader,
            context,
            null,
            null
        );
    }

    @Override
    public void afterApplicationCreated(
        RuntimeLaunchSpec spec,
        ClassLoader loader,
        Context context,
        Application application
    ) {
        run(
            RuntimePhase.AFTER_APPLICATION_CREATED,
            spec,
            loader,
            context,
            application,
            null
        );
    }

    @Override
    public void afterEmbeddedApplicationCreated(
        RuntimeLaunchSpec spec,
        ClassLoader loader,
        Context context,
        Application application
    ) {
        run(
            RuntimePhase.AFTER_EMBEDDED_APPLICATION_CREATED,
            spec,
            loader,
            context,
            application,
            null
        );
    }

    @Override
    public void beforeTargetActivity(
        RuntimeLaunchSpec spec,
        ClassLoader loader,
        Context context,
        Class<? extends Activity> activityClass
    ) {
        run(
            RuntimePhase.BEFORE_TARGET_ACTIVITY,
            spec,
            loader,
            context,
            null,
            activityClass
        );
    }

    @Override
    public AutoCloseable installFirstActivityHooks(
        RuntimeLaunchSpec spec,
        ClassLoader loader,
        Context context
    ) {
        run(
            RuntimePhase.FIRST_ACTIVITY,
            spec,
            loader,
            context,
            null,
            null
        );
        return this;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (cleanup) {
            for (int index = cleanup.size() - 1; index >= 0; index--) {
                try {
                    cleanup.get(index).close();
                } catch (Throwable error) {
                    report(RuntimePhase.BEFORE_APPLICATION_ATTACH, null, error);
                }
            }
            cleanup.clear();
        }
    }

    private void run(
        RuntimePhase phase,
        RuntimeLaunchSpec spec,
        ClassLoader loader,
        Context context,
        Application application,
        Class<? extends Activity> activityClass
    ) {
        if (closed.get()) return;
        List<Step> operations = stages.get(phase);
        if (operations == null || operations.isEmpty()) return;
        RuntimeExecutionContext execution = new RuntimeExecutionContext(
            phase,
            spec,
            loader,
            context,
            application,
            activityClass
        );
        RuntimePluginRuntime.withContainerClassLoader(loader, () -> {
            for (Step step : operations) {
                if (!step.shouldRun(execution)) continue;
                try {
                    Object result = step.operation.execute(execution);
                    if (result instanceof AutoCloseable) {
                        synchronized (cleanup) {
                            cleanup.add((AutoCloseable) result);
                        }
                    }
                } catch (Throwable error) {
                    report(phase, step.operation.describe(), error);
                    if (!continueOnError) {
                        throw new RuntimeProgramException(
                            "runtime step failed: " + step.operation.describe(),
                            error
                        );
                    }
                }
            }
            return null;
        });
    }

    private void report(RuntimePhase phase, String step, Throwable error) {
        if (errorListener != null) {
            try {
                errorListener.onError(phase, step, error);
            } catch (Throwable ignored) {
            }
        } else {
            Log.w(
                TAG,
                "id=" + id + " phase=" + phase + " step=" + step,
                error
            );
        }
    }

    public interface RuntimeErrorListener {
        void onError(RuntimePhase phase, String step, Throwable error);
    }

    public static final class RuntimeProgramException extends RuntimeException {
        RuntimeProgramException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class Builder {
        private final String id;
        private final Map<RuntimePhase, List<BuilderStep>> stages =
            new EnumMap<>(RuntimePhase.class);
        private final Set<String> requiredClasses = new LinkedHashSet<>();
        private boolean continueOnError = true;
        private RuntimeErrorListener errorListener;

        private Builder(String id) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("runtime program id is required");
            }
            this.id = id.trim();
        }

        public Builder requireClasses(String... classNames) {
            if (classNames != null) {
                for (String className : classNames) {
                    if (className != null && !className.trim().isEmpty()) {
                        requiredClasses.add(className.trim());
                    }
                }
            }
            return this;
        }

        public Builder add(RuntimePhase phase, RuntimeOperation operation) {
            if (phase == null || operation == null) {
                throw new IllegalArgumentException("phase and operation are required");
            }
            stages.computeIfAbsent(phase, ignored -> new ArrayList<>())
                .add(new BuilderStep(operation, null, false));
            return this;
        }

        public Builder addOnce(RuntimePhase phase, RuntimeOperation operation) {
            if (phase == null || operation == null) {
                throw new IllegalArgumentException("phase and operation are required");
            }
            stages.computeIfAbsent(phase, ignored -> new ArrayList<>())
                .add(new BuilderStep(operation, null, true));
            return this;
        }

        public Builder addForActivity(
            RuntimePhase phase,
            String activityClassName,
            RuntimeOperation operation
        ) {
            if (phase == null || operation == null ||
                activityClassName == null || activityClassName.trim().isEmpty()
            ) {
                throw new IllegalArgumentException(
                    "phase, activity class and operation are required"
                );
            }
            stages.computeIfAbsent(phase, ignored -> new ArrayList<>())
                .add(new BuilderStep(
                    operation,
                    activityClassName.trim(),
                    false
                ));
            return this;
        }

        public Builder failFast() {
            this.continueOnError = false;
            return this;
        }

        public Builder onError(RuntimeErrorListener listener) {
            this.errorListener = listener;
            return this;
        }

        public RuntimeProgram build() {
            return new RuntimeProgram(this);
        }
    }

    private static final class BuilderStep {
        private final RuntimeOperation operation;
        private final String activityClassName;
        private final boolean once;

        private BuilderStep(
            RuntimeOperation operation,
            String activityClassName,
            boolean once
        ) {
            this.operation = operation;
            this.activityClassName = activityClassName;
            this.once = once;
        }

        private Step build() {
            return new Step(operation, activityClassName, once);
        }
    }

    private static final class Step {
        private final RuntimeOperation operation;
        private final String activityClassName;
        private final AtomicBoolean completed;

        private Step(
            RuntimeOperation operation,
            String activityClassName,
            boolean once
        ) {
            this.operation = operation;
            this.activityClassName = activityClassName;
            this.completed = once ? new AtomicBoolean(false) : null;
        }

        private boolean shouldRun(RuntimeExecutionContext context) {
            if (activityClassName != null) {
                Class<?> activity = context.getActivityClass();
                if (activity == null || !activityClassName.equals(activity.getName())) {
                    return false;
                }
            }
            return completed == null || completed.compareAndSet(false, true);
        }
    }
}
