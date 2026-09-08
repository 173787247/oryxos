package io.oryxos.knowledge.index;

import io.oryxos.core.knowledge.KnowledgeImportException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * 文本型 PDF 解析（Apache PDFBox，research D11）：每页一个单元、出处用页码。 全文无文本层（扫描件）在导入时识别拒绝并给出明确原因（FR-003 /
 * SC-010），不静默产出空索引。
 */
public final class PdfParser implements DocumentParser {

  @Override
  public boolean supports(String fileName) {
    return fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
  }

  @Override
  public List<ParsedUnit> parse(Path file) {
    try (PDDocument document = Loader.loadPDF(file.toFile())) {
      List<ParsedUnit> units = new ArrayList<>();
      PDFTextStripper stripper = new PDFTextStripper();
      boolean hasText = false;
      for (int page = 1; page <= document.getNumberOfPages(); page++) {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        String text = stripper.getText(document);
        if (text != null && !text.isBlank()) {
          hasText = true;
          units.add(new ParsedUnit(text, page));
        }
      }
      if (!hasText) {
        throw new KnowledgeImportException(
            "PDF 无文本层（疑似扫描件），拒绝导入: " + file.getFileName() + "。请提供文本型 PDF 或先做 OCR");
      }
      return units;
    } catch (IOException e) {
      throw new KnowledgeImportException("PDF 解析失败: " + file.getFileName(), e);
    }
  }
}
