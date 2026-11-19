package com.coke.nestspace.runtime.plugin;

/**
 * Lazily resolves a value from the current runtime profile stage.
 */
@FunctionalInterface
public interface RuntimeValue {
    Object resolve(RuntimeExecutionContext context) throws Exception;
}
