package com.arthur.jdragresume.service;

import com.arthur.jdragresume.exception.BusinessException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;

@Service
public class ResumeTextExtractor {
    private final AutoDetectParser parser = new AutoDetectParser();
    private final ResumeTextQualityValidator qualityValidator;

    public ResumeTextExtractor(ResumeTextQualityValidator qualityValidator) {
        this.qualityValidator = qualityValidator;
    }

    public String extract(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            parser.parse(inputStream, handler, metadata, context);
            return qualityValidator.validate(handler.toString());
        } catch (IOException | SAXException | TikaException ex) {
            throw new BusinessException("RESUME_PARSE_FAILED", "failed to parse resume text");
        }
    }
}
