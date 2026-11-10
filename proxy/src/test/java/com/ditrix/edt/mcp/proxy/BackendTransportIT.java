/** Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.ditrix.edt.mcp.proxy.ProxyRoutingIT.McpTestClient;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Real HTTP failures after headers or a mutation, rather than before a request starts. */
public class BackendTransportIT
{
    @Rule
    public final Timeout timeout = Timeout.seconds(30);

    private HttpServer server;
    private ExecutorService workers;
    private final CountDownLatch releaseBody = new CountDownLatch(1);
    private final AtomicInteger mutations = new AtomicInteger();
    private ProxyServer proxy;

    @Before
    public void setUp() throws Exception
    {
        workers = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "backend-transport-test");
            thread.setDaemon(true);
            return thread;
        });
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/health", x -> reply(x, "{\"status\":\"ok\"}"));
        server.createContext("/mcp", this::handle);
        server.start();
    }

    @After
    public void tearDown()
    {
        releaseBody.countDown();
        if (proxy != null)
        {
            proxy.stop();
        }
        server.stop(0);
        workers.shutdownNow();
    }

    @Test
    public void bufferedBodyUsesTheDiscoveryTimeoutOverride() throws Exception
    {
        Backend backend = backend(30);
        backend.ensureSession();
        assertBodyTimeout(() -> backend.callToolBlocking("stall", new JsonObject(), 1));
        assertTrue("a timed-out read must not poison the next request",
            backend.callToolBlocking("ok", new JsonObject()).contains("result"));
    }

    @Test
    public void streamedBodyCannotHoldAReaderPastTheConfiguredTimeout() throws Exception
    {
        Backend backend = backend(1);
        backend.ensureSession();
        assertBodyTimeout(() -> {
            HttpResponse<InputStream> response = backend.forward(toolRequest("stall"), "application/json");
            try (InputStream in = response.body())
            {
                return in.transferTo(new ByteArrayOutputStream());
            }
        });
    }

    @Test
    public void timeSpentWaitingForHeadersIsPartOfTheBodyBudget() throws Exception
    {
        Backend backend = backend(1);
        backend.ensureSession();
        assertBodyTimeout(() -> backend.callToolBlocking("slow_headers_and_body", new JsonObject()));
    }

    @Test
    public void aLostMutationResponseReportsAnUnknownOutcome() throws Exception
    {
        assertUnknownMutationOutcome(new JsonObject());
    }

    @Test
    public void aLostMutationResponseIsAlsoExplicitForAStructuredContentOptOut() throws Exception
    {
        JsonObject experimental = new JsonObject();
        experimental.addProperty("structuredContent", false);
        JsonObject capabilities = new JsonObject();
        capabilities.add("experimental", experimental);
        assertUnknownMutationOutcome(capabilities);
    }

    private void assertUnknownMutationOutcome(JsonObject capabilities) throws Exception
    {
        int port = server.getAddress().getPort();
        ProxyConfig cfg = ProxyConfig.parse(new String[] {
            "--port", "0", "--scan", port + "-" + port, "--timeout", "1" }, Map.of());
        BackendRegistry registry = new BackendRegistry(cfg);
        registry.refresh();
        proxy = new ProxyServer(cfg, registry, new McpProxyHandler(cfg, registry, new SessionManager()));
        proxy.start();
        McpTestClient client = new McpTestClient(proxy.getPort());
        client.handshake(capabilities);

        JsonObject result = client.callTool("mutate_then_disconnect", new JsonObject());

        assertEquals("the backend performed the mutation, and it must never be replayed", 1, mutations.get());
        assertTrue("a lost response is a tool error", ProxyRoutingIT.isToolError(result));
        String message = ProxyRoutingIT.errorText(result).toLowerCase(java.util.Locale.ROOT);
        assertTrue(message, message.contains("unknown"));
        assertTrue("the caller must inspect state before deciding whether to repeat: " + message,
            message.contains("check") && message.contains("state"));
        assertFalse("do not tell an agent to blindly repeat an already committed mutation: " + message,
            message.contains("; retry"));
    }

    private Backend backend(int seconds)
    {
        return new Backend(server.getAddress().getPort(), HttpClient.newHttpClient(), seconds);
    }

    private void assertBodyTimeout(Callable<?> read) throws Exception
    {
        Future<?> pending = workers.submit(read);
        try
        {
            pending.get(4, TimeUnit.SECONDS);
            fail("an unfinished response must time out, not be accepted as a partial success");
        }
        catch (ExecutionException expected)
        {
            assertTrue("expected an HTTP body timeout, got " + expected.getCause(),
                expected.getCause() instanceof HttpTimeoutException);
        }
        catch (java.util.concurrent.TimeoutException e)
        {
            fail("body read remained blocked after the configured one-second deadline");
        }
        finally
        {
            pending.cancel(true);
        }
    }

    private void handle(HttpExchange exchange) throws IOException
    {
        try
        {
            JsonObject request = Json.parseObject(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
            String method = Json.str(request, "method");
            String id = request.has("id") ? request.get("id").toString() : "null";
            if ("initialize".equals(method))
            {
                exchange.getResponseHeaders().add("Mcp-Session-Id", "transport-test");
                reply(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{}}");
                return;
            }
            if (method.startsWith("notifications/"))
            {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            String tool = Json.str(Json.obj(request, "params"), "name");
            if ("mutate_then_disconnect".equals(tool))
            {
                mutations.incrementAndGet();
                return;
            }
            if ("stall".equals(tool) || "slow_headers_and_body".equals(tool))
            {
                boolean slow = "slow_headers_and_body".equals(tool);
                if (slow)
                {
                    releaseBody.await(650, TimeUnit.MILLISECONDS);
                }
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(' ');
                exchange.getResponseBody().flush();
                if (slow)
                {
                    releaseBody.await(650, TimeUnit.MILLISECONDS);
                }
                else
                {
                    releaseBody.await();
                }
                return;
            }
            String result = "list_projects".equals(tool)
                ? "{\"content\":[],\"structuredContent\":{\"success\":true,\"projects\":[]}}" : "{}";
            reply(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        finally
        {
            exchange.close();
        }
    }

    private static String toolRequest(String name)
    {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
            + name + "\",\"arguments\":{}}}";
    }

    private static void reply(HttpExchange exchange, String body) throws IOException
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
