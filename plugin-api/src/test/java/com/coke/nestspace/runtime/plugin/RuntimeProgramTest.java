package com.coke.nestspace.runtime.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

public class RuntimeProgramTest {
    @Before
    public void resetTarget() {
        ProgramTarget.processName = null;
        ProgramTarget.account = null;
        ProgramTarget.started = false;
        ProgramTarget.cleanupCount = 0;
    }

    @Test
    public void executesDeclarativeReflectionStagesAndCleanup() throws Exception {
        RuntimeProgram program = RuntimeProgram.builder("test-program")
            .requireClasses(ProgramTarget.class.getName())
            .add(
                RuntimePhase.BEFORE_APPLICATION_ATTACH,
                RuntimeReflection.seedStaticField(
                    ProgramTarget.class.getName(),
                    "processName",
                    RuntimeValues.processName()
                )
            )
            .add(
                RuntimePhase.BEFORE_APPLICATION_ATTACH,
                RuntimeReflection.ensureStaticInstance(
                    ProgramTarget.class.getName(),
                    "account",
                    ProgramAccount.class.getName()
                )
            )
            .add(
                RuntimePhase.AFTER_APPLICATION_CREATED,
                RuntimeReflection.invokeStatic(
                    ProgramTarget.class.getName(),
                    "start",
                    RuntimeValues.processName(),
                    2,
                    true
                )
            )
            .add(
                RuntimePhase.FIRST_ACTIVITY,
                execution -> (AutoCloseable) () ->
                    ProgramTarget.cleanupCount += 1
            )
            .build();

        assertTrue(program.isApplicable(getClass().getClassLoader()));
        RuntimeLaunchSpec spec = spec("com.example.target");
        program.beforeApplicationAttach(spec, getClass().getClassLoader(), null);
        program.afterApplicationCreated(
            spec,
            getClass().getClassLoader(),
            null,
            null
        );

        assertEquals("com.example.target:2:true", ProgramTarget.processName);
        assertNotNull(ProgramTarget.account);
        assertTrue(ProgramTarget.started);

        AutoCloseable firstActivity = program.installFirstActivityHooks(
            spec,
            getClass().getClassLoader(),
            null
        );
        assertNotNull(firstActivity);
        firstActivity.close();
        assertEquals(1, ProgramTarget.cleanupCount);
    }

    @Test
    public void continuesAfterFailureAndSupportsActivityScopedSteps() {
        List<Throwable> errors = new ArrayList<>();
        AtomicInteger runs = new AtomicInteger();
        RuntimeProgram program = RuntimeProgram.builder("activity-program")
            .onError((phase, step, error) -> errors.add(error))
            .add(RuntimePhase.BEFORE_TARGET_ACTIVITY, execution -> {
                runs.incrementAndGet();
                throw new IllegalStateException("expected");
            })
            .addForActivity(
                RuntimePhase.BEFORE_TARGET_ACTIVITY,
                ProgramActivity.class.getName(),
                execution -> {
                    ProgramTarget.processName = "activity";
                    return null;
                }
            )
            .build();

        RuntimeLaunchSpec spec = spec("com.example.target");
        program.beforeTargetActivity(
            spec,
            getClass().getClassLoader(),
            null,
            RuntimeProgramTest.ProgramActivity.class
        );

        assertEquals(1, runs.get());
        assertEquals(1, errors.size());
        assertEquals("activity", ProgramTarget.processName);
    }

    private static RuntimeLaunchSpec spec(String packageName) {
        return new RuntimeLaunchSpec.Builder()
            .packageName(packageName)
            .baseApkPath("/data/app/base.apk")
            .targetProcessName(packageName)
            .build();
    }

    public static final class ProgramTarget {
        static String processName;
        static Object account;
        static boolean started;
        static int cleanupCount;

        public static void start(String name, int count, boolean enabled) {
            processName = name + ":" + count + ":" + enabled;
            started = true;
        }
    }

    public static final class ProgramAccount {
        public ProgramAccount() {
        }
    }

    public static final class ProgramActivity
        extends android.app.Activity {
    }
}
