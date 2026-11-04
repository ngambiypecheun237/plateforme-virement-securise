package com.banque.virement.service;

import com.banque.virement.domain.entity.Transfer;
import com.banque.virement.domain.enums.TransferType;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Generation des avis d'operation (PDF) et export de l'historique (PDF / Excel).
 */
@Service
public class ExportService {

    private static final ZoneId ZONE = ZoneId.of("Africa/Douala");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZONE);
    private static final DateTimeFormatter LONG_FMT =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'a' HH:mm", java.util.Locale.FRANCE).withZone(ZONE);

    private final ReceiptVerificationService verificationService;

    public ExportService(ReceiptVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    // ------------------------------------------------------------- avis PDF

    public byte[] receiptPdf(Transfer t) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 50, 50, 60, 60);
            PdfWriter.getInstance(document, out);
            document.open();

            Font bankFont = new Font(Font.HELVETICA, 18, Font.BOLD, new java.awt.Color(15, 76, 129));
            Font subFont = new Font(Font.HELVETICA, 9, Font.NORMAL, java.awt.Color.GRAY);
            Font titleFont = new Font(Font.HELVETICA, 14, Font.BOLD, new java.awt.Color(15, 76, 129));
            Font labelFont = new Font(Font.HELVETICA, 9, Font.BOLD, java.awt.Color.DARK_GRAY);
            Font valueFont = new Font(Font.HELVETICA, 10, Font.NORMAL);

            Paragraph bank = new Paragraph("BANQUE DEMONSTRATION S.A.", bankFont);
            bank.setAlignment(Element.ALIGN_CENTER);
            document.add(bank);
            Paragraph sub = new Paragraph("Siège social : Boulevard de la Liberté, Douala — Cameroun | RC/DLA/1998/B/000001 | Agréée COBAC",
                    subFont);
            sub.setAlignment(Element.ALIGN_CENTER);
            document.add(sub);
            Paragraph sub2 = new Paragraph("Plateforme de Virement Bancaire Sécurisé — Groupe 9", subFont);
            sub2.setAlignment(Element.ALIGN_CENTER);
            sub2.setSpacingAfter(16);
            document.add(sub2);

            Paragraph title = new Paragraph("REÇU DE VIREMENT", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(2);
            document.add(title);
            Paragraph titleSub = new Paragraph("À imprimer et à remettre au client", subFont);
            titleSub.setAlignment(Element.ALIGN_CENTER);
            titleSub.setSpacingAfter(16);
            document.add(titleSub);

            PdfPTable table = new PdfPTable(2);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{35, 65});
            addRow(table, "Reference", t.getReference(), labelFont, valueFont);
            addRow(table, "Date et heure", LONG_FMT.format(t.getCreatedAt()), labelFont, valueFont);
            addRow(table, "Type", typeLabel(t.getType()), labelFont, valueFont);
            addRow(table, "Statut", t.getStatus().name(), labelFont, valueFont);
            addRow(table, "Compte débité", t.getSourceAccount().getIban(), labelFont, valueFont);
            addRow(table, "Bénéficiaire", t.getBeneficiary() != null ? t.getBeneficiary().getName()
                    : (t.getTargetAccount() != null ? "Compte propre " + t.getTargetAccount().getIban() : "-"), labelFont, valueFont);
            addRow(table, "IBAN destinataire", t.getBeneficiary() != null ? t.getBeneficiary().getIban()
                    : (t.getTargetAccount() != null ? t.getTargetAccount().getIban() : "-"), labelFont, valueFont);
            addRow(table, "Montant", formatAmount(t.getAmount()) + " " + t.getCurrency(), labelFont, valueFont);
            addRow(table, "Motif", t.getMotif() != null ? t.getMotif() : "-", labelFont, valueFont);
            if (t.getExecutedAt() != null) {
                addRow(table, "Exécuté le", FMT.format(t.getExecutedAt()), labelFont, valueFont);
            }
            document.add(table);

            // Code de verification anti-falsification (HMAC-SHA256 tronque)
            String verifyCode = verificationService.codeFor(t.getReference(), t.getAmount().toPlainString());
            Paragraph verify = new Paragraph("Code de vérification : " + verifyCode,
                    new Font(Font.HELVETICA, 12, Font.BOLD, new java.awt.Color(15, 76, 129)));
            verify.setAlignment(Element.ALIGN_CENTER);
            verify.setSpacingBefore(14);
            document.add(verify);
            Paragraph verifyHelp = new Paragraph("Authentification du reçu : référence + montant + code — via /api/v1/public/verify",
                    subFont);
            verifyHelp.setAlignment(Element.ALIGN_CENTER);
            verifyHelp.setSpacingAfter(6);
            document.add(verifyHelp);

            // Ligne de decoupe (recu agence)
            Paragraph cut = new Paragraph("- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -",
                    new Font(Font.HELVETICA, 8, Font.NORMAL, java.awt.Color.GRAY));
            cut.setAlignment(Element.ALIGN_CENTER);
            cut.setSpacingBefore(10);
            document.add(cut);
            Paragraph footer = new Paragraph(
                    "Document généré électroniquement par la plateforme de virement sécurisé. Il fait foi de l'opération initiée. "
                            + "Conservez-le comme justificatif. Pour toute contestation, contactez votre agence sous 8 jours "
                            + "en présentant la référence ci-dessus et le code de vérification.", subFont);
            footer.setSpacingBefore(10);
            document.add(footer);

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Erreur lors de la generation du PDF", e);
        }
    }

    // -------------------------------------------------------- export PDF liste

    public byte[] historyPdf(List<Transfer> transfers) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 36, 36, 40, 40);
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = new Font(Font.HELVETICA, 14, Font.BOLD, new java.awt.Color(15, 76, 129));
            Font cellFont = new Font(Font.HELVETICA, 8, Font.NORMAL);
            Font headFont = new Font(Font.HELVETICA, 8, Font.BOLD, java.awt.Color.WHITE);

            Paragraph title = new Paragraph("Historique des opérations de virement", titleFont);
            title.setSpacingAfter(12);
            document.add(title);

            PdfPTable table = new PdfPTable(7);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{18, 12, 10, 20, 16, 12, 12});
            String[] headers = {"Référence", "Date et heure", "Statut", "Bénéficiaire / Compte", "Compte débité", "Montant", "Devise"};
            for (String h : headers) {
                com.lowagie.text.pdf.PdfPCell cell = new com.lowagie.text.pdf.PdfPCell(new Phrase(h, headFont));
                cell.setBackgroundColor(new java.awt.Color(15, 76, 129));
                cell.setPadding(4);
                table.addCell(cell);
            }
            for (Transfer t : transfers) {
                table.addCell(cell(t.getReference(), cellFont));
                table.addCell(cell(FMT.format(t.getCreatedAt()), cellFont));
                table.addCell(cell(t.getStatus().name(), cellFont));
                table.addCell(cell(t.getBeneficiary() != null ? t.getBeneficiary().getName()
                        : (t.getTargetAccount() != null ? t.getTargetAccount().getIban() : "-"), cellFont));
                table.addCell(cell(t.getSourceAccount().getIban(), cellFont));
                table.addCell(cell(formatAmount(t.getAmount()), cellFont));
                table.addCell(cell(t.getCurrency(), cellFont));
            }
            document.add(table);
            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Erreur lors de la generation de l'export PDF", e);
        }
    }

    // ------------------------------------------------------ export Excel liste

    public byte[] historyXlsx(List<Transfer> transfers) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Virements");

            CellStyle headerStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle amountStyle = workbook.createCellStyle();
            DataFormat df = workbook.createDataFormat();
            amountStyle.setDataFormat(df.getFormat("#,##0.00"));

            String[] headers = {"Reference", "Date", "Type", "Statut", "Compte debite", "Beneficiaire", "Montant", "Devise", "Motif"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int r = 1;
            for (Transfer t : transfers) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(t.getReference());
                row.createCell(1).setCellValue(FMT.format(t.getCreatedAt()));
                row.createCell(2).setCellValue(t.getType().name());
                row.createCell(3).setCellValue(t.getStatus().name());
                row.createCell(4).setCellValue(t.getSourceAccount().getIban());
                row.createCell(5).setCellValue(t.getBeneficiary() != null ? t.getBeneficiary().getName()
                        : (t.getTargetAccount() != null ? t.getTargetAccount().getIban() : "-"));
                Cell amountCell = row.createCell(6);
                amountCell.setCellValue(t.getAmount().doubleValue());
                amountCell.setCellStyle(amountStyle);
                row.createCell(7).setCellValue(t.getCurrency());
                row.createCell(8).setCellValue(t.getMotif() != null ? t.getMotif() : "");
            }
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Erreur lors de la generation de l'export Excel", e);
        }
    }

    private static com.lowagie.text.pdf.PdfPCell cell(String value, Font font) {
        com.lowagie.text.pdf.PdfPCell cell = new com.lowagie.text.pdf.PdfPCell(new Phrase(value != null ? value : "-", font));
        cell.setPadding(3);
        return cell;
    }

    private static void addRow(PdfPTable table, String label, String value, Font labelFont, Font valueFont) {
        com.lowagie.text.pdf.PdfPCell labelCell = new com.lowagie.text.pdf.PdfPCell(new Phrase(label, labelFont));
        labelCell.setPadding(5);
        labelCell.setBackgroundColor(new java.awt.Color(238, 242, 247));
        com.lowagie.text.pdf.PdfPCell valueCell = new com.lowagie.text.pdf.PdfPCell(new Phrase(value != null ? value : "-", valueFont));
        valueCell.setPadding(5);
        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    private static String formatAmount(BigDecimal amount) {
        return String.format(java.util.Locale.FRANCE, "%,.0f", amount);
    }

    private static String typeLabel(TransferType type) {
        return switch (type) {
            case VIREMENT_UNITAIRE_INTERNE -> "Virement unitaire interne";
            case VIREMENT_UNITAIRE_EXTERNE -> "Virement unitaire externe";
            case VIREMENT_MASSE -> "Virement de masse (entreprise)";
        };
    }
}
