package vn.techzone.backend;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lazily started local language model; unavailable/busy models do not block catalogue advice. */
public final class AdvisorChat implements AutoCloseable {
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(), r -> {
        Thread t=new Thread(r,"techzone-chat-model"); t.setDaemon(true); return t;
    },new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean starting=new AtomicBoolean();
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t=new Thread(r,"techzone-chat-watchdog"); t.setDaemon(true); return t;
    });
    private volatile Process process;
    private volatile boolean ready,closed;
    private volatile String modelName="";
    private volatile long retryAfter;
    private BufferedReader input;
    private BufferedWriter output;

    public void start() {
        if(closed || ready || System.nanoTime()<retryAfter || !starting.compareAndSet(false,true)) return;
        try { worker.execute(() -> {
            try {
                Path root=Path.of("").toAbsolutePath().normalize();
                Path python=root.resolve(".venv-ai/Scripts/python.exe"), script=root.resolve("ai/chat_worker.py");
                if(!Files.isRegularFile(python) || !Files.isRegularFile(script) || !modelInstalled(root)) return;
                Files.createDirectories(root.resolve("data"));
                ProcessBuilder builder=new ProcessBuilder(python.toString(),"-u",script.toString()).directory(root.toFile());
                builder.environment().put("PYTHONUTF8","1");
                builder.redirectError(ProcessBuilder.Redirect.appendTo(root.resolve("data/chat-model.log").toFile()));
                Process child=builder.start(); process=child;
                if(closed) { child.destroy(); return; }
                ScheduledFuture<?> timeout=watchdog.schedule(() -> { if(!ready && child.isAlive()) child.destroyForcibly(); },180,TimeUnit.SECONDS);
                input=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8));
                output=new BufferedWriter(new OutputStreamWriter(child.getOutputStream(),StandardCharsets.UTF_8));
                String greeting=input.readLine();
                timeout.cancel(false);
                modelName=greeting!=null && greeting.startsWith("READY\t") ? greeting.substring(6) : "";
                ready=java.util.Set.of("Qwen3-4B-Instruct-2507","Qwen2.5-1.5B-Instruct").contains(modelName) && child.isAlive() && !closed;
                if(!ready) child.destroy();
            } catch(IOException | RuntimeException ex) { ready=false; if(process!=null)process.destroy(); }
            finally { if(!ready && !closed) retryAfter=System.nanoTime()+TimeUnit.SECONDS.toNanos(30); starting.set(false); }
        }); } catch(RejectedExecutionException ex) { starting.set(false); }
    }
    private static boolean modelInstalled(Path root) {
        return Files.isRegularFile(root.resolve("models/qwen3-chat/.techzone-ready"))
                || Files.isRegularFile(root.resolve("models/qwen-chat/.techzone-ready"));
    }
    public String status() {
        if(ready && (process==null || !process.isAlive())) { ready=false; retryAfter=System.nanoTime()+TimeUnit.SECONDS.toNanos(30); }
        if(ready) return modelName;
        if(!Files.isRegularFile(Path.of(".venv-ai/Scripts/python.exe"))) return "Chưa cài môi trường AI";
        if(!Files.isRegularFile(Path.of("ai/chat_worker.py"))) return "Thiếu chương trình hội thoại";
        if(!modelInstalled(Path.of(""))) return "Chưa cài mô hình hội thoại";
        if(System.nanoTime()<retryAfter) return "Mô hình chưa sẵn sàng · đang chờ thử lại";
        start(); return "Đang khởi động mô hình hội thoại";
    }
    public String reply(String message,boolean generalQuestion) {
        status();
        if(!ready) return null;
        try {
            Future<String> task=worker.submit(() -> {
                ScheduledFuture<?> timeout=null;
                try {
                    if(process==null || !process.isAlive()) throw new IOException("Model stopped");
                    Process child=process;
                    timeout=watchdog.schedule(() -> { if(child.isAlive()) child.destroyForcibly(); },190,TimeUnit.SECONDS);
                    output.write(generalQuestion?"question\t":"shopping\t");
                    output.write(Base64.getEncoder().encodeToString(message.getBytes(StandardCharsets.UTF_8))); output.newLine(); output.flush();
                    String line=input.readLine();
                    String[] fields=line==null?new String[0]:line.split("\\t",-1);
                    if(fields.length!=2 || !"OK".equals(fields[0])) throw new IOException("Invalid model response");
                    String text=new String(Base64.getDecoder().decode(fields[1]),StandardCharsets.UTF_8).trim();
                    if(text.isBlank() || text.length()>1500) return null;
                    return text;
                } catch(IOException | RuntimeException ex) { ready=false; retryAfter=System.nanoTime()+TimeUnit.SECONDS.toNanos(30); if(process!=null)process.destroy(); return null; }
                finally { if(timeout!=null) timeout.cancel(false); }
            });
            try { return task.get(180,TimeUnit.SECONDS); }
            catch(TimeoutException ex) { return null; }
            catch(InterruptedException ex) { Thread.currentThread().interrupt(); return null; }
            catch(ExecutionException ex) { return null; }
        } catch(RejectedExecutionException ex) { return null; }
    }
    @Override public void close() { closed=true; ready=false; if(process!=null) process.destroy(); worker.shutdownNow(); watchdog.shutdownNow(); }
}
