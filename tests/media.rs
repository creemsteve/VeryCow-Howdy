mod common;
use common::*;
use serde_json::json;
#[test]
fn voice_floor_buffers_expiry_revocation() {
    let mut f = Fixture::new();
    let a = f.join("a");
    let b = f.join("b");
    let channel = f.server.state.channels[1].id.clone();
    let text = f.server.state.channels[0].id.clone();
    let request = |action: &str| json!({"channel":channel,"action":action,"after":0});
    status(
        f.request(&a, "voice", json!({"channel":text,"action":"join"})),
        403,
    );
    status(f.request(&a, "voice", request("offer")), 400);
    let e = f.envelope(&a, &channel, "voice", b"tokens");
    status(
        f.request(
            &a,
            "voice",
            json!({"channel":channel,"action":"media","envelope":e}),
        ),
        403,
    );
    let join = f.request(&a, "voice", request("join")).unwrap();
    assert_eq!(join["transport"], "https-relay-v1");
    f.request(&b, "voice", request("join")).unwrap();
    let claim = f.request(&a, "voice", request("claim")).unwrap();
    assert_eq!(claim["floor"], a.hash);
    assert_eq!(
        f.request(&b, "voice", request("claim")).unwrap()["floor"],
        a.hash
    );
    for _ in 0..8 {
        let e = f.envelope(&a, &channel, "voice", b"tokens");
        f.request(
            &a,
            "voice",
            json!({"channel":channel,"action":"media","envelope":e}),
        )
        .unwrap();
    }
    let frames = f.request(&b, "voice", request("heartbeat")).unwrap();
    assert_eq!(frames["frames"].as_array().unwrap().len(), 6);
    let cursor = frames["cursor"].clone();
    assert_eq!(
        f.request(
            &b,
            "voice",
            json!({"channel":channel,"action":"heartbeat","after":cursor})
        )
        .unwrap()["frames"],
        json!([])
    );
    let duplicate = frames["frames"][5].clone();
    f.request(
        &a,
        "voice",
        json!({"channel":channel,"action":"media","envelope":duplicate}),
    )
    .unwrap();
    f.now += 3001;
    assert_eq!(
        f.request(&b, "voice", request("heartbeat")).unwrap()["frames"],
        json!([])
    );
    f.now += 500;
    assert_eq!(
        f.request(&b, "voice", request("claim")).unwrap()["floor"],
        b.hash
    );
    f.request(&b, "voice", request("release")).unwrap();
    assert_eq!(
        f.request(&a, "voice", request("heartbeat")).unwrap()["floor"],
        ""
    );
    f.request(&a, "voice", request("claim")).unwrap();
    let stale = f.envelope(&a, &channel, "voice", b"stale");
    f.server.rotate_channel_key(&channel).unwrap();
    status(
        f.request(
            &a,
            "voice",
            json!({"channel":channel,"action":"media","envelope":stale}),
        ),
        409,
    );
    let mut bad = f.envelope(&a, &channel, "voice", b"tokens");
    bad.kind = "text".into();
    status(
        f.request(
            &a,
            "voice",
            json!({"channel":channel,"action":"media","envelope":bad}),
        ),
        400,
    );
    f.request(&a, "voice", request("leave")).unwrap();
    assert_eq!(
        f.request(&b, "voice", request("heartbeat")).unwrap()["floor"],
        ""
    );
    f.server.state.channels[1].allow.insert(a.hash.clone());
    f.server.save().unwrap();
    status(f.request(&b, "voice", request("heartbeat")), 403);
}

#[test]
fn video_subscriptions_bitrate_replay_and_lifecycle() {
    let mut f = Fixture::new();
    let a = f.join("a");
    let b = f.join("b");
    let channel = f.server.state.channels[1].id.clone();
    let video = |action: &str| json!({"channel":channel,"action":action});
    status(f.request(&a, "video", video("start")), 403);
    for m in [&a, &b] {
        f.request(m, "voice", json!({"channel":channel,"action":"join"}))
            .unwrap();
    }
    status(
        f.request(
            &a,
            "video",
            json!({"channel":channel,"action":"start","source":"file"}),
        ),
        400,
    );
    for kbps in [0, 49, 1001] {
        status(
            f.request(
                &a,
                "video",
                json!({"channel":channel,"action":"start","source":"screen","kbps":kbps}),
            ),
            400,
        );
    }
    let stream = f
        .request(
            &a,
            "video",
            json!({"channel":channel,"action":"start","source":"screen","kbps":1000}),
        )
        .unwrap()["stream"]
        .as_str()
        .unwrap()
        .to_owned();
    status(
        f.request(
            &a,
            "video",
            json!({"channel":channel,"action":"start","source":"screen"}),
        ),
        409,
    );
    let camera = f
        .request(
            &a,
            "video",
            json!({"channel":channel,"action":"start","source":"camera","kbps":500}),
        )
        .unwrap()["stream"]
        .as_str()
        .unwrap()
        .to_owned();
    f.request(
        &a,
        "video",
        json!({"channel":channel,"action":"configure","stream":camera,"kbps":1000}),
    )
    .unwrap();
    status(
        f.request(
            &b,
            "video",
            json!({"channel":channel,"action":"stop","stream":stream}),
        ),
        403,
    );
    let e = f.envelope(&a, &channel, &format!("video/{stream}"), b"video frame");
    let media = json!({"channel":channel,"action":"media","stream":stream,"envelope":e});
    f.request(&a, "video", media.clone()).unwrap();
    f.request(&a, "video", media).unwrap();
    let poll = f.request(&b, "video", video("poll")).unwrap();
    assert_eq!(poll["streams"].as_array().unwrap().len(), 2);
    assert_eq!(poll["frames"], json!([]));
    let poll = f
        .request(
            &b,
            "video",
            json!({"channel":channel,"action":"poll","receive":[stream],"after":-1}),
        )
        .unwrap();
    assert_eq!(poll["frames"].as_array().unwrap().len(), 1);
    let cursor = poll["cursor"].clone();
    assert_eq!(
        f.request(
            &b,
            "video",
            json!({"channel":channel,"action":"poll","receive":[stream],"after":cursor})
        )
        .unwrap()["frames"],
        json!([])
    );
    status(
        f.request(
            &b,
            "video",
            json!({"channel":channel,"action":"poll","receive":vec!["x";65]}),
        ),
        400,
    );
    f.now += 1501;
    assert_eq!(
        f.request(
            &b,
            "video",
            json!({"channel":channel,"action":"poll","receive":[stream]})
        )
        .unwrap()["frames"],
        json!([])
    );
    let stale = f.envelope(&a, &channel, &format!("video/{stream}"), b"stale");
    f.server.rotate_channel_key(&channel).unwrap();
    status(
        f.request(
            &a,
            "video",
            json!({"channel":channel,"action":"media","stream":stream,"envelope":stale}),
        ),
        409,
    );
    let mut invalid = f.envelope(&a, &channel, &format!("video/{stream}"), b"bad");
    invalid.signature = "bad".into();
    status(
        f.request(
            &a,
            "video",
            json!({"channel":channel,"action":"media","stream":stream,"envelope":invalid}),
        ),
        400,
    );
    // The rate charge uses encrypted byte size; signed random payloads prevent DEFLATE from hiding the budget.
    f.request(
        &a,
        "video",
        json!({"channel":channel,"action":"configure","stream":stream,"kbps":50}),
    )
    .unwrap();
    let e = f.envelope(
        &a,
        &channel,
        &format!("video/{stream}"),
        &vch_core::crypto::random(7000),
    );
    status(
        f.request(
            &a,
            "video",
            json!({"channel":channel,"action":"media","stream":stream,"envelope":e}),
        ),
        429,
    );
    f.request(
        &a,
        "video",
        json!({"channel":channel,"action":"stop","stream":camera}),
    )
    .unwrap();
    status(
        f.request(
            &a,
            "video",
            json!({"channel":channel,"action":"invalid","stream":stream}),
        ),
        400,
    );
    f.now += 10001;
    f.server.cleanup(f.now);
    f.request(&b, "voice", json!({"channel":channel,"action":"join"}))
        .unwrap();
    assert_eq!(
        f.request(&b, "video", video("poll")).unwrap()["streams"],
        json!([])
    );
}
