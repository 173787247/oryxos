package io.oryxos.knowledge.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.knowledge.KnowledgeImportException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentParserTest {

  @TempDir Path root;

  @Test
  void markdownAndTextParseAsSingleUnit() throws IOException {
    Path md = root.resolve("guide.md");
    Files.writeString(md, "# 标题\n\n内容");
    List<ParsedUnit> units = new MarkdownParser().parse(md);
    assertEquals(1, units.size());
    assertEquals(null, units.get(0).pageNo());

    Path txt = root.resolve("note.txt");
    Files.writeString(txt, "纯文本内容");
    assertEquals(1, new TextParser().parse(txt).size());

    Path empty = root.resolve("empty.md");
    Files.writeString(empty, "  \n ");
    assertTrue(new MarkdownParser().parse(empty).isEmpty(), "空文档返回空列表");
  }

  @Test
  void textParserRejectsBinaryContentReadably() throws IOException {
    Path binary = root.resolve("fake.txt");
    Files.write(binary, new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0x00, (byte) 0xFF});
    KnowledgeImportException rejected =
        assertThrows(KnowledgeImportException.class, () -> new TextParser().parse(binary));
    assertTrue(rejected.getMessage().contains("UTF-8"));
  }

  @Test
  void pdfParsesPerPageWithPageNumbers() throws IOException {
    Path pdf = root.resolve("manual.pdf");
    try (PDDocument document = new PDDocument()) {
      writePage(document, "disk alert handling step one");
      writePage(document, "network failure handling");
      document.save(pdf.toFile());
    }

    List<ParsedUnit> units = new PdfParser().parse(pdf);

    assertEquals(2, units.size());
    assertEquals(1, units.get(0).pageNo());
    assertEquals(2, units.get(1).pageNo());
    assertTrue(units.get(0).text().contains("disk alert"));
  }

  @Test
  void scannedPdfWithoutTextLayerIsRejectedAtImport() throws IOException {
    Path scanned = root.resolve("scan.pdf");
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      document.save(scanned.toFile());
    }

    KnowledgeImportException rejected =
        assertThrows(KnowledgeImportException.class, () -> new PdfParser().parse(scanned));
    assertTrue(rejected.getMessage().contains("扫描件"), "拒绝原因必须明确指向扫描件（SC-010）");
  }

  private static void writePage(PDDocument document, String text) throws IOException {
    PDPage page = new PDPage();
    document.addPage(page);
    try (PDPageContentStream content = new PDPageContentStream(document, page)) {
      content.beginText();
      content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
      content.newLineAtOffset(50, 700);
      content.showText(text);
      content.endText();
    }
  }
}
