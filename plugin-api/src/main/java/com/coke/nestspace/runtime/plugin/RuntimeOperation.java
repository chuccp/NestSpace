package com.coke.nestspace.runtime.plugin;

/**
 * One executable step in a declarative runtime program.
 *
 * Returning an AutoCloseable registers a reverse-order cleanup action.
 */
@FunctionalInterface
public interface RuntimeOperation {
    Object execute(RuntimeExecutionContext context) throws Exception;

    default String describe() {
        return getClass().getSimpleName();
    }
}
