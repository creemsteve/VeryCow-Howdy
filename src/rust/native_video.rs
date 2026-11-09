//! Native capture, AVC keyframes and opt-in rendering over the existing encrypted relay.
use crate::{crypto, server::text, voice::VoiceSession};
use anyhow::{ensure, Context, Result};
use image::{imageops, RgbImage};
use nokhwa::{
    pixel_format::RgbFormat,
    utils::{ApiBackend, CameraFormat, FrameFormat, RequestedFormat, RequestedFormatType},
    Camera,
};
use openh264::{
    decoder::Decoder,
    encoder::{
        BitRate, Encoder, EncoderConfig, FrameRate, FrameType, Level, Profile, RateControlMode,
    },
    formats::{RgbSliceU8, YUVBuffer, YUVSource},
    OpenH264API, Timestamp,
};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::{
    collections::{BTreeMap, BTreeSet, VecDeque},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc, Mutex,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

pub trait Capture {
    fn frame(&mut self) -> Result<RgbImage>;
}

struct Screen(xcap::Monitor);
impl Capture for Screen {
    fn frame(&mut self) -> Result<RgbImage> {
        Ok(image::DynamicImage::ImageRgba8(self.0.capture_image()?).into_rgb8())
    }
}
struct Webcam(Camera);
impl Capture for Webcam {
    fn frame(&mut self) -> Result<RgbImage> {
        let frame = self.0.frame()?;
        if frame.source_frame_format() == FrameFormat::MJPEG {
            Ok(
                image::load_from_memory_with_format(frame.buffer(), image::ImageFormat::Jpeg)?
                    .into_rgb8(),
            )
        } else {
            Ok(frame.decode_image::<RgbFormat>()?)
        }
    }
}
impl Drop for Webcam {
    fn drop(&mut self) {
        let _ = self.0.stop_stream();
    }
}

#[derive(Clone, Debug)]
pub struct Device {
    pub id: String,
    pub label: String,
}
#[derive(Clone, Default)]
pub struct Devices {
    pub screens: Vec<Device>,
    pub cameras: Vec<Device>,
    pub error: String,
    pub ready: bool,
}
fn devices() -> Devices {
    let mut out = Devices::default();
    match xcap::Monitor::all() {
        Ok(monitors) => {
            for m in monitors {
                if let Ok(id) = m.id() {
                    out.screens.push(Device {
                        id: id.to_string(),
                        label: format!(
                            "{} · {} × {}",
                            m.friendly_name().unwrap_or_else(|_| "Display".into()),
                            m.width().unwrap_or(0),
                            m.height().unwrap_or(0)
                        ),
                    });
                }
            }
        }
        Err(e) => out.error = format!("Displays unavailable: {e}"),
    }
    match nokhwa::query(ApiBackend::Auto) {
        Ok(cameras) => {
            for c in cameras {
                out.cameras.push(Device {
                    id: c.index().as_string(),
                    label: c.human_name(),
                });
            }
        }
        Err(e) => {
            if !out.error.is_empty() {
                out.error.push('\n');
            }
            out.error.push_str(&format!("Cameras unavailable: {e}"));
        }
    }
    out.ready = true;
    out
}
fn capture(source: &str, device: &str) -> Result<Box<dyn Capture>> {
    if source == "screen" {
        let id: u32 = device.parse().context("Choose a display")?;
        let screen = xcap::Monitor::all()?
            .into_iter()
            .find(|m| m.id().ok() == Some(id))
            .context("Display unavailable. Refresh devices and choose again.")?;
        return Ok(Box::new(Screen(screen)));
    }
    ensure!(source == "camera", "Invalid video source");
    let index = nokhwa::query(ApiBackend::Auto)?
        .into_iter()
        .find(|c| c.index().as_string() == device)
        .context("Camera unavailable. Refresh devices and choose again.")?
        .index()
        .clone();
    let format = RequestedFormat::new::<RgbFormat>(RequestedFormatType::Closest(
        CameraFormat::new_from(640, 360, FrameFormat::MJPEG, 15),
    ));
    let mut camera = Camera::new(index, format)?;
    camera.open_stream()?;
    Ok(Box::new(Webcam(camera)))
}

#[derive(Serialize, Deserialize)]
struct Header {
    codec: String,
    width: usize,
    height: usize,
    timestamp: u64,
}
fn encoder(kbps: u32) -> Result<Encoder> {
    ensure!(
        (50..=1000).contains(&kbps),
        "Choose 50–1000 kbps per stream"
    );
    Ok(Encoder::with_api_config(
        OpenH264API::from_source(),
        EncoderConfig::new()
            .bitrate(BitRate::from_bps(kbps * 600))
            .max_frame_rate(FrameRate::from_hz(8.))
            .rate_control_mode(RateControlMode::Bitrate)
            .profile(Profile::Baseline)
            .level(Level::Level_3_1)
            .num_threads(2)
            .skip_frames(true),
    )?)
}
pub fn encode_frame(
    encoder: &mut Encoder,
    image: &RgbImage,
    timestamp: u64,
) -> Result<Option<Vec<u8>>> {
    let (width, height) = image.dimensions();
    ensure!(
        (2..=1920).contains(&width)
            && (2..=1080).contains(&height)
            && width % 2 == 0
            && height % 2 == 0,
        "Invalid video dimensions"
    );
    let yuv = YUVBuffer::from_rgb_source(RgbSliceU8::new(
        image.as_raw(),
        (width as usize, height as usize),
    ));
    encoder.force_intra_frame();
    let bitstream = encoder.encode_at(&yuv, Timestamp::from_millis(timestamp / 1000))?;
    if matches!(bitstream.frame_type(), FrameType::Skip) {
        return Ok(None);
    }
    let header = serde_json::to_vec(&Header {
        codec: "avc1.42001f".into(),
        width: width as usize,
        height: height as usize,
        timestamp,
    })?;
    let mut packet = Vec::with_capacity(4 + header.len());
    packet.extend_from_slice(&(header.len() as u32).to_be_bytes());
    packet.extend(header);
    bitstream.write_vec(&mut packet);
    ensure!(
        packet.len() <= 125000,
        "Encoded frame exceeds the relay limit"
    );
    Ok(Some(packet))
}
pub fn decode_frame(decoder: &mut Decoder, packet: &[u8]) -> Result<Option<RgbImage>> {
    ensure!(
        (5..=125000).contains(&packet.len()),
        "Invalid video packet size"
    );
    let n = u32::from_be_bytes(packet[..4].try_into()?) as usize;
    ensure!(
        n <= 1024 && n + 4 < packet.len(),
        "Invalid video header length"
    );
    let header: Header = serde_json::from_slice(&packet[4..n + 4])?;
    ensure!(
        header.codec == "avc1.42001f"
            && (2..=1920).contains(&header.width)
            && (2..=1080).contains(&header.height),
        "Unsupported video format or dimensions"
    );
    let Some(yuv) = decoder.decode(&packet[n + 4..])? else {
        return Ok(None);
    };
    let (w, h) = yuv.dimensions();
    ensure!(
        (w, h) == (header.width, header.height),
        "Video dimensions do not match the header"
    );
    let mut image = RgbImage::new(w as u32, h as u32);
    yuv.write_rgb8(image.as_mut());
    Ok(Some(image))
}
fn fit(image: &RgbImage, source: &str) -> RgbImage {
    let (w, h) = if source == "screen" {
        (960, 540)
    } else {
        (640, 360)
    };
    let scaled = imageops::thumbnail(image, w, h);
    let mut frame = RgbImage::from_pixel(w, h, image::Rgb([22, 22, 22]));
    imageops::overlay(
        &mut frame,
        &scaled,
        ((w - scaled.width()) / 2).into(),
        ((h - scaled.height()) / 2).into(),
    );
    frame
}

struct Publisher {
    id: String,
    capture: Box<dyn Capture>,
    encoder: Encoder,
    kbps: u32,
    charges: VecDeque<(Instant, usize)>,
    preview: Option<Arc<RgbImage>>,
}
#[derive(Clone, Deserialize)]
pub struct Stream {
    pub id: String,
    pub author: String,
    pub source: String,
    pub kbps: u32,
    #[serde(skip)]
    pub receiving: bool,
    #[serde(skip)]
    pub image: Option<Arc<RgbImage>>,
    #[serde(skip)]
    pub frame: u64,
}
pub struct VideoDriver {
    session: Arc<Mutex<VoiceSession>>,
    account: String,
    local: BTreeMap<String, Publisher>,
    pub streams: Vec<Stream>,
    wanted: BTreeSet<String>,
    decoders: BTreeMap<String, Decoder>,
    cursor: i64,
    sequence: u64,
}
impl VideoDriver {
    pub fn new(session: Arc<Mutex<VoiceSession>>) -> Result<Self> {
        let settings = session
            .lock()
            .map_err(|_| anyhow::anyhow!("Call unavailable"))?
            .request("/settings", json!({}))?;
        Ok(Self {
            session,
            account: text(&settings, "account").into(),
            local: BTreeMap::new(),
            streams: vec![],
            wanted: BTreeSet::new(),
            decoders: BTreeMap::new(),
            cursor: -1,
            sequence: 0,
        })
    }
    fn request(&self, path: &str, input: serde_json::Value) -> Result<serde_json::Value> {
        self.session
            .lock()
            .map_err(|_| anyhow::anyhow!("Call unavailable"))?
            .request(path, input)
    }
    pub fn start(&mut self, source: &str, kbps: u32, capture: Box<dyn Capture>) -> Result<()> {
        ensure!(
            ["screen", "camera"].contains(&source),
            "Invalid video source"
        );
        ensure!(
            !self.local.contains_key(source),
            "This source is already sharing"
        );
        let encoder = encoder(kbps)?;
        let out = self.request("/video/start", json!({"source":source,"kbps":kbps}))?;
        self.local.insert(
            source.into(),
            Publisher {
                id: text(&out, "stream").into(),
                capture,
                encoder,
                kbps,
                charges: VecDeque::new(),
                preview: None,
            },
        );
        Ok(())
    }
    pub fn stop(&mut self, source: &str) {
        if let Some(p) = self.local.remove(source) {
            let id = p.id.clone();
            drop(p); // Release the device before waiting on the network.
            let _ = self.request("/video/stop", json!({"stream":id}));
        }
    }
    pub fn configure(&mut self, source: &str, kbps: u32) -> Result<()> {
        let encoder = encoder(kbps)?;
        let id = self
            .local
            .get(source)
            .context("Start sharing first")?
            .id
            .clone();
        self.request("/video/configure", json!({"stream":id,"kbps":kbps}))?;
        let p = self.local.get_mut(source).unwrap();
        p.encoder = encoder;
        p.kbps = kbps;
        Ok(())
    }
    pub fn receive(&mut self, id: &str, wanted: bool) {
        if wanted {
            self.wanted.insert(id.into());
        } else {
            self.wanted.remove(id);
            self.decoders.remove(id);
            if let Some(s) = self.streams.iter_mut().find(|s| s.id == id) {
                s.image = None;
                s.receiving = false;
            }
        }
    }
    pub fn step(&mut self) -> Result<()> {
        let result = self.step_inner();
        if result.is_err() {
            self.stop_all();
        }
        result
    }
    fn step_inner(&mut self) -> Result<()> {
        let out = self.request(
            "/video/poll",
            json!({"after":self.cursor,"receive":self.wanted}),
        )?;
        self.cursor = out["cursor"].as_i64().context("Invalid video cursor")?;
        let listings: Vec<Stream> = serde_json::from_value(out["streams"].clone())?;
        ensure!(listings.len() <= 64, "Too many video streams");
        let mut previous: BTreeMap<_, _> = std::mem::take(&mut self.streams)
            .into_iter()
            .map(|s| (s.id.clone(), s))
            .collect();
        for mut s in listings.into_iter().filter(|s| s.author != self.account) {
            s.receiving = self.wanted.contains(&s.id);
            if s.receiving {
                if let Some(old) = previous.remove(&s.id) {
                    s.image = old.image;
                    s.frame = old.frame;
                }
            }
            self.streams.push(s);
        }
        self.wanted
            .retain(|id| self.streams.iter().any(|s| &s.id == id));
        self.decoders.retain(|id, _| self.wanted.contains(id));
        for item in out["frames"].as_array().context("Invalid video frames")? {
            let id = text(item, "stream");
            let Some(i) = self.streams.iter().position(|s| s.id == id && s.receiving) else {
                continue;
            };
            let data = self.request(
                "/video/decode",
                json!({"stream":id,"author":self.streams[i].author,"envelope":item["envelope"]}),
            )?;
            if !self.decoders.contains_key(id) {
                self.decoders.insert(id.into(), Decoder::new()?);
            }
            if let Some(image) = decode_frame(
                self.decoders.get_mut(id).unwrap(),
                &crypto::unb64(text(&data, "data"))?,
            )? {
                self.sequence += 1;
                self.streams[i].image = Some(Arc::new(image));
                self.streams[i].frame = self.sequence;
            }
        }
        let sources: Vec<_> = self.local.keys().cloned().collect();
        for source in sources {
            let p = self.local.get_mut(&source).unwrap();
            let image = fit(&p.capture.frame()?, &source);
            let packet = encode_frame(&mut p.encoder, &image, crate::now() * 1000)?;
            p.preview = Some(Arc::new(image));
            let Some(packet) = packet else { continue };
            let now = Instant::now();
            while p
                .charges
                .front()
                .is_some_and(|(t, _)| now.duration_since(*t) >= Duration::from_millis(1100))
            {
                p.charges.pop_front();
            }
            let cost = packet.len() + 256;
            if p.charges.iter().map(|(_, n)| n).sum::<usize>() + cost > p.kbps as usize * 125 {
                continue;
            }
            p.charges.push_back((now, cost));
            let id = p.id.clone();
            let envelope = self.request(
                "/video/encode",
                json!({"stream":id,"data":crypto::b64(&packet)}),
            )?;
            self.request("/video/media", json!({"stream":id,"envelope":envelope}))?;
        }
        Ok(())
    }
    pub fn stop_all(&mut self) {
        // Drop every capture before the first network cleanup request.
        let ids: Vec<_> = std::mem::take(&mut self.local)
            .into_values()
            .map(|p| p.id.clone())
            .collect();
        self.streams.clear();
        self.wanted.clear();
        self.decoders.clear();
        for id in ids {
            let _ = self.request("/video/stop", json!({"stream":id}));
        }
    }
    fn stop_sharing(&mut self) {
        let sources: Vec<_> = self.local.keys().cloned().collect();
        for source in sources {
            self.stop(&source);
        }
    }
}
impl Drop for VideoDriver {
    fn drop(&mut self) {
        self.stop_all();
    }
}

#[derive(Clone, Default)]
pub struct VideoState {
    pub devices: Devices,
    pub sharing: BTreeMap<String, u32>,
    pub previews: BTreeMap<String, Arc<RgbImage>>,
    pub streams: Vec<Stream>,
    pub status: String,
    pub failed: bool,
    pub pending: bool,
    pub sequence: u64,
}
pub enum Command {
    Refresh,
    Start {
        source: String,
        device: String,
        kbps: u32,
    },
    Stop(String),
    Configure(String, u32),
    Receive(String, bool),
    StopAll,
}
pub struct NativeVideo {
    pub state: Arc<Mutex<VideoState>>,
    tx: mpsc::SyncSender<Command>,
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}
impl NativeVideo {
    pub fn start(session: Arc<Mutex<VoiceSession>>) -> Self {
        let state = Arc::new(Mutex::new(VideoState {
            status: "Finding displays and cameras…".into(),
            ..Default::default()
        }));
        let shared = state.clone();
        let stop = Arc::new(AtomicBool::new(false));
        let shutdown = stop.clone();
        let (tx, rx) = mpsc::sync_channel(16);
        let thread = thread::spawn(move || {
            let result = (|| -> Result<()> {
                let mut driver = VideoDriver::new(session)?;
                if let Ok(mut s) = shared.lock() {
                    s.devices = devices();
                    s.status =
                        "Choose a source to share. Incoming video is off until you select Receive."
                            .into();
                }
                while !shutdown.load(Ordering::Relaxed) {
                    let started = Instant::now();
                    for command in rx.try_iter() {
                        if shutdown.load(Ordering::Relaxed) {
                            break;
                        }
                        let result = match command {
                            Command::Refresh => {
                                if let Ok(mut s) = shared.lock() {
                                    s.devices = devices();
                                }
                                Ok(())
                            }
                            Command::Start {
                                source,
                                device,
                                kbps,
                            } => capture(&source, &device)
                                .and_then(|c| driver.start(&source, kbps, c)),
                            Command::Stop(source) => {
                                driver.stop(&source);
                                Ok(())
                            }
                            Command::Configure(source, kbps) => driver.configure(&source, kbps),
                            Command::Receive(id, wanted) => {
                                driver.receive(&id, wanted);
                                Ok(())
                            }
                            Command::StopAll => {
                                driver.stop_sharing();
                                Ok(())
                            }
                        };
                        if let Ok(mut s) = shared.lock() {
                            s.pending = false;
                            s.status = result
                                .err()
                                .map(|e| format!("{e:#}"))
                                .unwrap_or_else(|| "Video controls updated".into());
                        }
                    }
                    if shutdown.load(Ordering::Relaxed) {
                        break;
                    }
                    driver.step()?;
                    if let Ok(mut s) = shared.lock() {
                        s.sharing = driver
                            .local
                            .iter()
                            .map(|(name, p)| (name.clone(), p.kbps))
                            .collect();
                        s.previews = driver
                            .local
                            .iter()
                            .filter_map(|(name, p)| {
                                p.preview
                                    .as_ref()
                                    .map(|image| (name.clone(), image.clone()))
                            })
                            .collect();
                        s.streams = driver.streams.clone();
                        s.sequence += 1;
                    }
                    thread::sleep(Duration::from_millis(125).saturating_sub(started.elapsed()));
                }
                Ok(())
            })();
            if let Err(e) = result {
                if let Ok(mut s) = shared.lock() {
                    s.failed = true;
                    s.pending = false;
                    s.sharing.clear();
                    s.previews.clear();
                    s.streams.clear();
                    s.status = format!("Video stopped: {e:#}");
                }
            }
        });
        Self {
            state,
            tx,
            stop,
            thread: Some(thread),
        }
    }
    pub fn command(&self, command: Command) -> Result<()> {
        let mut state = self
            .state
            .lock()
            .map_err(|_| anyhow::anyhow!("Video unavailable"))?;
        self.tx
            .try_send(command)
            .context("Video is busy or stopped. Try again.")?;
        state.pending = true;
        Ok(())
    }
}
impl Drop for NativeVideo {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}
