use anyhow::{bail, Result};
use image::{DynamicImage, ImageFormat};
use std::{io::Cursor, path::Path};
pub fn is_webp(b: &[u8]) -> bool {
    b.len() >= 12 && &b[..4] == b"RIFF" && &b[8..12] == b"WEBP"
}
pub fn decode(bytes: &[u8], max_side: u32) -> Result<DynamicImage> {
    if !is_webp(bytes) {
        bail!("Invalid WebP image");
    }
    let reader = image::ImageReader::with_format(Cursor::new(bytes), ImageFormat::WebP);
    let (w, h) = reader.into_dimensions()?;
    if w > max_side || h > max_side {
        bail!("Image dimensions exceed limit");
    }
    Ok(image::load_from_memory_with_format(
        bytes,
        ImageFormat::WebP,
    )?)
}
pub fn prepare(file: &Path, limit: usize, max_side: u32) -> Result<Vec<u8>> {
    if std::fs::metadata(file)?.len() > 20_000_000 {
        bail!("Source image must be below 20 MB");
    }
    prepare_bytes(&std::fs::read(file)?, limit, max_side)
}
pub fn prepare_bytes(bytes: &[u8], limit: usize, max_side: u32) -> Result<Vec<u8>> {
    if bytes.len() > 20_000_000 || max_side < 32 {
        bail!("Invalid source image or dimension limit");
    }
    let format = image::guess_format(bytes)?;
    if ![ImageFormat::Png, ImageFormat::Jpeg, ImageFormat::WebP].contains(&format) {
        bail!("Only still PNG, JPEG or WebP images are allowed");
    }
    let (w, h) = image::ImageReader::with_format(Cursor::new(bytes), format).into_dimensions()?;
    if w == 0 || h == 0 || w as u64 * h as u64 > 40_000_000 {
        bail!("Image dimensions exceed limit");
    }
    let source = image::load_from_memory_with_format(bytes, format)?;
    let mut side = max_side;
    while side >= 32 {
        let scaled = source.resize(side, side, image::imageops::FilterType::CatmullRom);
        let mut rgb = scaled.to_rgb8();
        for (x, y, p) in scaled.to_rgba8().enumerate_pixels() {
            let alpha = p[3] as u16;
            let out = rgb.get_pixel_mut(x, y);
            for k in 0..3 {
                out[k] = ((p[k] as u16 * alpha + 255 * (255 - alpha)) / 255) as u8;
            }
        }
        let image = DynamicImage::ImageRgb8(rgb);
        let mut out = Cursor::new(Vec::new());
        image.write_to(&mut out, ImageFormat::WebP)?;
        let bytes = out.into_inner();
        if bytes.len() <= limit && is_webp(&bytes) {
            return Ok(bytes);
        }
        side = side * 3 / 4;
    }
    bail!("Image cannot fit the size limit")
}
pub fn collection(images: &[Vec<u8>]) -> Result<Vec<u8>> {
    if images.is_empty()
        || images.len() > 3
        || images.iter().any(|b| b.len() > 100000 || !is_webp(b))
    {
        bail!("Choose one to three WebP images, at most 100,000 bytes each");
    }
    Ok(serde_json::to_vec(
        &images
            .iter()
            .map(|b| crate::crypto::b64(b))
            .collect::<Vec<_>>(),
    )?)
}
