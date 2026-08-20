package com.arthur.jdragresume.service;

import com.arthur.jdragresume.exception.BusinessException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

@Service
public class ResumeTextExtractor {
    static final int MAX_RAW_TEXT_CHARS = 200_000;
    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of("txt", "md");

    private final AutoDetectParser parser = new AutoDetectParser();
    private final ResumeTextQualityValidator qualityValidator;

    public ResumeTextExtractor(ResumeTextQualityValidator qualityValidator) {
        this.qualityValidator = qualityValidator;
    }

    public String extract(MultipartFile file) {
        String extension = extensionOf(file.getOriginalFilename());
        try {
            if (PLAIN_TEXT_EXTENSIONS.contains(extension)) {
                return qualityValidator.validate(readPlainText(file));
            }
            return qualityValidator.validate(parseWithTika(file));
        } catch (BusinessException ex) {
            throw ex;
        } catch (WriteLimitReachedException ex) {
            throw tooLong();
        } catch (IOException | SAXException | TikaException ex) {
            if (WriteLimitReachedException.isWriteLimitReached(ex)) {
                throw tooLong();
            }
            throw new BusinessException("RESUME_PARSE_FAILED", "failed to parse resume text");
        }
    }

    private String readPlainText(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.length() > MAX_RAW_TEXT_CHARS) {
            throw tooLong();
        }
        return text;
    }

    private String parseWithTika(MultipartFile file) throws IOException, SAXException, TikaException {
        try (InputStream inputStream = file.getInputStream()) {
            BodyContentHandler handler = new BodyContentHandler(MAX_RAW_TEXT_CHARS);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            parser.parse(inputStream, handler, metadata, context);
            return handler.toString();
        }
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private static BusinessException tooLong() {
        return new BusinessException(
                "RESUME_TEXT_TOO_LONG",
                "resume text exceeds the maximum of " + MAX_RAW_TEXT_CHARS + " characters"
        );
    }
}
