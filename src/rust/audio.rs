use anyhow::{bail, Context, Result};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use std::{
    collections::VecDeque,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc, Mutex,
    },
};
pub fn to_float(pcm: &[u8], gain: u32) -> Result<Vec<f32>> {
    anyhow::ensure!(pcm.len().is_multiple_of(2), "Invalid PCM16 length");
    Ok(pcm
        .as_chunks::<2>()
        .0
        .iter()
        .map(|b| {
            (i16::from_le_bytes([b[0], b[1]]) as f32 / 32768. * gain as f32 / 100.).clamp(-1., 1.)
        })
        .collect())
}
pub fn to_pcm(floats: &[f32], gain: u32) -> Vec<u8> {
    floats
        .iter()
        .flat_map(|f| {
            let f = if f.is_finite() {
                (f * gain as f32 / 100.).clamp(-1., 1.)
            } else {
                0.
            };
            ((f * 32767.).round() as i16).to_le_bytes()
        })
        .collect()
}
pub fn resample(samples: &[f32], from: u32, to: u32) -> Result<Vec<f32>> {
    anyhow::ensure!(
        from > 0 && to > 0 && from <= 384000 && to <= 384000,
        "Invalid sample rate"
    );
    if samples.is_empty() {
        return Ok(Vec::new());
    }
    let len = (samples.len() as u64 * to as u64 / from as u64) as usize;
    anyhow::ensure!(len <= 1_000_000, "Audio block too large");
    Ok((0..len)
        .map(|i| {
            let p = i as f64 * from as f64 / to as f64;
            let j = p as usize;
            let k = (j + 1).min(samples.len() - 1);
            let f = (p - j as f64) as f32;
            samples[j] * (1. - f) + samples[k] * f
        })
        .collect())
}
pub fn devices() -> Result<(Vec<String>, Vec<String>)> {
    let host = cpal::default_host();
    Ok((
        host.input_devices()?
            .filter_map(|d| d.name().ok())
            .collect(),
        host.output_devices()?
            .filter_map(|d| d.name().ok())
            .collect(),
    ))
}
pub struct Devices {
    input: cpal::Stream,
    _output: cpal::Stream,
    incoming: Arc<Mutex<VecDeque<f32>>>,
    outgoing: Arc<Mutex<VecDeque<f32>>>,
    input_rate: u32,
    output_rate: u32,
    recording: bool,
    pressed: Option<Arc<AtomicBool>>,
    lease: Arc<AtomicU64>,
}
impl Devices {
    pub fn open(microphone: &str, speakers: &str) -> Result<Self> {
        Self::open_gated(microphone, speakers, None)
    }
    pub fn open_gated(
        microphone: &str,
        speakers: &str,
        pressed: Option<Arc<AtomicBool>>,
    ) -> Result<Self> {
        let host = cpal::default_host();
        let input = if microphone == "Default" {
            host.default_input_device()
        } else {
            host.input_devices()?
                .find(|d| d.name().ok().as_deref() == Some(microphone))
        }
        .context("Microphone unavailable")?;
        let output = if speakers == "Default" {
            host.default_output_device()
        } else {
            host.output_devices()?
                .find(|d| d.name().ok().as_deref() == Some(speakers))
        }
        .context("Speakers unavailable")?;
        let ic = input.default_input_config()?;
        let oc = output.default_output_config()?;
        let incoming = Arc::new(Mutex::new(VecDeque::<f32>::new()));
        let outgoing = Arc::new(Mutex::new(VecDeque::<f32>::new()));
        let queue = incoming.clone();
        let channels = ic.channels() as usize;
        let lease = Arc::new(AtomicU64::new(0));
        let capture_lease = lease.clone();
        let capture_pressed = pressed.clone();
        let capture = move |data: &[f32]| {
            if capture_pressed
                .as_ref()
                .is_some_and(|p| !p.load(Ordering::Relaxed))
                || crate::now() >= capture_lease.load(Ordering::Relaxed)
            {
                return;
            }
            if let Ok(mut q) = queue.lock() {
                for frame in data.chunks(channels) {
                    if q.len() < 96000 {
                        q.push_back(frame.iter().sum::<f32>() / channels as f32);
                    }
                }
            }
        };
        let input = match ic.sample_format() {
            cpal::SampleFormat::F32 => input.build_input_stream(
                &ic.clone().into(),
                move |d: &[f32], _| capture(d),
                |e| eprintln!("Audio input: {e}"),
                None,
            )?,
            cpal::SampleFormat::I16 => input.build_input_stream(
                &ic.clone().into(),
                move |d: &[i16], _| {
                    capture(&d.iter().map(|&s| s as f32 / 32768.).collect::<Vec<_>>())
                },
                |e| eprintln!("Audio input: {e}"),
                None,
            )?,
            cpal::SampleFormat::U16 => input.build_input_stream(
                &ic.clone().into(),
                move |d: &[u16], _| {
                    capture(
                        &d.iter()
                            .map(|&s| s as f32 / 32768. - 1.)
                            .collect::<Vec<_>>(),
                    )
                },
                |e| eprintln!("Audio input: {e}"),
                None,
            )?,
            _ => bail!("Unsupported input sample format"),
        };
        let queue = outgoing.clone();
        let channels = oc.channels() as usize;
        let playback = move |count: usize| {
            let mut out = vec![0.; count];
            if let Ok(mut q) = queue.lock() {
                for frame in out.chunks_mut(channels) {
                    let sample = q.pop_front().unwrap_or(0.);
                    frame.fill(sample);
                }
            }
            out
        };
        let output = match oc.sample_format() {
            cpal::SampleFormat::F32 => output.build_output_stream(
                &oc.clone().into(),
                move |d: &mut [f32], _| d.copy_from_slice(&playback(d.len())),
                |e| eprintln!("Audio output: {e}"),
                None,
            )?,
            cpal::SampleFormat::I16 => output.build_output_stream(
                &oc.clone().into(),
                move |d: &mut [i16], _| {
                    let samples = playback(d.len());
                    for (o, v) in d.iter_mut().zip(samples) {
                        *o = (v.clamp(-1., 1.) * 32767.) as i16;
                    }
                },
                |e| eprintln!("Audio output: {e}"),
                None,
            )?,
            cpal::SampleFormat::U16 => output.build_output_stream(
                &oc.clone().into(),
                move |d: &mut [u16], _| {
                    let samples = playback(d.len());
                    for (o, v) in d.iter_mut().zip(samples) {
                        *o = ((v.clamp(-1., 1.) + 1.) * 32767.) as u16;
                    }
                },
                |e| eprintln!("Audio output: {e}"),
                None,
            )?,
            _ => bail!("Unsupported output sample format"),
        };
        output.play()?;
        Ok(Self {
            input,
            _output: output,
            incoming,
            outgoing,
            input_rate: ic.sample_rate().0,
            output_rate: oc.sample_rate().0,
            recording: false,
            pressed,
            lease,
        })
    }
    pub fn recording(&mut self, active: bool) -> Result<()> {
        self.lease.store(
            if active { crate::now() + 3000 } else { 0 },
            Ordering::Relaxed,
        );
        if active != self.recording {
            self.incoming
                .lock()
                .map_err(|_| anyhow::anyhow!("Audio queue poisoned"))?
                .clear();
            if active {
                self.input.play()?;
            } else {
                self.input.pause()?;
            }
            self.recording = active;
        }
        Ok(())
    }
    pub fn allowed(&self) -> bool {
        self.recording
            && self
                .pressed
                .as_ref()
                .is_none_or(|p| p.load(Ordering::Relaxed))
            && crate::now() < self.lease.load(Ordering::Relaxed)
    }
    pub fn read(&mut self, gain: u32) -> Result<Option<Vec<u8>>> {
        if !self.allowed() {
            self.incoming
                .lock()
                .map_err(|_| anyhow::anyhow!("Audio queue poisoned"))?
                .clear();
            return Ok(None);
        }
        let count = (self.input_rate / 5) as usize;
        let mut q = self
            .incoming
            .lock()
            .map_err(|_| anyhow::anyhow!("Audio queue poisoned"))?;
        if q.len() < count {
            return Ok(None);
        }
        let samples: Vec<_> = q.drain(..count).collect();
        drop(q);
        let samples = resample(&samples, self.input_rate, 16000)?;
        Ok(Some(crate::codec::float_bytes(
            &samples
                .into_iter()
                .map(|v| (v * gain as f32 / 100.).clamp(-1., 1.))
                .collect::<Vec<_>>(),
        )))
    }
    pub fn play(&mut self, bytes: &[u8], gain: u32) -> Result<()> {
        let samples = crate::codec::pcm_floats(bytes)?;
        let samples: Vec<_> = samples
            .into_iter()
            .map(|v| (v * gain as f32 / 100.).clamp(-1., 1.))
            .collect();
        let samples = resample(&samples, 16000, self.output_rate)?;
        let mut q = self
            .outgoing
            .lock()
            .map_err(|_| anyhow::anyhow!("Audio queue poisoned"))?;
        if q.len() > self.output_rate as usize {
            q.clear();
        }
        q.extend(samples);
        Ok(())
    }
    pub fn silence(&mut self) {
        if let Ok(mut q) = self.outgoing.lock() {
            q.clear();
        }
    }
}
