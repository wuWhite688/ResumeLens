package com.arthur.jdragresume.rag;

import com.arthur.jdragresume.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class TextChunker {
    private static final Pattern SECTION_HINT = Pattern.compile(
            "(?m)^\\s*(技能|专业技能|技术栈|工作经历|工作经验|项目经历|项目经验|教育|教育背景|自我评价|其他|证书|获奖)[:：]?\\s*$"
    );

    private final RagProperties properties;

    public TextChunker(RagProperties properties) {
        this.properties = properties;
    }

    public List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        int chunkSize = Math.max(160, properties.getChunkSize());
        int overlap = Math.max(0, Math.min(properties.getChunkOverlap(), chunkSize / 2));
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < normalized.length()) {
            int hardEnd = Math.min(start + chunkSize, normalized.length());
            int end = chooseBoundary(normalized, start, hardEnd);
            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (chunks.size() > Math.max(1, properties.getMaxChunks())) {
                throw new BusinessException(
                        "RESUME_TEXT_TOO_LONG",
                        "resume text exceeds the maximum of " + properties.getMaxChunks() + " chunks"
                );
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return chunks;
    }

    static String detectSection(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return "正文";
        }
        var matcher = SECTION_HINT.matcher(chunk);
        if (matcher.find()) {
            return normalizeSection(matcher.group(1));
        }
        String head = chunk.length() > 40 ? chunk.substring(0, 40) : chunk;
        if (head.contains("技能") || head.contains("技术栈")) {
            return "技能";
        }
        if (head.contains("项目")) {
            return "项目";
        }
        if (head.contains("工作") || head.contains("任职")) {
            return "工作经历";
        }
        if (head.contains("教育") || head.contains("本科") || head.contains("硕士")) {
            return "教育";
        }
        return "正文";
    }

    private static String normalizeSection(String raw) {
        if (raw.contains("技能") || raw.contains("技术栈")) {
            return "技能";
        }
        if (raw.contains("项目")) {
            return "项目";
        }
        if (raw.contains("工作")) {
            return "工作经历";
        }
        if (raw.contains("教育")) {
            return "教育";
        }
        if (raw.contains("自我评价")) {
            return "自我评价";
        }
        if (raw.contains("证书") || raw.contains("获奖")) {
            return "证书";
        }
        return raw;
    }

    private int chooseBoundary(String text, int start, int hardEnd) {
        if (hardEnd >= text.length()) {
            return text.length();
        }
        int minimum = start + ((hardEnd - start) / 2);
        for (int i = hardEnd; i > minimum; i--) {
            char value = text.charAt(i - 1);
            if (value == '\n' || Character.isWhitespace(value) || value == '.' || value == '。' || value == ';' || value == '；') {
                return i;
            }
        }
        return hardEnd;
    }
}
