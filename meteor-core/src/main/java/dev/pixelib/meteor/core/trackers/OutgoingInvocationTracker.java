package dev.pixelib.meteor.core.trackers;

import dev.pixelib.meteor.base.RpcOptions;
import dev.pixelib.meteor.base.RpcSerializer;
import dev.pixelib.meteor.base.RpcTransport;
import dev.pixelib.meteor.base.enums.Direction;
import dev.pixelib.meteor.core.proxy.PendingInvocation;
import dev.pixelib.meteor.core.transport.packets.InvocationDescriptor;
import dev.pixelib.meteor.core.transport.packets.InvocationResponse;

import java.util.Timer;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

public class OutgoingInvocationTracker {

    private final Timer timer;
    private final RpcOptions options;
    private RpcTransport transport;
    private RpcSerializer serializer;

    // Map of pending invocations, keyed by invocation id
    private final ConcurrentHashMap<UUID, PendingInvocation<?>> pendingInvocations = new ConcurrentHashMap<>();

    public OutgoingInvocationTracker(RpcTransport transport, RpcSerializer serializer, RpcOptions options, Timer timer) {
        this.transport = transport;
        this.options = options;
        this.timer = timer;
        this.serializer = serializer;
    }

    public <T> T invokeRemoteMethod(InvocationDescriptor invocationDescriptor) throws Throwable {
        // create a pending invocation
        PendingInvocation<T> pendingInvocation = new PendingInvocation<>(options.getTimeoutSeconds(), this.timer, invocationDescriptor, () -> {
            // remove the pending invocation from the map
            pendingInvocations.remove(invocationDescriptor.getUniqueInvocationId());
        });

        // add the pending invocation to the map
        pendingInvocations.put(invocationDescriptor.getUniqueInvocationId(), pendingInvocation);

        transport.send(Direction.IMPLEMENTATION, invocationDescriptor.toBuffer(serializer));

        // wait for response or timeout
        try {
            return pendingInvocation.waitForResponse();
        } catch (CompletionException e) {
            throw e.getCause();
        }
    }

    public boolean completeInvocation(InvocationResponse invocationResponse) {
        // do we have a pending invocation for this invocation id?
        PendingInvocation<?> pendingInvocation = pendingInvocations.get(invocationResponse.getInvocationId());
        if (pendingInvocation == null) {
            // we cannot handle this invocation, so it must be handled in another listener
            return false;
        }

        pendingInvocation.complete(invocationResponse.getResult());

        // remove the pending invocation from the map
        pendingInvocations.remove(invocationResponse.getInvocationId());

        // invocation was successfully completed
        return true;
    }

}
