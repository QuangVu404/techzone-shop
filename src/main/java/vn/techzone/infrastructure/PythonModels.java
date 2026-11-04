package vn.techzone.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Invokes local model scripts in an isolated Python process, with no inference network access. */
public final class PythonModels {
    private PythonModels() { }
    public static String run(String scriptName, int timeoutSeconds, String... arguments) throws IOException, InterruptedException {
        Path project = Path.of("").toAbsolutePath().normalize();
        Path script = project.resolve("ai").resolve(scriptName).normalize();
        if (!script.getParent().equals(project.resolve("ai")) || !Files.isRegularFile(script))
            throw new IOException("Không tìm thấy mã mô hình AI; hãy chạy ứng dụng từ thư mục dự án.");
        Path python = project.resolve(".venv-ai").resolve("Scripts").resolve("python.exe");
        if (!Files.isRegularFile(python)) throw new IOException("Chưa cài môi trường AI. Hãy chạy .\\setup-ai.bat.");
        List<String> command = new ArrayList<>(); command.add(python.toString()); command.add(script.toString());
        command.addAll(Arrays.asList(arguments));
        Path stdout = Files.createTempFile("techzone-ai-out-", ".txt");
        Path stderr = Files.createTempFile("techzone-ai-error-", ".txt");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            builder.environment().put("PYTHONUTF8", "1");
            builder.environment().put("HF_HUB_OFFLINE", "1");
            builder.environment().put("TRANSFORMERS_OFFLINE", "1");
            builder.environment().put("TOKENIZERS_PARALLELISM", "false");
            process = builder.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Mô hình AI xử lý quá thời gian. Hãy thử lại.");
            }
            String output = Files.readString(stdout, StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                String error = Files.readString(stderr, StandardCharsets.UTF_8);
                if (error.contains("No speech detected")) throw new IOException("Không nghe rõ giọng nói. Hãy thử lại gần micro hơn.");
                if (error.contains("IMAGE_TOO_LARGE")) throw new IOException("Ảnh có quá 64 triệu điểm ảnh. Hãy chọn ảnh nhỏ hơn.");
                if (error.contains("MISSING_HEIF")) throw new IOException("Chưa cài bộ đọc HEIC/HEIF. Hãy chạy .\\setup-ai.bat.");
                if (error.contains("INVALID_IMAGE")) throw new IOException("Không đọc được ảnh. Tệp có thể bị hỏng hoặc định dạng chưa được hỗ trợ.");
                if (error.contains("Missing") || error.contains("No module named") || error.contains("ModuleNotFoundError"))
                    throw new IOException("Thiếu thư viện hoặc trọng số AI. Hãy chạy .\\setup-ai.bat.");
                throw new IOException("Không chạy được mô hình AI cục bộ. Kiểm tra môi trường Python và dữ liệu đầu vào.");
            }
            return output;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(stdout); Files.deleteIfExists(stderr);
        }
    }
}
