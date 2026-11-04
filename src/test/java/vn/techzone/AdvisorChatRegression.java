package vn.techzone;

import vn.techzone.backend.AdvisorChat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Optional integration test that runs the real downloaded local chat model. */
public final class AdvisorChatRegression {
    public static void main(String[] args) throws Exception {
        if (!Files.isRegularFile(Path.of("models/qwen-chat/.techzone-ready"))) {
            System.out.println("Chat model test skipped: run setup-chat-ai.bat"); return;
        }
        try (AdvisorChat chat=new AdvisorChat()) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
            while (!"Qwen2.5-1.5B-Instruct".equals(chat.status()) && System.nanoTime()<deadline) Thread.sleep(500);
            if (!"Qwen2.5-1.5B-Instruct".equals(chat.status())) throw new AssertionError("Chat model did not start; see data/chat-model.log");
            String build=chat.reply("Yêu cầu mới: Tôi muốn cấu hình PC chơi game khoảng 35 triệu.\nThông tin hệ thống: Có gợi ý từ danh mục.",false);
            if (build==null || build.isBlank()) throw new AssertionError("Chat model did not respond to a build request");
            String question=chat.reply("Yêu cầu mới: RAM DDR4 khác DDR5 như thế nào?",true);
            if (question==null || question.isBlank()) throw new AssertionError("Chat model did not answer a general question");
            System.out.println("Real Qwen chat model: Vietnamese conversation passed");
            System.out.println("Build reply: "+build);
            System.out.println("General reply: "+question);
        }
    }
}
