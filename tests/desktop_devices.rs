#![cfg(feature = "audio")]
use std::{
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::{Duration, Instant},
};
use vch_core::{
    audio::{self, Devices},
    codec,
};

/// Explicitly opt in: records a short block and plays a quiet test tone on real hardware.
#[test]
#[ignore = "uses the host microphone and speakers; run explicitly for desktop verification"]
fn native_devices_capture_playback_and_ptt_gating() {
    let (inputs, outputs) = audio::devices().unwrap();
    println!("Inputs: {inputs:?}; outputs: {outputs:?}");
    assert!(!inputs.is_empty(), "No microphone available");
    assert!(!outputs.is_empty(), "No speaker available");
    let pressed = Arc::new(AtomicBool::new(false));
    let mut devices = Devices::open_gated("Default", "Default", Some(pressed.clone())).unwrap();
    devices.recording(true).unwrap();
    assert!(!devices.allowed());
    assert!(devices.read(100).unwrap().is_none());
    pressed.store(true, Ordering::Relaxed);
    let deadline = Instant::now() + Duration::from_secs(5);
    let pcm = loop {
        devices.recording(true).unwrap();
        if let Some(pcm) = devices.read(100).unwrap() {
            break pcm;
        }
        assert!(
            Instant::now() < deadline,
            "Microphone callback did not deliver audio"
        );
        std::thread::sleep(Duration::from_millis(40));
    };
    let samples = codec::pcm_floats(&pcm).unwrap();
    assert_eq!(samples.len(), 3200);
    assert!(samples
        .iter()
        .all(|x| x.is_finite() && (-1.0..=1.0).contains(x)));
    pressed.store(false, Ordering::Relaxed);
    assert!(!devices.allowed());
    assert!(devices.read(100).unwrap().is_none());
    devices.recording(false).unwrap();
    let tone: Vec<_> = (0..3200)
        .map(|i| (i as f32 * 440.0 * std::f32::consts::TAU / 16000.0).sin() * 0.02)
        .collect();
    devices.play(&codec::float_bytes(&tone), 100).unwrap();
    std::thread::sleep(Duration::from_millis(250));
    devices.silence();
    drop(devices);
    let mut devices = Devices::open("Default", "Default").unwrap();
    devices.recording(true).unwrap();
    assert!(devices.allowed());
    std::thread::sleep(Duration::from_millis(3050));
    assert!(
        !devices.allowed(),
        "Capture lease must expire without renewal"
    );
    assert!(devices.read(100).unwrap().is_none());
    devices.recording(false).unwrap();
}
