package io.keploy.dedup;

import io.keploy.dedup.bootstrap.KeploySinkBridge;

/**
 * Runtime sink handler installed into {@link KeploySinkBridge#handler} strictly
 * AFTER {@link JdkSinkBootstrap} has loaded both {@link KeploySinkBridge} and
 * {@link KeploySinkBridge.Handler} via the bootstrap class loader.
 */
final class JdkSinkHandler implements KeploySinkBridge.Handler {

    // Fast-path: when the JVM is already permanently latched asyncExecutors=false,
    // no request window or job is active on this thread, and sink audit logging
    // is off, skip stack walking altogether. In-window / in-job calls ALWAYS
    // walk so per-window contamination remains exact.
    private static boolean canSkipPostLatchOutsideWindow() {
        return !SinkAllowlist.AUDIT
                && KeployDedupAgent.observedPreCapturedUntrackedExecutor.get()
                && !RequestGate.isInsideTrackedWindowOrJob();
    }

    @Override
    public void onThreadStart(Thread thread) {
        if (canSkipPostLatchOutsideWindow()) {
            return;
        }
        String[] caller = new String[1];
        boolean allowed = SinkAllowlist.isAllowlistedThreadStart(thread, caller);
        SinkAllowlist.audit("thread-start", allowed, thread != null ? thread.getName() : "null",
                thread, null, caller[0]);
        if (!allowed) {
            RequestGate.handleUntrackedSink("thread-start", thread, null, caller[0]);
        }
    }

    @Override
    public void onVirtualThreadStart(Thread thread) {
        if (canSkipPostLatchOutsideWindow()) {
            return;
        }
        String[] caller = new String[1];
        boolean allowed = SinkAllowlist.isAllowlistedVirtualThreadStart(thread, caller);
        SinkAllowlist.audit("virtual-thread-start", allowed, thread != null ? thread.getName() : "null",
                thread, null, caller[0]);
        if (!allowed) {
            RequestGate.handleUntrackedSink("virtual-thread-start", thread, null, caller[0]);
        }
    }

    @Override
    public void onTpeExecute(Object executor, Object command) {
        if (canSkipPostLatchOutsideWindow()) {
            return;
        }
        String[] caller = new String[1];
        boolean allowed = SinkAllowlist.isAllowlistedTpeExecute(executor, command, caller);
        SinkAllowlist.audit("tpe-execute", allowed, "", executor, command, caller[0]);
        if (!allowed) {
            RequestGate.handleUntrackedSink("tpe-execute", executor, command, caller[0]);
        }
    }

    @Override
    public void onStpeDelayedExecute(Object executor, Object task) {
        if (canSkipPostLatchOutsideWindow()) {
            return;
        }
        String[] caller = new String[1];
        boolean allowed = SinkAllowlist.isAllowlistedStpeDelayedExecute(executor, task, caller);
        SinkAllowlist.audit("stpe-delayed-execute", allowed, "", executor, task, caller[0]);
        if (!allowed) {
            RequestGate.handleUntrackedSink("stpe-delayed-execute", executor, task, caller[0]);
        }
    }

    @Override
    public void onFjpExternalSubmit(Object pool, Object task) {
        if (canSkipPostLatchOutsideWindow()) {
            return;
        }
        String[] caller = new String[1];
        boolean allowed = SinkAllowlist.isAllowlistedFjpSubmit(pool, task, caller);
        SinkAllowlist.audit("fjp-submit", allowed, "", pool, task, caller[0]);
        if (!allowed) {
            RequestGate.handleUntrackedSink("fjp-submit", pool, task, caller[0]);
        }
    }

    @Override
    public void onTimerSched(Object timer, Object task) {
        if (canSkipPostLatchOutsideWindow()) {
            return;
        }
        String[] caller = new String[1];
        boolean allowed = SinkAllowlist.isAllowlistedTimerSched(timer, task, caller);
        SinkAllowlist.audit("timer-sched", allowed, "", timer, task, caller[0]);
        if (!allowed) {
            RequestGate.handleUntrackedSink("timer-sched", timer, task, caller[0]);
        }
    }
}
