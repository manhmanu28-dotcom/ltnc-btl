package com.auction.client.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.paint.Color;

/** Minh họa vẽ bằng JavaFX, chỉ dùng cho sản phẩm mẫu. */
public final class ProductArtwork {
    private ProductArtwork() { }

    public static Canvas create(String title) {
        Canvas canvas = new Canvas(280, 180);
        var g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#0000000C"));
        g.fillOval(55, 151, 170, 14);
        if (title.contains("Đồng hồ")) {
            g.setFill(Color.web("#795E49"));
            g.fillRoundRect(119, 0, 42, 180, 12, 12);
            g.setFill(Color.web("#C7AB77"));
            g.fillOval(86, 37, 108, 108);
            g.setFill(Color.web("#FAF7EE"));
            g.fillOval(94, 45, 92, 92);
            g.setStroke(Color.web("#344054"));
            g.setLineWidth(3);
            g.strokeLine(140, 91, 119, 70);
            g.strokeLine(140, 91, 163, 62);
            for (int i = 0; i < 12; i++) {
                double angle = i * Math.PI / 6;
                g.strokeLine(140 + 35 * Math.sin(angle), 91 + 35 * Math.cos(angle),
                        140 + 39 * Math.sin(angle), 91 + 39 * Math.cos(angle));
            }
        } else if (title.contains("Máy ảnh")) {
            g.setFill(Color.web("#39434D"));
            g.fillRoundRect(47, 60, 186, 94, 12, 12);
            g.setFill(Color.web("#B5BDC2"));
            g.fillRoundRect(47, 54, 186, 30, 8, 8);
            g.fillRect(82, 42, 48, 20);
            g.setFill(Color.web("#222D36"));
            g.fillOval(105, 65, 84, 84);
            g.setFill(Color.web("#718C9D"));
            g.fillOval(115, 75, 64, 64);
            g.setFill(Color.web("#213F50"));
            g.fillOval(127, 87, 40, 40);
            g.setFill(Color.web("#D2E6EC"));
            g.fillOval(133, 92, 12, 12);
        } else if (title.contains("gốm") || title.contains("sưu tập")) {
            g.setFill(Color.web("#AC755D"));
            g.fillOval(94, 65, 92, 99);
            g.fillRoundRect(119, 32, 42, 70, 12, 12);
            g.setFill(Color.web("#D6A184"));
            g.fillOval(116, 27, 48, 15);
            g.setStroke(Color.web("#E8C8AD"));
            g.setLineWidth(3);
            g.strokeArc(106, 81, 68, 47, 190, 160, javafx.scene.shape.ArcType.OPEN);
        } else {
            g.setFill(Color.web("#82664B"));
            g.fillRect(31, 17, 218, 145);
            g.setFill(Color.web("#FAF3E7"));
            g.fillRect(39, 25, 202, 129);
            g.setFill(Color.web("#E9CFA4"));
            g.fillRect(49, 35, 182, 109);
            g.setFill(Color.web("#F7E7C0"));
            g.fillOval(185, 43, 26, 26);
            String[] colors = {"#B67F60", "#D2AB77", "#8D9D8F", "#C58E67", "#9EAC99"};
            for (int i = 0; i < 5; i++) {
                int x = 49 + i * 37;
                int y = 82 + (i % 2) * 12;
                g.setFill(Color.web(colors[i]));
                g.fillRect(x, y, 35, 144 - y);
                g.setFill(Color.web("#685C50"));
                g.fillPolygon(new double[]{x, x + 17, x + 35}, new double[]{y, y - 17, y}, 3);
                g.fillRect(x + 13, y + 16, 9, 17);
            }
        }
        return canvas;
    }
}
