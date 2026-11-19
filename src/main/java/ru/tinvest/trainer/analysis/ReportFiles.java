package ru.tinvest.trainer.analysis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/**
 * Файлы выгрузки отчёта из листов {@link ReportSheets}: XLSX (fastexcel, ячейки — строки отчёта как есть, чтобы деньги
 * не прошли через {@code double}) и CSV в ZIP, по файлу на лист: UTF-8 с BOM, разделитель {@code ;}, кавычки по RFC 4180,
 * строки через CRLF.
 */
final class ReportFiles {

    private static final String BOM = "﻿";

    private ReportFiles() {}

    static byte[] xlsx(List<ReportSheets.Sheet> sheets) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(out, "Trainer", "1.0")) {
            for (ReportSheets.Sheet sheet : sheets) {
                Worksheet ws = workbook.newWorksheet(sheet.name());
                for (int c = 0; c < sheet.header().size(); c++) {
                    ws.value(0, c, sheet.header().get(c));
                    ws.style(0, c).bold().set();
                }
                for (int r = 0; r < sheet.rows().size(); r++) {
                    List<ReportSheets.Cell> row = sheet.rows().get(r);
                    for (int c = 0; c < row.size(); c++) {
                        ReportSheets.Cell cell = row.get(c);
                        if (cell.number() != null) {
                            ws.value(r + 1, c, cell.number());
                            if (cell.format() != null) {
                                ws.style(r + 1, c).format(cell.format()).set();
                            }
                        } else if (!cell.text().isEmpty()) {
                            ws.value(r + 1, c, safeText(cell));
                        }
                    }
                }
                ws.freezePane(0, 1);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] csvZip(List<ReportSheets.Sheet> sheets) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (ReportSheets.Sheet sheet : sheets) {
                zip.putNextEntry(new ZipEntry(sheet.name() + ".csv"));
                zip.write(csv(sheet).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static String csv(ReportSheets.Sheet sheet) {
        StringBuilder sb = new StringBuilder(BOM);
        line(sb, sheet.header());
        sheet.rows().forEach(row -> line(sb, texts(row)));
        return sb.toString();
    }

    private static void line(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(quote(cells.get(i)));
        }
        sb.append("\r\n");
    }

    private static List<String> texts(List<ReportSheets.Cell> cells) {
        return cells.stream().map(ReportFiles::safeText).toList();
    }

    /**
     * Защита от формул при открытии в Excel: текст, начинающийся с {@code = + - @}, табуляции или CR, получает префикс
     * {@code '}. Числовые ячейки (в том числе отрицательные) не трогаются.
     */
    static String safeText(ReportSheets.Cell cell) {
        String text = cell.text();
        if (cell.number() == null && !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            return "'" + text;
        }
        return text;
    }

    private static String quote(String cell) {
        if (cell.indexOf(';') < 0 && cell.indexOf('"') < 0 && cell.indexOf('\n') < 0 && cell.indexOf('\r') < 0) {
            return cell;
        }
        return "\"" + cell.replace("\"", "\"\"") + "\"";
    }
}
