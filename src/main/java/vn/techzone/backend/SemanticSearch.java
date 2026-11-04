package vn.techzone.backend;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local pretrained multilingual embeddings. Busy, warming and failed workers return null. */
public final class SemanticSearch implements AutoCloseable {
    public record Document(String id,String text) { }
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),r -> {
        Thread t=new Thread(r,"techzone-semantic-search"); t.setDaemon(true); return t;
    },new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t=new Thread(r,"techzone-semantic-watchdog"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean starting=new AtomicBoolean();
    private volatile Process process;
    private volatile boolean ready,closed;
    private volatile String modelName="";
    private volatile long retryAfterNanos;
    private BufferedReader input;
    private BufferedWriter output;
    private final Object requestLock=new Object();
    private String pendingKey;
    private Future<Map<String,Double>> pendingTask;
    public SemanticSearch() { start(); }
    private void start() {
        if(closed || ready || System.nanoTime()<retryAfterNanos || !starting.compareAndSet(false,true)) return;
        try {
            worker.execute(() -> {
                try {
                    Path root=Path.of("").toAbsolutePath().normalize();
                    Path python=root.resolve(".venv-ai").resolve("Scripts").resolve("python.exe");
                    Path script=root.resolve("ai").resolve("semantic_search.py");
                    Path strong=root.resolve("models").resolve("bge-m3").resolve(".techzone-ready");
                    Path fallback=root.resolve("models").resolve("semantic-mini").resolve("model.safetensors");
                    if(!Files.isRegularFile(python) || !Files.isRegularFile(script) || (!Files.isRegularFile(strong) && !Files.isRegularFile(fallback))) return;
                    Path data=root.resolve("data"); Files.createDirectories(data);
                    ProcessBuilder builder=new ProcessBuilder(python.toString(),"-u",script.toString()).directory(root.toFile());
                    builder.redirectError(ProcessBuilder.Redirect.appendTo(data.resolve("semantic-search.log").toFile()));
                    builder.environment().put("PYTHONUTF8","1");
                    Process child=builder.start(); process=child;
                    if(closed) { child.destroy(); return; }
                    input=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8));
                    output=new BufferedWriter(new OutputStreamWriter(child.getOutputStream(),StandardCharsets.UTF_8));
                    watchdog.schedule(() -> { if(!ready && child.isAlive()) child.destroyForcibly(); },180,TimeUnit.SECONDS);
                    String greeting=input.readLine();
                    if(greeting!=null && greeting.startsWith("READY\t")) modelName=greeting.substring(6);
                    ready=Set.of("BGE-M3","MiniLM").contains(modelName) && child.isAlive() && !closed;
                    if(ready) synchronized(requestLock) { pendingKey=null; pendingTask=null; }
                    if(!ready) child.destroy();
                } catch(IOException | RuntimeException ex) { ready=false; Process child=process; if(child!=null) child.destroy(); }
                finally {
                    if(!ready && !closed) retryAfterNanos=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                    starting.set(false);
                }
            });
        } catch(RejectedExecutionException ex) { starting.set(false); }
    }
    private static String enc(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    public Map<String,Double> rank(String query,List<Document> documents) {
        return rank(query,documents,2500);
    }
    public Map<String,Double> rank(String query,List<Document> documents,long timeoutMs) {
        if(closed || query==null || query.isBlank() || documents.isEmpty()) return null;
        if(!ready()) { start(); return null; }
        try {
            StringBuilder line=new StringBuilder(enc(query)); Set<String> allowed=new HashSet<>();
            for(Document doc:documents) { allowed.add(doc.id()); line.append('\t').append(enc(doc.id())).append('\t').append(enc(doc.text())); }
            String request=line.toString();
            Future<Map<String,Double>> task;
            synchronized(requestLock) {
                if(request.equals(pendingKey) && pendingTask!=null) task=pendingTask;
                else {
                    task=worker.submit(() -> {
                ScheduledFuture<?> timeout=null;
                try {
                    Process child=process;
                    if(child==null || !child.isAlive()) throw new IOException("Worker stopped");
                    timeout=watchdog.schedule(() -> { if(child.isAlive()) child.destroyForcibly(); },180,TimeUnit.SECONDS);
                    output.write(request); output.newLine(); output.flush();
                    String response=input.readLine(); if(response==null) throw new IOException("Worker stopped");
                    String[] parts=response.split("\\t",-1);
                    if(parts.length!=1+documents.size()*2 || !"OK".equals(parts[0])) throw new IOException("Invalid worker response");
                    Map<String,Double> scores=new HashMap<>();
                    for(int i=1;i<parts.length;i+=2) {
                        String id=new String(Base64.getDecoder().decode(parts[i]),StandardCharsets.UTF_8); double score=Double.parseDouble(parts[i+1]);
                        if(!allowed.contains(id) || !Double.isFinite(score) || scores.putIfAbsent(id,score)!=null) throw new IOException("Invalid score");
                    }
                    return Map.copyOf(scores);
                } catch(IOException | RuntimeException ex) {
                    ready=false; retryAfterNanos=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                    Process child=process; if(child!=null) child.destroy(); return null;
                }
                finally { if(timeout!=null) timeout.cancel(false); }
                    });
                    pendingKey=request; pendingTask=task;
                }
            }
            try {
                Map<String,Double> result=task.get(Math.max(1,timeoutMs),TimeUnit.MILLISECONDS);
                if(result==null) synchronized(requestLock) { if(pendingTask==task) { pendingTask=null; pendingKey=null; } }
                return result;
            }
            catch(TimeoutException ex) { return null; }
            catch(InterruptedException ex) { Thread.currentThread().interrupt(); return null; }
            catch(ExecutionException ex) { return null; }
        } catch(RejectedExecutionException ex) { return null; }
    }
    public boolean ready() {
        Process child=process;
        if (ready && (child==null || !child.isAlive())) {
            ready=false;
            retryAfterNanos=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        }
        return ready;
    }
    public String modelName() { return ready() ? modelName : ""; }
    /** Status checks also restart a failed worker after the retry delay. */
    public String status() {
        if (closed) return "Dịch vụ đã dừng";
        if (ready()) return modelName;
        Path root=Path.of("").toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve(".venv-ai/Scripts/python.exe")))
            return "Chưa cài môi trường AI · chạy setup-ai.bat";
        if (!Files.isRegularFile(root.resolve("ai/semantic_search.py")))
            return "Thiếu chương trình tìm kiếm AI";
        if (!Files.isRegularFile(root.resolve("models/bge-m3/.techzone-ready"))
                && !Files.isRegularFile(root.resolve("models/semantic-mini/model.safetensors")))
            return "Chưa cài mô hình · chạy setup-ai.bat";
        if (starting.get()) return "Đang khởi động mô hình AI";
        if (System.nanoTime()<retryAfterNanos)
            return "Mô hình chưa sẵn sàng · đang chờ thử lại";
        start();
        return "Đang khởi động mô hình AI";
    }
    @Override public void close() {
        closed=true; ready=false; Process child=process; if(child!=null) child.destroy();
        worker.shutdownNow(); watchdog.shutdownNow();
    }
}
