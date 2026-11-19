package com.coke.nestspace.runtime.plugin;

/** Common lazy values used by runtime reflection operations. */
public final class RuntimeValues {
    private RuntimeValues() {
    }

    public static RuntimeValue literal(Object value) {
        return context -> value;
    }

    public static RuntimeValue context() {
        return RuntimeExecutionContext::getContext;
    }

    public static RuntimeValue application() {
        return RuntimeExecutionContext::getApplication;
    }

    public static RuntimeValue processName() {
        return context -> context.getSpec().getTargetProcessName();
    }

    public static RuntimeValue classNamed(String className) {
        return context -> context.getLoader().loadClass(className);
    }

    public static RuntimeValue staticField(String className, String fieldName) {
        return context -> {
            Class<?> type = context.getLoader().loadClass(className);
            java.lang.reflect.Field field = RuntimeReflection.findField(
                type,
                fieldName
            );
            field.setAccessible(true);
            return field.get(null);
        };
    }

    public static RuntimeValue newInstance(String className, Object... arguments) {
        return context -> RuntimeReflection.construct(context, className, arguments);
    }
}
