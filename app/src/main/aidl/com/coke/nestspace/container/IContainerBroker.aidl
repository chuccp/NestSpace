package com.coke.nestspace.container;

/** Main-process slot allocation for target subprocesses. */
interface IContainerBroker {
    int acquireProcessSlot(
            String packageName,
            String instanceId,
            String targetProcess,
            int callerSlot);

    String[] liveProcessSlots(
            String packageName,
            String instanceId);
}
