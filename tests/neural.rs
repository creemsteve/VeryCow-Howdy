#[cfg(feature = "neural")]
#[test]
fn actual_dac_inference_roundtrip_and_validation() {
    use serde_json::json;
    use vch_core::codec::*;
    let path = std::path::Path::new("build/models");
    assert!(
        path.join("dac-encoder.onnx").exists(),
        "Full voice validation requires the DAC models"
    );
    let mut codec = DacCodec::open(path).unwrap();
    let ready = codec.request(&json!({"op":"init"})).unwrap();
    assert_eq!(ready["sampleRate"], 16000);
    let samples: Vec<f32> = (0..3200)
        .map(|i| (i as f32 * 2. * std::f32::consts::PI * 440. / 16000.).sin() * 0.2)
        .collect();
    for rate in [500, 2000, 6000] {
        let bytes = codec.encode(&float_bytes(&samples), rate).unwrap();
        let packet = Packet::decode(&bytes, 12).unwrap();
        assert_eq!(packet.books, (rate as usize / 500).max(1));
        let decoded = codec.decode(&bytes).unwrap();
        assert_eq!(decoded.len(), 12800);
        let floats = pcm_floats(&decoded).unwrap();
        assert!(floats.iter().all(|f| f.is_finite()));
        assert!(floats.iter().any(|f| f.abs() > 0.01));
        let reply = codec
            .request(&json!({"op":"decode","data":vch_core::crypto::b64(&bytes)}))
            .unwrap();
        assert_eq!(reply["sampleRate"], 16000);
    }
    let encoded = codec
        .request(
            &json!({"op":"encode","bitRate":6000,"data":vch_core::crypto::b64(&float_bytes(&samples))}),
        )
        .unwrap();
    assert!(encoded["ms"].as_f64().unwrap() >= 0.);
    assert!(codec.request(&json!({"op":"unknown"})).is_err());
    assert!(codec.encode(&f32::INFINITY.to_le_bytes(), 6000).is_err());
    assert!(codec.decode(b"bad").is_err());
    assert!(DacCodec::open(std::path::Path::new("target/missing-models")).is_err());
}
