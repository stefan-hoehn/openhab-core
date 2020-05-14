/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.io.rest;

import java.io.Closeable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.ws.rs.sse.OutboundSseEvent;
import javax.ws.rs.sse.SseEventSink;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * We do not use the SseBroadcaster as it seems registered SseEventSinks are not removed if the peer terminates the
 * connection.
 *
 * @author Markus Rathgeb - Initial contribution
 *
 * @param <I> the type of the SSE event sink specific information
 */
@NonNullByDefault
public class SseBroadcaster<@NonNull I> implements Closeable {

    public interface Listener<I> {
        void sseEventSinkRemoved(final SseEventSink sink, I info);
    }

    private final Logger logger = LoggerFactory.getLogger(SseBroadcaster.class);

    private final List<Listener<I>> listeners = new CopyOnWriteArrayList<>();
    private final Map<SseEventSink, I> sinks = new ConcurrentHashMap<>();

    public void addListener(final Listener<I> listener) {
        listeners.add(listener);
    }

    public void removeListener(final Listener<I> listener) {
        listeners.remove(listener);
    }

    public @Nullable I add(final SseEventSink sink, final I info) {
        return sinks.put(sink, info);
    }

    public @Nullable I remove(final SseEventSink sink) {
        return sinks.remove(sink);
    }

    public @Nullable I getInfo(final SseEventSink sink) {
        return sinks.get(sink);
    }

    public Stream<I> getInfoIf(Predicate<I> predicate) {
        return sinks.values().stream().filter(predicate);
    }

    @Override
    public void close() {
        final Iterator<Entry<SseEventSink, I>> it = sinks.entrySet().iterator();
        while (it.hasNext()) {
            final Entry<SseEventSink, I> entry = it.next();
            it.remove();
            close(entry.getKey());
            notifyAboutRemoval(entry.getKey(), entry.getValue());
        }
    }

    public void send(final OutboundSseEvent event) {
        sendIf(event, info -> true);
    }

    public void sendIf(final OutboundSseEvent event, Predicate<I> predicate) {
        logger.trace("broadcast to potential {} sinks", sinks.size());
        sinks.forEach((sink, info) -> {
            if (sink.isClosed()) {
                // We are using a concurrent collection, so we are allowed to modify the collection asynchronous (we
                // don't know if there is currently an iteration in progress or not, but it does not matter).
                handleRemoval(sink);
                return;
            }

            // Check if we should send at all.
            if (!predicate.test(info)) {
                return;
            }

            sink.send(event).exceptionally(th -> {
                close(sink);

                // We are using a concurrent collection, so we are allowed to modify the collection asynchronous (we
                // don't know if there is currently an iteration in progress or not, but it does not matter).
                handleRemoval(sink);

                final String thClass = th.getClass().toString();
                final String message = th.getMessage();

                if (thClass.equals("class org.eclipse.jetty.io.EofException")) {
                    // The peer terminates the connection.
                } else if (th instanceof IllegalStateException && message != null
                        && (message.equals("The sink is already closed, unable to queue SSE event for send")
                                || message.equals("AsyncContext completed and/or Request lifecycle recycled"))) {
                    // java.lang.IllegalStateException: The sink is already closed, unable to queue SSE event for
                    // send
                    // java.lang.IllegalStateException: AsyncContext completed and/or Request lifecycle recycled
                } else {
                    logger.warn("failure", th);
                }
                return null;
            });
        });
    }

    public void closeAndRemoveIf(Predicate<I> predicate) {
        sinks.forEach((sink, info) -> {
            if (predicate.test(info)) {
                close(sink);

                // We are using a concurrent collection, so we are allowed to modify the collection asynchronous (we
                // don't know if there is currently an iteration in progress or not, but it does not matter).
                handleRemoval(sink);
            }
        });
    }

    private void close(final SseEventSink sink) {
        try {
            sink.close();
        } catch (final RuntimeException ex) {
            logger.debug("Closing a SSE event sink failed. Nothing we can do here...", ex);
        }
    }

    private void handleRemoval(final SseEventSink sink) {
        final I info = sinks.remove(sink);
        notifyAboutRemoval(sink, info);
    }

    private void notifyAboutRemoval(final SseEventSink sink, I info) {
        listeners.forEach(listener -> {
            listener.sseEventSinkRemoved(sink, info);
        });
    }
}
