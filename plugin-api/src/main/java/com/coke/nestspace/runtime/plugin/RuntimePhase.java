package com.coke.nestspace.runtime.plugin;

/** Ordered lifecycle phases available to a generic runtime program. */
public enum RuntimePhase {
    BEFORE_APPLICATION_ATTACH,
    AFTER_APPLICATION_CREATED,
    AFTER_EMBEDDED_APPLICATION_CREATED,
    BEFORE_TARGET_ACTIVITY,
    FIRST_ACTIVITY
}
