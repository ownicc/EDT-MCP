/** Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Extends the request deadline through body consumption without buffering the response. */
final class ResponseBodyTimeout extends InputStream
{
    private static final ScheduledThreadPoolExecutor TIMER = timer();

    private final InputStream source;
    private final long deadline;
    private final ScheduledFuture<?> timeout;
    private volatile boolean expired;
    private volatile boolean finished;

    /** Create immediately before send, so time waiting for headers also spends this budget. */
    static HttpResponse.BodyHandler<InputStream> handler(int seconds)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        return info -> HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofInputStream(),
            source -> new ResponseBodyTimeout(source, deadline));
    }

    private ResponseBodyTimeout(InputStream source, long deadline)
    {
        this.source = source;
        this.deadline = deadline;
        timeout = TIMER.schedule(this::expire, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    @Override
    public int read() throws IOException
    {
        checkTimeout();
        try
        {
            return afterRead(source.read());
        }
        catch (IOException e)
        {
            checkTimeout();
            throw e;
        }
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException
    {
        checkTimeout();
        try
        {
            return afterRead(source.read(bytes, offset, length));
        }
        catch (IOException e)
        {
            checkTimeout();
            throw e;
        }
    }

    private int afterRead(int count) throws IOException
    {
        checkTimeout();
        if (count == -1)
        {
            finish();
        }
        return count;
    }

    private void checkTimeout() throws HttpTimeoutException
    {
        if (expired || (!finished && System.nanoTime() - deadline >= 0))
        {
            expire();
            throw new HttpTimeoutException("Backend response body timed out"); //$NON-NLS-1$
        }
    }

    private void expire()
    {
        synchronized (this)
        {
            if (finished)
            {
                return;
            }
            expired = true;
            finished = true;
        }
        try
        {
            // HttpClient's response stream unblocks its pending reader when closed.
            source.close();
        }
        catch (IOException ignored)
        {
            // Reads report the timeout, even if closing the failed connection also throws.
        }
    }

    private synchronized void finish()
    {
        finished = true;
        timeout.cancel(false);
    }

    @Override
    public void close() throws IOException
    {
        finish();
        source.close();
    }

    private static ScheduledThreadPoolExecutor timer()
    {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "edt-mcp-proxy-body-timeout"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }
}
