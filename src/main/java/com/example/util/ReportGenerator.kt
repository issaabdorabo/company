package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.example.data.model.WarehouseItemStock
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportGenerator {

    enum class ReportColumn(val id: String, val titleAr: String) {
        SERIAL("serial", "م"),
        VOUCHER_NUMBER("voucher_num", "رقم الحركة"),
        ITEM_NAME("name", "اسم الصنف"),
        ITEM_UNIT("unit", "الوحدة"),
        WAREHOUSE("warehouse", "المخزن"),
        BENEFICIARY("beneficiary", "الجهة المستفيدة"),
        RECEIVER("receiver", "المستلم"),
        DISBURSER("disburser", "القائم بالصرف"),
        VOUCHER_DATE("date", "تاريخ الحركة"),
        CURRENT_STOCK("stock", "الكمية"),
        TOTAL_QUANTITY("total_quantity", "إجمالي الكمية"),
        MIN_STOCK("min_stock", "حد الطلب"),
        PURCHASE_PRICE("price", "السعر"),
        TOTAL_VALUE("total_value", "الإجمالي")
    }

    data class ReportRowData(
        val voucherNumber: String = "",
        val warehouseName: String = "",
        val itemName: String = "",
        val itemUnit: String = "حبة",
        val beneficiaryName: String = "",
        val receiverName: String = "",
        val disburserName: String = "",
        val voucherDate: String = "",
        val quantity: Double = 0.0,
        val totalQuantity: Double = 0.0,
        val minStockLevel: Double = 0.0,
        val unitPrice: Double = 0.0,
        val totalValue: Double = 0.0,
        val entityQuantities: Map<String, Double> = emptyMap()
    )

    private data class PdfColDef(
        val column: ReportColumn?,
        val title: String,
        val weight: Float,
        val getValue: (ReportRowData, Int) -> String
    )

    fun generatePdf(
        context: Context,
        title: String,
        warehouseName: String,
        appUserName: String,
        orgName: String,
        rows: List<ReportRowData>,
        selectedColumns: Set<ReportColumn>,
        entityColumns: List<String> = emptyList(),
        customColumnTitles: Map<ReportColumn, String> = emptyMap(),
        columnOrder: List<ReportColumn>? = null
    ): File {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 portrait
        val page = pdfDocument.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        val paint = Paint().apply {
            isAntiAlias = true
        }

        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val currentDate = dateFormat.format(Date())

        val pageWidth = 595f
        val margin = 24f
        val contentWidth = pageWidth - (margin * 2)

        // 1. Header Area (Clean, Right-Aligned RTL)
        paint.color = Color.rgb(30, 41, 59)
        canvas.drawRect(0f, 0f, pageWidth, 5f, paint)

        // Organization Name on top right
        paint.color = Color.rgb(15, 23, 42)
        paint.textSize = 15f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(orgName.ifBlank { "المؤسسة العامة للتوريد والمخازن" }, pageWidth - margin, 38f, paint)

        // Report Title on right
        paint.textSize = 13f
        paint.color = Color.rgb(37, 99, 235) // Primary accent
        canvas.drawText(title, pageWidth - margin, 58f, paint)

        // Subtitle (Warehouse & Date) on right
        paint.textSize = 9.5f
        paint.color = Color.rgb(100, 116, 139)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("المخزن: $warehouseName  |  تاريخ التقرير: $currentDate", pageWidth - margin, 74f, paint)

        // Subtle divider line
        paint.color = Color.rgb(226, 232, 240)
        canvas.drawLine(margin, 86f, pageWidth - margin, 86f, paint)

        // 2. Table Setup (RTL ordered columns)
        val orderedBase = columnOrder ?: ReportColumn.values().toList()
        val baseColumns = orderedBase.filter { selectedColumns.contains(it) }
        val pdfCols = mutableListOf<PdfColDef>()

        for (col in baseColumns) {
            val colTitle = customColumnTitles[col] ?: col.titleAr
            val weight = when (col) {
                ReportColumn.SERIAL -> 0.5f
                ReportColumn.ITEM_NAME -> 2.4f
                ReportColumn.ITEM_UNIT -> 0.8f
                ReportColumn.VOUCHER_NUMBER -> 1.4f
                ReportColumn.BENEFICIARY -> 1.3f
                ReportColumn.RECEIVER -> 1.3f
                ReportColumn.WAREHOUSE -> 1.3f
                else -> 1.0f
            }

            if (col == ReportColumn.BENEFICIARY && entityColumns.isNotEmpty()) {
                for (ent in entityColumns) {
                    pdfCols.add(
                        PdfColDef(column = col, title = ent, weight = 1.0f) { row, _ ->
                            val q = row.entityQuantities[ent] ?: if (row.beneficiaryName == ent) row.quantity else null
                            if (q != null && q > 0) "%.0f".format(q) else "-"
                        }
                    )
                }
            } else {
                pdfCols.add(
                    PdfColDef(column = col, title = colTitle, weight = weight) { row, index ->
                        when (col) {
                            ReportColumn.SERIAL -> "${index + 1}"
                            ReportColumn.VOUCHER_NUMBER -> row.voucherNumber
                            ReportColumn.ITEM_NAME -> row.itemName
                            ReportColumn.ITEM_UNIT -> row.itemUnit
                            ReportColumn.WAREHOUSE -> row.warehouseName
                            ReportColumn.BENEFICIARY -> row.beneficiaryName
                            ReportColumn.RECEIVER -> row.receiverName
                            ReportColumn.DISBURSER -> row.disburserName
                            ReportColumn.VOUCHER_DATE -> row.voucherDate
                            ReportColumn.CURRENT_STOCK -> "%.0f".format(row.quantity)
                            ReportColumn.TOTAL_QUANTITY -> "%.0f".format(row.totalQuantity)
                            ReportColumn.MIN_STOCK -> "%.0f".format(row.minStockLevel)
                            ReportColumn.PURCHASE_PRICE -> "%.2f".format(row.unitPrice)
                            ReportColumn.TOTAL_VALUE -> "%.2f".format(row.totalValue)
                        }
                    }
                )
            }
        }

        if (entityColumns.isNotEmpty() && baseColumns.none { it == ReportColumn.BENEFICIARY }) {
            for (ent in entityColumns) {
                pdfCols.add(
                    PdfColDef(column = null, title = ent, weight = 1.0f) { row, _ ->
                        val q = row.entityQuantities[ent] ?: if (row.beneficiaryName == ent) row.quantity else null
                        if (q != null && q > 0) "%.0f".format(q) else "-"
                    }
                )
            }
        }

        val totalWeight = if (pdfCols.isNotEmpty()) pdfCols.sumOf { it.weight.toDouble() }.toFloat() else 1f
        val colWidths = pdfCols.map { (it.weight / totalWeight) * contentWidth }

        var currentY = 112f

        // Table Header Background
        paint.color = Color.rgb(241, 245, 249)
        canvas.drawRect(margin, currentY - 16f, pageWidth - margin, currentY + 8f, paint)

        // Table Header Text
        paint.color = Color.rgb(15, 23, 42)
        paint.textSize = 9.5f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.RIGHT

        var cumRightX = pageWidth - margin
        pdfCols.forEachIndexed { index, colDef ->
            val w = colWidths[index]
            val rightX = cumRightX - 4f
            val maxHeaderChars = (w / 5.0f).toInt().coerceAtLeast(3)
            val headerText = if (colDef.title.length > maxHeaderChars) colDef.title.take(maxHeaderChars - 1) + "." else colDef.title
            canvas.drawText(headerText, rightX, currentY, paint)
            cumRightX -= w
        }

        currentY += 22f

        // 3. Table Rows
        paint.textSize = 9f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)

        rows.forEachIndexed { rowIndex, row ->
            if (currentY > 790f) {
                return@forEachIndexed
            }

            if (rowIndex % 2 == 1) {
                paint.color = Color.rgb(248, 250, 252)
                canvas.drawRect(margin, currentY - 14f, pageWidth - margin, currentY + 6f, paint)
            }

            paint.color = Color.rgb(241, 245, 249)
            canvas.drawLine(margin, currentY + 6f, pageWidth - margin, currentY + 6f, paint)

            paint.color = Color.rgb(30, 41, 59)
            paint.textAlign = Paint.Align.RIGHT

            var cumRightRowX = pageWidth - margin
            pdfCols.forEachIndexed { index, colDef ->
                val w = colWidths[index]
                val rightX = cumRightRowX - 4f
                val cellText = colDef.getValue(row, rowIndex)
                val maxChars = (w / 4.8f).toInt().coerceAtLeast(3)
                val displayText = if (cellText.length > maxChars) cellText.take(maxChars - 1) + "." else cellText
                canvas.drawText(displayText, rightX, currentY, paint)
                cumRightRowX -= w
            }

            currentY += 19f
        }

        // 4. Bottom Margin Footer
        paint.color = Color.rgb(226, 232, 240)
        canvas.drawLine(margin, 810f, pageWidth - margin, 810f, paint)

        paint.color = Color.rgb(100, 116, 139)
        paint.textSize = 9f

        // Right side footer: User Name
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("المستخدم: $appUserName", pageWidth - margin, 826f, paint)

        // Left side footer: Developer Credit
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("طور بواسطة عيسى عبدربه 771438740", margin, 826f, paint)

        pdfDocument.finishPage(page)

        val reportsDir = File(context.cacheDir, "reports")
        if (!reportsDir.exists()) reportsDir.mkdirs()

        val fileName = "report_${System.currentTimeMillis()}.pdf"
        val file = File(reportsDir, fileName)
        val fos = FileOutputStream(file)
        pdfDocument.writeTo(fos)
        fos.flush()
        fos.close()
        pdfDocument.close()

        return file
    }

    fun generateExcel(
        context: Context,
        title: String,
        warehouseName: String,
        appUserName: String,
        rows: List<ReportRowData>,
        selectedColumns: Set<ReportColumn>,
        entityColumns: List<String> = emptyList(),
        customColumnTitles: Map<ReportColumn, String> = emptyMap(),
        columnOrder: List<ReportColumn>? = null
    ): File {
        val reportsDir = File(context.cacheDir, "reports")
        if (!reportsDir.exists()) reportsDir.mkdirs()

        val fileName = "report_${System.currentTimeMillis()}.csv"
        val file = File(reportsDir, fileName)

        val orderedBase = columnOrder ?: ReportColumn.values().toList()
        val baseColumns = orderedBase.filter { selectedColumns.contains(it) }

        val sb = StringBuilder()
        // UTF-8 BOM for Microsoft Excel Arabic rendering
        sb.append("\uFEFF")

        // Metadata Header
        sb.append("\"$title\"\n")
        sb.append("\"المخزن:\",\"$warehouseName\"\n")
        sb.append("\"المستخدم:\",\"$appUserName\"\n")
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        sb.append("\"تاريخ الاستخراج:\",\"${dateFormat.format(Date())}\"\n")
        sb.append("\"المطور:\",\"طور بواسطة عيسى عبدربه 771438740\"\n\n")

        // Column headers
        val excelHeaders = mutableListOf<String>()
        for (col in baseColumns) {
            val titleText = customColumnTitles[col] ?: col.titleAr
            if (col == ReportColumn.BENEFICIARY && entityColumns.isNotEmpty()) {
                for (ent in entityColumns) {
                    excelHeaders.add("\"$ent\"")
                }
            } else {
                excelHeaders.add("\"$titleText\"")
            }
        }
        if (entityColumns.isNotEmpty() && baseColumns.none { it == ReportColumn.BENEFICIARY }) {
            for (ent in entityColumns) {
                excelHeaders.add("\"$ent\"")
            }
        }
        sb.append(excelHeaders.joinToString(","))
        sb.append("\n")

        // Data rows
        rows.forEachIndexed { index, row ->
            val rowValues = mutableListOf<String>()
            for (col in baseColumns) {
                if (col == ReportColumn.BENEFICIARY && entityColumns.isNotEmpty()) {
                    for (ent in entityColumns) {
                        val q = row.entityQuantities[ent] ?: if (row.beneficiaryName == ent) row.quantity else null
                        val valStr = if (q != null && q > 0) "%.0f".format(q) else "-"
                        rowValues.add("\"$valStr\"")
                    }
                } else {
                    val raw = when (col) {
                        ReportColumn.SERIAL -> "${index + 1}"
                        ReportColumn.VOUCHER_NUMBER -> row.voucherNumber
                        ReportColumn.ITEM_NAME -> row.itemName
                        ReportColumn.ITEM_UNIT -> row.itemUnit
                        ReportColumn.WAREHOUSE -> row.warehouseName
                        ReportColumn.BENEFICIARY -> row.beneficiaryName
                        ReportColumn.RECEIVER -> row.receiverName
                        ReportColumn.DISBURSER -> row.disburserName
                        ReportColumn.VOUCHER_DATE -> row.voucherDate
                        ReportColumn.CURRENT_STOCK -> "%.0f".format(row.quantity)
                        ReportColumn.TOTAL_QUANTITY -> "%.0f".format(row.totalQuantity)
                        ReportColumn.MIN_STOCK -> "%.0f".format(row.minStockLevel)
                        ReportColumn.PURCHASE_PRICE -> "%.2f".format(row.unitPrice)
                        ReportColumn.TOTAL_VALUE -> "%.2f".format(row.totalValue)
                    }
                    rowValues.add("\"${raw.replace("\"", "\"\"")}\"")
                }
            }
            if (entityColumns.isNotEmpty() && baseColumns.none { it == ReportColumn.BENEFICIARY }) {
                for (ent in entityColumns) {
                    val q = row.entityQuantities[ent] ?: if (row.beneficiaryName == ent) row.quantity else null
                    val valStr = if (q != null && q > 0) "%.0f".format(q) else "-"
                    rowValues.add("\"$valStr\"")
                }
            }
            sb.append(rowValues.joinToString(","))
            sb.append("\n")
        }

        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    fun shareFile(context: Context, file: File, mimeType: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    fun viewFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "عرض التقرير"))
    }
}
