package com.summarizer.token;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Erzeugt QR-Codes als SVG-Markup (kein Bild-Encoding nötig).
 */
@Service
public class QrCodeService {

    public String toSvg(String payload, int sizePx) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(payload, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.MARGIN, 1));
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            StringBuilder svg = new StringBuilder();
            svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                    .append(width).append(' ').append(height)
                    .append("\" width=\"").append(sizePx).append("\" height=\"").append(sizePx)
                    .append("\" shape-rendering=\"crispEdges\">");
            svg.append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>");
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (matrix.get(x, y)) {
                        svg.append("<rect x=\"").append(x).append("\" y=\"").append(y)
                                .append("\" width=\"1\" height=\"1\" fill=\"black\"/>");
                    }
                }
            }
            svg.append("</svg>");
            return svg.toString();
        } catch (Exception e) {
            throw new IllegalStateException("QR-Erzeugung fehlgeschlagen", e);
        }
    }
}
