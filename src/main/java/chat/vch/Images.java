package chat.vch;

import javax.imageio.*;
import javax.imageio.stream.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

public final class Images {
    static { ImageIO.setUseCache(false); }
    public static boolean isWebp(byte[] b) { return b.length >= 12 && Arrays.equals(Arrays.copyOfRange(b,0,4), Crypto.utf("RIFF")) && Arrays.equals(Arrays.copyOfRange(b,8,12), Crypto.utf("WEBP")); }
    public static BufferedImage decode(byte[] bytes,int maxSide)throws IOException{
        if(!isWebp(bytes))throw new IOException("Invalid WebP image");
        try(ImageInputStream in=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw new IOException("Unsupported image");ImageReader r=readers.next();try{r.setInput(in);if(r.getWidth(0)>maxSide||r.getHeight(0)>maxSide)throw new IOException("Image dimensions exceed limit");return r.read(0);}finally{r.dispose();}}
    }
    public static byte[] prepare(Path file, int limit, int maxSide) throws Exception {
        if (Files.size(file) > 20000000) throw new IOException("Source image must be below 20 MB");
        BufferedImage source;
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Choose a still PNG, JPEG, or WebP image. Video files are disabled.");
            ImageReader r = readers.next();
            try { r.setInput(input); String format = r.getFormatName().toLowerCase();
                if (!java.util.Set.of("png", "jpeg", "jpg", "webp").contains(format)) throw new IOException("Only still PNG, JPEG, or WebP images are allowed");
                int w = r.getWidth(0), h = r.getHeight(0);
                if ((long)w*h > 40000000 || w < 1 || h < 1) throw new IOException("Image dimensions exceed limit");
                var param = r.getDefaultReadParam(); int sample = Math.max(1, Math.max(w,h) / (maxSide * 2)); param.setSourceSubsampling(sample,sample,0,0); source = r.read(0,param);
            } finally { r.dispose(); }
        }
        var writers = ImageIO.getImageWritersByMIMEType("image/webp");
        if (!writers.hasNext()) throw new IOException("WebP encoder unavailable on this platform");
        ImageWriter writer = writers.next();
        try {
            for (int side = maxSide; side >= 32; side = side * 3 / 4) {
                double ratio = Math.min(1, (double)side / Math.max(source.getWidth(), source.getHeight()));
                BufferedImage scaled = new BufferedImage(Math.max(1,(int)(source.getWidth()*ratio)),Math.max(1,(int)(source.getHeight()*ratio)),BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scaled.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0,0,scaled.getWidth(),scaled.getHeight());
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC); g.drawImage(source,0,0,scaled.getWidth(),scaled.getHeight(),null); g.dispose();
                for (float quality : new float[]{.82f,.60f,.40f,.20f}) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    try (ImageOutputStream out = ImageIO.createImageOutputStream(b)) {
                        writer.setOutput(out); ImageWriteParam p = writer.getDefaultWriteParam(); p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        String[] types = p.getCompressionTypes(); if (types != null) p.setCompressionType(Arrays.stream(types).filter(t -> t.toLowerCase().contains("lossy")).findFirst().orElse(types[0]));
                        p.setCompressionQuality(quality); writer.write(null,new IIOImage(scaled,null,null),p); out.flush();
                    }
                    if (b.size() <= limit && isWebp(b.toByteArray())) return b.toByteArray();
                }
            }
            throw new IOException("Image cannot fit the size limit");
        } finally { writer.dispose(); }
    }
}
