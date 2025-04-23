package com.example.berryharvest.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.berryharvest.data.model.Worker
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.itextpdf.text.*
import com.itextpdf.text.pdf.BaseFont
import com.itextpdf.text.pdf.PdfContentByte
import com.itextpdf.text.pdf.PdfPCell
import com.itextpdf.text.pdf.PdfPTable
import com.itextpdf.text.pdf.PdfWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*

class BadgeGenerator(private val context: Context) {

    private val TAG = "BadgeGenerator"

    // Constants for badge dimensions in mm - vertical format
    private val MM_TO_POINTS = 72 / 25.4f // Points per mm in PDF
    private val BADGE_WIDTH_MM = 55.0f  // Width of vertical badge
    private val BADGE_HEIGHT_MM = 90.0f // Height of vertical badge
    private val MARGIN_MM = 5.0f

    // PDF page size
    private val PAGE_SIZE = PageSize.A4

    // Colors - using only black and white
    private val COLOR_BLACK = BaseColor.BLACK
    private val COLOR_WHITE = BaseColor.WHITE

    /**
     * Generate a PDF with badges for all workers
     * @param workers List of Worker objects from database
     * @param outputFilePath Path where the PDF file will be saved
     * @param logoResId Resource ID of the logo to use (optional)
     * @return The File object of the generated PDF
     */
    fun generateBadgesPdf(
        workers: List<Worker>,
        outputFilePath: String,
        logoResId: Int? = null
    ): File {
        val file = File(outputFilePath)

        // Create parent directories if they don't exist
        file.parentFile?.mkdirs()

        // Create PDF document
        val document = Document(PAGE_SIZE)
        val writer = PdfWriter.getInstance(document, FileOutputStream(file))
        document.open()

        // Calculate layout
        val badgeWidth = BADGE_WIDTH_MM * MM_TO_POINTS
        val badgeHeight = BADGE_HEIGHT_MM * MM_TO_POINTS
        val margin = MARGIN_MM * MM_TO_POINTS

        val pageWidth = PAGE_SIZE.width
        val pageHeight = PAGE_SIZE.height

        val availableWidth = pageWidth - 2 * margin
        val availableHeight = pageHeight - 2 * margin

        val cols = (availableWidth / badgeWidth).toInt()
        val rows = (availableHeight / badgeHeight).toInt()

        if (cols == 0 || rows == 0) {
            throw IllegalStateException("Badge size is too large for the page with margins")
        }

        val badgesPerPage = cols * rows

        // Load logo if provided
        val logoBitmap = logoResId?.let {
            try {
                BitmapFactory.decodeResource(context.resources, it)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading logo: ${e.message}")
                null
            }
        }

        // Create fonts
        val titleFont = createFont(16f, Font.BOLD, COLOR_BLACK)
        val normalFont = createFont(12f, Font.NORMAL, COLOR_BLACK)
        val smallFont = createFont(10f, Font.NORMAL, COLOR_BLACK)
        val idFont = createFont(14f, Font.BOLD, COLOR_BLACK)

        // Sort workers by sequence number
        val sortedWorkers = workers.sortedBy { it.sequenceNumber }

        // Create badges
        val contentByte = writer.directContent

        sortedWorkers.forEachIndexed { index, worker ->
            try {
                // Create a new page if needed
                if (index > 0 && index % badgesPerPage == 0) {
                    document.newPage()
                }

                val positionInPage = index % badgesPerPage
                val col = positionInPage % cols
                val row = positionInPage / cols

                val x = margin + col * badgeWidth
                val y = pageHeight - margin - (row + 1) * badgeHeight

                // Draw badge
                drawBadge(
                    contentByte,
                    x,
                    y,
                    badgeWidth,
                    badgeHeight,
                    worker,
                    logoBitmap,
                    titleFont,
                    normalFont,
                    smallFont,
                    idFont
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error creating badge for worker ${worker._id}: ${e.message}", e)
                // Continue with next worker
            }
        }

        document.close()
        return file
    }

    /**
     * Create a font that supports Cyrillic characters with specified size and style
     */
    private fun createFont(size: Float = 12f, style: Int = Font.NORMAL, color: BaseColor = COLOR_BLACK): Font {
        try {
            // Step 1: Try to create BaseFont using the font included in assets
            val assetManager = context.assets
            val inputStream = assetManager.open("fonts/NotoSans-Regular.ttf")
            val tempFile = File(context.cacheDir, "temp_font.ttf")

            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output)
            }

            val baseFont = BaseFont.createFont(
                tempFile.absolutePath,
                BaseFont.IDENTITY_H,
                BaseFont.EMBEDDED
            )

            // Clean up the temp file
            tempFile.delete()

            return Font(baseFont, size, style, color)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating font from assets: ${e.message}", e)

            // Step 2: Try to use a standard PDF font (may not support Cyrillic)
            try {
                val baseFont = BaseFont.createFont(
                    BaseFont.HELVETICA,
                    BaseFont.CP1252,
                    BaseFont.NOT_EMBEDDED
                )

                Log.w(TAG, "Using Helvetica font which may not support Cyrillic")
                return Font(baseFont, size, style, color)
            } catch (e2: Exception) {
                Log.e(TAG, "Error creating standard font: ${e2.message}", e2)

                // Step 3: Last resort - use the default font
                return FontFactory.getFont(FontFactory.HELVETICA, size, style, color)
            }
        }
    }

    /**
     * Draw a single badge for a worker in vertical format with improved design
     */
    private fun drawBadge(
        contentByte: PdfContentByte,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        worker: Worker,
        logo: Bitmap?,
        titleFont: Font,
        normalFont: Font,
        smallFont: Font,
        idFont: Font
    ) {
        try {
            // 1. Draw badge border with basic rectangle
            contentByte.saveState()
            contentByte.setColorStroke(COLOR_BLACK)
            contentByte.setLineWidth(1f)
            contentByte.rectangle(x, y, width, height)
            contentByte.stroke()
            contentByte.restoreState()

            // 2. Add company logo if available
            if (logo != null) {
                try {
                    val logoStream = ByteArrayOutputStream()
                    logo.compress(Bitmap.CompressFormat.PNG, 100, logoStream)
                    val logoImage = Image.getInstance(logoStream.toByteArray())

                    // Position logo at top
                    val logoSize = 10 * MM_TO_POINTS
                    logoImage.scaleToFit(logoSize, logoSize)
                    logoImage.setAbsolutePosition(
                        x + width - logoSize - 5 * MM_TO_POINTS,
                        y + height - logoSize - 5 * MM_TO_POINTS
                    )
                    contentByte.addImage(logoImage)
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding logo: ${e.message}", e)
                }
            }

            // 3. Draw worker sequence number at top
            val sequenceNumber = "№${worker.sequenceNumber}"
            contentByte.beginText()
            contentByte.setFontAndSize(idFont.baseFont, idFont.size)
            contentByte.setColorFill(COLOR_BLACK)
            contentByte.showTextAligned(
                Element.ALIGN_LEFT,
                sequenceNumber,
                x + 5 * MM_TO_POINTS,
                y + height - 10 * MM_TO_POINTS,
                0f
            )
            contentByte.endText()

            // 4. Draw worker name and surname on separate lines
            val workerFullName = worker.fullName ?: ""
            if (workerFullName.isNotEmpty()) {
                // Split name and surname
                val nameParts = workerFullName.split(" ", limit = 2)
                val firstName = nameParts.getOrElse(0) { "" }
                val lastName = nameParts.getOrElse(1) { "" }

                // Create name table
                val nameTable = PdfPTable(1)
                nameTable.totalWidth = width - 10 * MM_TO_POINTS
                nameTable.isLockedWidth = true

                // First name cell
                if (firstName.isNotEmpty()) {
                    val firstNameCell = PdfPCell(Paragraph(firstName, titleFont))
                    firstNameCell.border = Rectangle.NO_BORDER
                    firstNameCell.paddingTop = 5f
                    firstNameCell.paddingBottom = 2f
                    firstNameCell.horizontalAlignment = Element.ALIGN_CENTER
                    nameTable.addCell(firstNameCell)
                }

                // Last name cell
                if (lastName.isNotEmpty()) {
                    val lastNameCell = PdfPCell(Paragraph(lastName, titleFont))
                    lastNameCell.border = Rectangle.NO_BORDER
                    lastNameCell.paddingBottom = 5f
                    lastNameCell.horizontalAlignment = Element.ALIGN_CENTER
                    nameTable.addCell(lastNameCell)
                }

                // Position name below worker number
                nameTable.writeSelectedRows(
                    0, nameTable.rows.size,
                    x + 5 * MM_TO_POINTS,
                    y + height - 15 * MM_TO_POINTS - 2 * MM_TO_POINTS,
                    contentByte
                )
            }

            // 5. Generate and draw QR code with improved positioning
            val qrCode = generateQRCode(worker._id)
            if (qrCode != null) {
                try {
                    // Calculate maximum possible QR code size
                    // Leave margins on sides and bottom
                    val horizontalMargin = 5 * MM_TO_POINTS
                    val bottomMargin = 5 * MM_TO_POINTS
                    val maxWidth = width - (2 * horizontalMargin)

                    // Calculate available height (from bottom to below the name section)
                    val topContentHeight = 35 * MM_TO_POINTS // Approximate space for header, name and additional info
                    val availableHeight = height - topContentHeight - bottomMargin

                    // Use the smaller dimension to ensure QR code fits
                    val qrSize = Math.min(maxWidth, availableHeight)

                    // Position QR code at the bottom center
                    val qrX = x + (width - qrSize) / 2
                    val qrY = y + bottomMargin

                    val qrImage = Image.getInstance(qrCode)
                    qrImage.setAbsolutePosition(qrX, qrY)
                    qrImage.scaleToFit(qrSize, qrSize)
                    contentByte.addImage(qrImage)

                } catch (e: Exception) {
                    Log.e(TAG, "Error adding QR code: ${e.message}", e)
                    drawPlaceholderQRCode(contentByte, x, y, width)
                }
            } else {
                drawPlaceholderQRCode(contentByte, x, y, width)
            }

            // Add additional worker information if available
            // For example: position, department, etc.
            addAdditionalInfo(contentByte, x, y, width, height, worker, normalFont)

        } catch (e: Exception) {
            Log.e(TAG, "Error drawing badge: ${e.message}", e)
            // At minimum, draw the border rectangle
            contentByte.rectangle(x, y, width, height)
            contentByte.stroke()
        }
    }

    /**
     * Helper method to draw a rounded rectangle
     */
    private fun drawRoundedRectangle(
        contentByte: PdfContentByte,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float
    ) {
        // Start a new path
        contentByte.moveTo(x + radius, y)

        // Bottom edge
        contentByte.lineTo(x + width - radius, y)

        // Bottom-right corner
        contentByte.curveTo(
            x + width, y,
            x + width, y,
            x + width, y + radius
        )

        // Right edge
        contentByte.lineTo(x + width, y + height - radius)

        // Top-right corner
        contentByte.curveTo(
            x + width, y + height,
            x + width, y + height,
            x + width - radius, y + height
        )

        // Top edge
        contentByte.lineTo(x + radius, y + height)

        // Top-left corner
        contentByte.curveTo(
            x, y + height,
            x, y + height,
            x, y + height - radius
        )

        // Left edge
        contentByte.lineTo(x, y + radius)

        // Bottom-left corner
        contentByte.curveTo(
            x, y,
            x, y,
            x + radius, y
        )
    }

    /**
     * Helper method to draw placeholder for QR code
     */
    private fun drawPlaceholderQRCode(
        contentByte: PdfContentByte,
        x: Float,
        y: Float,
        width: Float
    ) {
        // Draw placeholder for QR code
        contentByte.saveState()
        contentByte.setColorStroke(COLOR_BLACK)
        contentByte.setLineWidth(0.5f)

        // Calculate maximum possible QR code size for placeholder
        val horizontalMargin = 5 * MM_TO_POINTS
        val bottomMargin = 5 * MM_TO_POINTS
        val maxWidth = width - (2 * horizontalMargin)
        val availableHeight = 40 * MM_TO_POINTS // Approximate size
        val placeholderSize = Math.min(maxWidth, availableHeight)

        contentByte.rectangle(
            x + (width - placeholderSize) / 2,
            y + bottomMargin,
            placeholderSize,
            placeholderSize
        )
        contentByte.stroke()

        // Add text in placeholder
        contentByte.beginText()
        contentByte.setFontAndSize(FontFactory.getFont(FontFactory.HELVETICA, 8f).baseFont, 8f)
        contentByte.showTextAligned(
            Element.ALIGN_CENTER,
            "QR Code",
            x + width / 2,
            y + bottomMargin + placeholderSize / 2,
            0f
        )
        contentByte.endText()

        contentByte.restoreState()
    }

    /**
     * Add additional worker information if available
     */
    private fun addAdditionalInfo(
        contentByte: PdfContentByte,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        worker: Worker,
        font: Font
    ) {
        // Calculate position for additional info (between name and QR code)
        // Adjust for vertical format
        val nameHeight = 20 * MM_TO_POINTS // Approximate height for name section
        val infoY = y + height - 15 * MM_TO_POINTS - nameHeight

        // Create a table for additional info
        val infoTable = PdfPTable(1)
        infoTable.totalWidth = width - 10 * MM_TO_POINTS
        infoTable.isLockedWidth = true

        // Add worker information if available (example fields)
        // Note: Modify these based on what fields are available in your Worker class

        // Position and draw the information table if it has content
        if (infoTable.rows.size > 0) {
            infoTable.writeSelectedRows(
                0, infoTable.rows.size,
                x + 5 * MM_TO_POINTS,
                infoY,
                contentByte
            )
        }
    }

    /**
     * Generate a QR code as a byte array image
     * @param content Worker ID to encode in the QR code
     * @return ByteArray of the generated QR code image or null if generation fails
     */
    private fun generateQRCode(content: String): ByteArray? {
        return try {
            // Create hints map for QR code generation
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
            hints[EncodeHintType.MARGIN] = 1 // Small margin
            hints[EncodeHintType.ERROR_CORRECTION] = com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H // Highest error correction

            // Ensure content is properly formatted as a string
            val qrContent = content.trim()

            Log.d(TAG, "Generating QR code with content: $qrContent")

            val bitMatrix = MultiFormatWriter().encode(
                qrContent,
                BarcodeFormat.QR_CODE,
                400, // Width
                400, // Height
                hints
            )

            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)

            // Convert bit matrix to pixels
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }

            // Create bitmap from pixels
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

            // Convert bitmap to byte array
            val stream = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                stream.toByteArray()
            } else {
                Log.e(TAG, "Failed to compress QR code bitmap")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating QR code: ${e.message}", e)
            null
        }
    }
}