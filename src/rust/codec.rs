use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use std::path::Path;
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct CodecInfo {
    pub sample_rate: usize,
    pub hop: usize,
    pub codebooks: usize,
}
impl Default for CodecInfo {
    fn default() -> Self {
        Self {
            sample_rate: 16000,
            hop: 320,
            codebooks: 12,
        }
    }
}
pub fn pack10(values: &[u16]) -> Result<Vec<u8>> {
    let mut result = Vec::with_capacity((values.len() * 10).div_ceil(8));
    let (mut acc, mut bits) = (0u32, 0);
    for &v in values {
        if v > 1023 {
            bail!("Invalid DAC token");
        }
        acc = (acc << 10) | v as u32;
        bits += 10;
        while bits >= 8 {
            bits -= 8;
            result.push((acc >> bits) as u8);
        }
    }
    if bits > 0 {
        result.push((acc << (8 - bits)) as u8);
    }
    Ok(result)
}
pub fn unpack10(data: &[u8], count: usize) -> Result<Vec<u16>> {
    anyhow::ensure!(
        count <= 12 * 256 && data.len() == (count * 10).div_ceil(8),
        "Invalid token length"
    );
    let mut result = Vec::with_capacity(count);
    let (mut acc, mut bits) = (0u32, 0);
    for &v in data {
        acc = (acc << 8) | v as u32;
        bits += 8;
        while bits >= 10 && result.len() < count {
            bits -= 10;
            result.push(((acc >> bits) & 1023) as u16);
        }
    }
    anyhow::ensure!(result.len() == count, "Truncated codes");
    Ok(result)
}
#[derive(Debug)]
pub struct Packet {
    pub books: usize,
    pub frames: usize,
    pub samples: usize,
    pub codes: Vec<u16>,
}
impl Packet {
    pub fn encode(&self) -> Result<Vec<u8>> {
        self.validate(12)?;
        let mut bytes = b"VCD1".to_vec();
        bytes.extend((self.books as u16).to_le_bytes());
        bytes.extend((self.frames as u16).to_le_bytes());
        bytes.extend((self.samples as u32).to_le_bytes());
        bytes.extend(pack10(&self.codes)?);
        Ok(bytes)
    }
    pub fn decode(bytes: &[u8], books: usize) -> Result<Self> {
        anyhow::ensure!(
            (12..=32000).contains(&bytes.len()) && bytes.starts_with(b"VCD1"),
            "Invalid DAC packet"
        );
        let packet = Self {
            books: u16::from_le_bytes(bytes[4..6].try_into()?) as usize,
            frames: u16::from_le_bytes(bytes[6..8].try_into()?) as usize,
            samples: u32::from_le_bytes(bytes[8..12].try_into()?) as usize,
            codes: Vec::new(),
        };
        packet.validate_dimensions(books)?;
        let codes = unpack10(&bytes[12..], packet.books * packet.frames)?;
        Ok(Self { codes, ..packet })
    }
    fn validate_dimensions(&self, books: usize) -> Result<()> {
        anyhow::ensure!(
            (1..=books).contains(&self.books)
                && (1..=256).contains(&self.frames)
                && (1..=16000).contains(&self.samples),
            "Invalid DAC dimensions"
        );
        Ok(())
    }
    fn validate(&self, books: usize) -> Result<()> {
        self.validate_dimensions(books)?;
        anyhow::ensure!(
            self.codes.len() == self.books * self.frames,
            "Invalid DAC code count"
        );
        Ok(())
    }
}
pub fn pcm_floats(bytes: &[u8]) -> Result<Vec<f32>> {
    anyhow::ensure!(
        !bytes.is_empty() && bytes.len() <= 64000 && bytes.len().is_multiple_of(4),
        "Invalid PCM length"
    );
    bytes
        .as_chunks::<4>()
        .0
        .iter()
        .map(|b| {
            let sample = f32::from_le_bytes(*b);
            if !sample.is_finite() {
                bail!("Invalid PCM samples");
            }
            Ok(sample.clamp(-1., 1.))
        })
        .collect()
}
pub fn float_bytes(samples: &[f32]) -> Vec<u8> {
    samples.iter().flat_map(|f| f.to_le_bytes()).collect()
}
pub struct DacCodec {
    pub info: CodecInfo,
    #[cfg(feature = "neural")]
    encoder: ort::session::Session,
    #[cfg(feature = "neural")]
    decoder: ort::session::Session,
}
impl DacCodec {
    pub fn open(directory: &Path) -> Result<Self> {
        let info: CodecInfo = serde_json::from_slice(
            &std::fs::read(directory.join("dac.json"))
                .context("DAC models are missing; set --models to the model directory")?,
        )?;
        anyhow::ensure!(
            info.sample_rate == 16000 && info.hop == 320 && (1..=12).contains(&info.codebooks),
            "Unsupported DAC model dimensions"
        );
        #[cfg(feature = "neural")]
        {
            let dylib = std::env::var_os("ORT_DYLIB_PATH")
                .map(std::path::PathBuf::from)
                .or_else(|| {
                    let p = directory.join(if cfg!(windows) {
                        "onnxruntime.dll"
                    } else if cfg!(target_os = "macos") {
                        "libonnxruntime.dylib"
                    } else {
                        "libonnxruntime.so"
                    });
                    p.exists().then_some(p)
                });
            if let Some(path) = dylib {
                ort::init_from(path.to_string_lossy()).commit()?;
            }
            let threads = std::thread::available_parallelism()
                .map(|n| (n.get() / 2).clamp(1, 4))
                .unwrap_or(1);
            let encoder = ort::session::Session::builder()?
                .with_intra_threads(threads)?
                .commit_from_file(directory.join("dac-encoder.onnx"))?;
            let decoder = ort::session::Session::builder()?
                .with_intra_threads(threads)?
                .commit_from_file(directory.join("dac-decoder.onnx"))?;
            Ok(Self {
                info,
                encoder,
                decoder,
            })
        }
        #[cfg(not(feature = "neural"))]
        {
            let _ = info;
            bail!("This build does not include neural voice support")
        }
    }
    pub fn encode(&mut self, bytes: &[u8], bit_rate: u32) -> Result<Vec<u8>> {
        let mut pcm = pcm_floats(bytes)?;
        let samples = pcm.len();
        let padded = (samples + self.info.hop + 7) / self.info.hop * self.info.hop;
        pcm.resize(padded, 0.);
        let n = ((bit_rate as f64 * self.info.hop as f64 / (10 * self.info.sample_rate) as f64)
            .round() as usize)
            .clamp(1, self.info.codebooks);
        #[cfg(feature = "neural")]
        {
            let input = ort::value::Tensor::from_array(([1, 1, padded], pcm.into_boxed_slice()))?;
            let result = self.encoder.run(ort::inputs!["pcm"=>input])?;
            let (shape, codes) = result[0].try_extract_tensor::<i64>()?;
            anyhow::ensure!(
                shape.len() == 3 && shape[0] == 1 && shape[1] >= n as i64,
                "Invalid encoder output"
            );
            let frames = shape[2] as usize;
            let codes = codes
                .iter()
                .take(n * frames)
                .map(|&c| u16::try_from(c).context("Invalid DAC token"))
                .collect::<Result<Vec<_>>>()?;
            Packet {
                books: n,
                frames,
                samples,
                codes,
            }
            .encode()
        }
        #[cfg(not(feature = "neural"))]
        {
            let _ = (pcm, samples, n);
            bail!("Neural voice is disabled")
        }
    }
    pub fn decode(&mut self, bytes: &[u8]) -> Result<Vec<u8>> {
        let packet = Packet::decode(bytes, self.info.codebooks)?;
        #[cfg(feature = "neural")]
        {
            let mut codes = vec![0i64; self.info.codebooks * packet.frames];
            for (i, &c) in packet.codes.iter().enumerate() {
                codes[i] = c as i64;
            }
            let mask: Vec<f32> = (0..self.info.codebooks)
                .map(|i| if i < packet.books { 1. } else { 0. })
                .collect();
            let codes = ort::value::Tensor::from_array((
                [1, self.info.codebooks, packet.frames],
                codes.into_boxed_slice(),
            ))?;
            let mask =
                ort::value::Tensor::from_array(([self.info.codebooks], mask.into_boxed_slice()))?;
            let result = self
                .decoder
                .run(ort::inputs!["codes"=>codes,"mask"=>mask])?;
            let (_, pcm) = result[0].try_extract_tensor::<f32>()?;
            anyhow::ensure!(pcm.len() >= packet.samples, "Truncated PCM output");
            Ok(float_bytes(&pcm[..packet.samples]))
        }
        #[cfg(not(feature = "neural"))]
        {
            let _ = packet;
            bail!("Neural voice is disabled")
        }
    }
    pub fn request(&mut self, input: &serde_json::Value) -> Result<serde_json::Value> {
        use serde_json::json;
        let op = crate::server::text(input, "op");
        match op {
            "" | "init" => {
                Ok(json!({"ready":true,"sampleRate":self.info.sample_rate,"hop":self.info.hop}))
            }
            "encode" => {
                let start = std::time::Instant::now();
                let data = self.encode(
                    &crate::crypto::unb64(crate::server::text(input, "data"))?,
                    input["bitRate"]
                        .as_u64()
                        .unwrap_or(6000)
                        .min(u32::MAX as u64) as u32,
                )?;
                Ok(
                    json!({"data":crate::crypto::b64(&data),"ms":start.elapsed().as_secs_f64()*1000.}),
                )
            }
            "decode" => {
                let data =
                    self.decode(&crate::crypto::unb64(crate::server::text(input, "data"))?)?;
                Ok(json!({"data":crate::crypto::b64(&data),"sampleRate":self.info.sample_rate}))
            }
            _ => bail!("Unknown codec operation"),
        }
    }
}
