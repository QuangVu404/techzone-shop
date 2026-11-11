package vn.techzone.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs the stdlib-only public storefront crawler without loading optional AI models. */
final class GearVNCatalogCrawler {
    private GearVNCatalogCrawler() { }

    static String crawl(Path output,int limit) throws IOException,InterruptedException {
        Path project=Path.of("").toAbsolutePath().normalize();
        Path script=project.resolve("ai").resolve("gearvn_catalog.py").normalize();
        if(!script.getParent().equals(project.resolve("ai")) || !Files.isRegularFile(script))
            throw new IOException("Thiếu crawler ai/gearvn_catalog.py.");
        Path python=project.resolve(".venv-ai").resolve("Scripts").resolve("python.exe");
        List<String> command=new ArrayList<>();
        command.add(Files.isRegularFile(python)?python.toString():"python");
        command.add(script.toString());
        command.add("--output");command.add(output.toAbsolutePath().normalize().toString());
        command.add("--limit");command.add(Integer.toString(limit));
        command.add("--details");
        command.add("--delay");command.add("0.5");
        Path log=Files.createTempFile("techzone-gearvn-crawl-",".log");
        Process process=null;
        try {
            ProcessBuilder builder=new ProcessBuilder(command).directory(project.toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("PYTHONUTF8","1");
            process=builder.start();
            if(!process.waitFor(600,TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Crawl GearVN quá thời gian; catalogue hiện tại được giữ nguyên.");
            }
            String outputText=Files.readString(log,StandardCharsets.UTF_8).trim();
            if(process.exitValue()!=0)
                throw new IOException(outputText.isBlank()?"Không crawl được GearVN; catalogue hiện tại được giữ nguyên.":outputText);
            return outputText;
        } finally {
            if(process!=null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }
}
