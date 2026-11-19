package com.coke.nestspace.runtime.plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Reusable reflective operations for runtime profiles. Operations resolve
 * classes through the current target ClassLoader and support lazy values.
 */
public final class RuntimeReflection {
    private RuntimeReflection() {
    }

    public static RuntimeOperation seedStaticField(
        String className,
        String fieldName,
        Object value
    ) {
        return new NamedOperation(
            "seed " + className + "#" + fieldName,
            context -> {
                Class<?> type = context.getLoader().loadClass(className);
                Field field = findField(type, fieldName);
                field.setAccessible(true);
                Object resolved = context.resolve(value);
                field.set(null, coerce(resolved, field.getType()));
                return field.get(null);
            }
        );
    }

    public static RuntimeOperation ensureStaticInstance(
        String className,
        String fieldName,
        String implementationClassName,
        Object... constructorArguments
    ) {
        return new NamedOperation(
            "ensure " + className + "#" + fieldName,
            context -> {
                Class<?> type = context.getLoader().loadClass(className);
                Field field = findField(type, fieldName);
                field.setAccessible(true);
                Object current = field.get(null);
                if (current != null) return current;
                Object created = construct(
                    context,
                    implementationClassName,
                    constructorArguments
                );
                field.set(null, created);
                return created;
            }
        );
    }

    public static RuntimeOperation invokeStatic(
        String className,
        String methodName,
        Object... arguments
    ) {
        return new NamedOperation(
            "invoke " + className + "#" + methodName,
            context -> {
                Class<?> type = context.getLoader().loadClass(className);
                Object[] resolved = resolveArguments(context, arguments);
                Method method = findMethod(type, methodName, resolved, true);
                method.setAccessible(true);
                return invoke(method, null, resolved);
            }
        );
    }

    public static RuntimeOperation invokeInstance(
        Object instance,
        String methodName,
        Object... arguments
    ) {
        return new NamedOperation(
            "invoke instance#" + methodName,
            context -> {
                Object target = context.resolve(instance);
                if (target == null) {
                    throw new IllegalStateException("instance is null for " + methodName);
                }
                Object[] resolved = resolveArguments(context, arguments);
                Method method = findMethod(
                    target.getClass(),
                    methodName,
                    resolved,
                    false
                );
                method.setAccessible(true);
                return invoke(method, target, resolved);
            }
        );
    }

    public static Object construct(
        RuntimeExecutionContext context,
        String className,
        Object... arguments
    ) throws Exception {
        Class<?> type = context.getLoader().loadClass(className);
        Object[] resolved = resolveArguments(context, arguments);
        Constructor<?> constructor = findConstructor(type, resolved);
        constructor.setAccessible(true);
        return constructor.newInstance(resolved);
    }

    public static Object[] resolveArguments(
        RuntimeExecutionContext context,
        Object[] arguments
    ) throws Exception {
        Object[] resolved = new Object[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            resolved[index] = context.resolve(arguments[index]);
        }
        return resolved;
    }

    public static Field findField(
        Class<?> type,
        String fieldName
    ) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "#" + fieldName);
    }

    public static Method findMethod(
        Class<?> type,
        String methodName,
        Object[] arguments,
        boolean requireStatic
    ) throws NoSuchMethodException {
        Method best = null;
        int bestScore = Integer.MIN_VALUE;
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) continue;
                if (method.isBridge() || method.isSynthetic()) continue;
                if (requireStatic && !Modifier.isStatic(method.getModifiers())) continue;
                if (!matches(method.getParameterTypes(), arguments)) continue;
                int score = compatibilityScore(method.getParameterTypes(), arguments);
                if (score > bestScore) {
                    best = method;
                    bestScore = score;
                }
            }
            current = current.getSuperclass();
        }
        if (best == null) {
            throw new NoSuchMethodException(
                type.getName() + "#" + methodName + "/" + arguments.length
            );
        }
        return best;
    }

    private static Constructor<?> findConstructor(
        Class<?> type,
        Object[] arguments
    ) throws NoSuchMethodException {
        Constructor<?> best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (!matches(constructor.getParameterTypes(), arguments)) continue;
            int score = compatibilityScore(
                constructor.getParameterTypes(),
                arguments
            );
            if (score > bestScore) {
                best = constructor;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new NoSuchMethodException(
                type.getName() + " constructor/" + arguments.length
            );
        }
        return best;
    }

    private static Object invoke(
        Method method,
        Object receiver,
        Object[] arguments
    ) throws Exception {
        try {
            return method.invoke(receiver, arguments);
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getTargetException();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        }
    }

    private static boolean matches(Class<?>[] types, Object[] arguments) {
        if (types.length != arguments.length) return false;
        for (int index = 0; index < types.length; index++) {
            if (!accepts(types[index], arguments[index])) return false;
        }
        return true;
    }

    private static int compatibilityScore(
        Class<?>[] types,
        Object[] arguments
    ) {
        int score = 0;
        for (int index = 0; index < types.length; index++) {
            Object value = arguments[index];
            if (value != null && types[index].equals(value.getClass())) score += 10;
            if (value != null && types[index].isInstance(value)) score += 5;
        }
        return score;
    }

    private static boolean accepts(Class<?> type, Object value) {
        if (value == null) return !type.isPrimitive();
        Class<?> boxed = boxed(type);
        return boxed.isInstance(value);
    }

    private static Object coerce(Object value, Class<?> target) {
        if (value == null) return null;
        Class<?> boxed = boxed(target);
        if (boxed.isInstance(value)) return value;
        if (value instanceof Number) {
            Number number = (Number) value;
            if (boxed == Integer.class) return number.intValue();
            if (boxed == Long.class) return number.longValue();
            if (boxed == Short.class) return number.shortValue();
            if (boxed == Byte.class) return number.byteValue();
            if (boxed == Float.class) return number.floatValue();
            if (boxed == Double.class) return number.doubleValue();
        }
        return value;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static final class NamedOperation implements RuntimeOperation {
        private final String description;
        private final RuntimeOperation delegate;

        private NamedOperation(String description, RuntimeOperation delegate) {
            this.description = description;
            this.delegate = delegate;
        }

        @Override
        public Object execute(RuntimeExecutionContext context) throws Exception {
            return delegate.execute(context);
        }

        @Override
        public String describe() {
            return description;
        }
    }
}
