package com.toolhelper.druid;

import com.toolhelper.config.DruidScanProperties;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLException;

/** 每目标有界生命周期；只允许规则目录和受控登录/跳转，不暴露任意HTTP代理接口。 */
public final class DruidHttpClient implements AutoCloseable {
    public static final class Failure extends Exception {
        public final String code;
        public Failure(String code) { super(code); this.code = code; }
    }
    public record Reply(int status, byte[] body, String contentType, String location, long elapsedMs) {}
    private final DruidScanProperties properties;
    private final URI root;
    private final BooleanSupplier cancelled;
    private final long deadline;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final HttpClient http;
    private final boolean credential;
    private int requests;
    private Reply loginPage;
    public DruidHttpClient(URI root, DruidScanProperties properties, BooleanSupplier cancelled, long deadline, boolean credential) {
        this.root = root; this.properties = properties; this.cancelled = cancelled; this.deadline = deadline; this.credential = credential;
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(properties.requestSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
                });
        http = builder.build();
    }
    public int requests() { return requests; }
    void used(int count) { requests = count; }
    public boolean hasCookie() { return !cookies.getCookieStore().getCookies().isEmpty(); }
    public void check() throws Failure {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new Failure("CANCELLED");
        if (System.nanoTime() >= deadline) throw new Failure("TARGET_TIMEOUT");
    }
    public Reply get(DruidRuleCatalog.Rule rule) throws Failure {
        if (!DruidRuleCatalog.ALL.contains(rule)) throw new IllegalArgumentException("不支持的检测规则");
        URI uri = root.resolve(rule.path());
        for (int redirects = 0;; redirects++) {
            Reply reply = exchange(uri, null);
            if (!Set.of(301, 302, 303, 307, 308).contains(reply.status())) {
                if (uri.getRawPath().equals(root.getRawPath() + "login.html")) loginPage = reply;
                return reply;
            }
            URI next = DruidTargetPolicy.redirect(root, uri, reply.location());
            if (next == null || redirects >= 3) throw new Failure("REDIRECT_RESTRICTED");
            // 同一匿名上下文只读取一次固定登录页指纹；每个原端点的302仍各自记录，避免耗尽预算。
            if (loginPage != null && next.getRawPath().equals(root.getRawPath() + "login.html"))
                return new Reply(reply.status(), loginPage.body(), loginPage.contentType(), reply.location(), reply.elapsedMs());
            uri = next;
        }
    }
    public Reply login(String username, String password) throws Failure {
        if (!credential) throw new IllegalStateException("当前不是凭据验证上下文");
        String form = "loginUsername=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&loginPassword=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        return exchange(root.resolve(DruidRuleCatalog.LOGIN.path()), form);
    }
    private Reply exchange(URI uri, String form) throws Failure {
        check();
        if (requests >= properties.maxRequests()) throw new Failure("REQUEST_LIMIT");
        if (!DruidTargetPolicy.origin(root).equals(DruidTargetPolicy.origin(uri))) throw new IllegalArgumentException("禁止跨源派发");
        long started = System.nanoTime(); requests++;
        long nanos = Math.min(TimeUnit.SECONDS.toNanos(properties.requestSeconds()), deadline - started);
        if (nanos <= 0) throw new Failure("TARGET_TIMEOUT");
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(nanos))
                .header("User-Agent", "ToolHelper-Druid-Audit/1")
                .header("Accept", "application/json,text/html;q=0.9").header("Accept-Encoding", "identity");
        if (form == null) builder.GET(); else builder.header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        try {
            if (credential) cookies.get(uri, Map.of()).forEach((name, values) -> {
                if (name.equalsIgnoreCase("Cookie")) values.forEach(value -> builder.header("Cookie", value));
            });
        } catch (IOException failure) { throw new Failure("COOKIE_INVALID"); }
        var pending = http.sendAsync(builder.build(), info -> {
            String encoding = info.headers().firstValue("Content-Encoding").orElse("identity");
            long length = info.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (!encoding.equalsIgnoreCase("identity")) return new LimitedBody(0, "CONTENT_ENCODING_UNSUPPORTED");
            return new LimitedBody(length > properties.maxResponseBytes() ? 0 : properties.maxResponseBytes(), "RESPONSE_TOO_LARGE");
        });
        try {
            // 整体等待覆盖慢响应体；短轮询只检查取消，不产生额外目标请求。
            HttpResponse<byte[]> response;
            long requestDeadline = started + nanos;
            while (true) {
                check();
                long remaining = requestDeadline - System.nanoTime();
                if (remaining <= 0) throw new Failure("REQUEST_TIMEOUT");
                try { response = pending.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS); break; }
                catch (TimeoutException waiting) { /* 继续检查统一截止时间与取消 */ }
            }
            if (credential) {
                var setCookies = response.headers().allValues("Set-Cookie");
                if (setCookies.stream().mapToInt(String::length).sum() > 8192 || setCookies.size() > 16) throw new Failure("COOKIE_LIMIT");
                cookies.put(uri, response.headers().map());
                if (cookies.getCookieStore().getCookies().size() > 16
                        || cookies.getCookieStore().getCookies().stream().mapToInt(c -> c.toString().length()).sum() > 8192) throw new Failure("COOKIE_LIMIT");
            }
            return new Reply(response.statusCode(), response.body(), response.headers().firstValue("Content-Type").orElse(""),
                    response.headers().firstValue("Location").orElse(null), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new Failure("CANCELLED"); }
        catch (ExecutionException failure) {
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof LimitFailure limit) throw new Failure(limit.code);
                if (cause instanceof SSLException) throw new Failure("TLS_ERROR");
                if (cause instanceof HttpTimeoutException) throw new Failure("REQUEST_TIMEOUT");
            }
            throw new Failure("NETWORK_ERROR");
        } catch (IOException | IllegalArgumentException failure) { throw new Failure("COOKIE_INVALID"); }
        finally { if (!pending.isDone()) pending.cancel(true); }
    }
    @Override public void close() {
        cookies.getCookieStore().removeAll(); http.shutdownNow();
        boolean interrupted = Thread.interrupted();
        // DNS/底层IO若暂不能中断，继续占用工作槽和活跃任务，避免不断制造遗留线程。
        try { while (!http.awaitTermination(Duration.ofMillis(200))) { } }
        catch (InterruptedException failure) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static class LimitFailure extends IOException {
        final String code;
        LimitFailure(String code) { this.code = code; }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private final String code;
        private Flow.Subscription subscription;
        LimitedBody(int limit, String code) { this.limit = limit; this.code = code; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            if (limit == 0) { value.cancel(); result.completeExceptionally(new LimitFailure(code)); }
            else value.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) { subscription.cancel(); result.completeExceptionally(new LimitFailure(code)); return; }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
