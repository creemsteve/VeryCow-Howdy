mod common;
use common::*;
use serde_json::{json, Value};
use vch_core::{crypto, models::*};

#[test]
fn messaging_profiles_friends_calls_and_bans() {
    let mut f = Fixture::new();
    let alice = f.join("alice-ip");
    let bob = f.join("bob-ip");
    let outsider = f.join("outsider-ip");
    let text = f.server.state.channels[0].id.clone();
    let voice = f.server.state.channels[1].id.clone();
    let e = f.envelope(&alice, &text, "text", b"hello");
    let result = f
        .request(&alice, "send", serde_json::to_value(&e).unwrap())
        .unwrap();
    assert_eq!(result["sequence"], 1);
    assert!(f
        .request(&alice, "send", serde_json::to_value(&e).unwrap())
        .is_ok());
    let poll = f.request(&bob, "poll", json!({"after":0})).unwrap();
    assert_eq!(poll["messages"].as_array().unwrap().len(), 1);
    assert_eq!(poll["cursor"], 1);
    assert_eq!(
        f.request(&bob, "poll", json!({"after":1})).unwrap()["messages"],
        json!([])
    );
    f.request(
        &alice,
        "profile",
        json!({"name":"Alice","bio":"hello","pronouns":"she/her","avatar":""}),
    )
    .unwrap();
    assert_eq!(f.server.state.accounts[&alice.hash].name, "Alice");
    for name in ["", "<script>", &"a".repeat(41), "bad\nname"] {
        status(f.request(&alice, "profile", json!({"name":name})), 400);
    }
    status(
        f.request(
            &alice,
            "profile",
            json!({"name":"Alice","avatar":crypto::b64(b"not webp")}),
        ),
        413,
    );
    f.request(&alice, "typing", json!({"channel":text}))
        .unwrap();
    assert_eq!(
        f.request(&bob, "poll", json!({})).unwrap()["typing"][&alice.hash],
        text
    );
    f.now += 5001;
    assert_eq!(
        f.request(&bob, "poll", json!({})).unwrap()["typing"],
        json!({})
    );
    f.request(&alice, "typing", json!({"channel":""})).unwrap();
    status(f.request(&alice, "typing", json!({"channel":voice})), 403);
    status(
        f.request(
            &alice,
            "friend",
            json!({"action":"request","account":alice.hash}),
        ),
        400,
    );
    status(
        f.request(
            &alice,
            "friend",
            json!({"action":"accept","account":bob.hash}),
        ),
        409,
    );
    f.request(
        &alice,
        "friend",
        json!({"action":"request","account":bob.hash}),
    )
    .unwrap();
    assert_eq!(
        f.request(&bob, "poll", json!({})).unwrap()["friendRequests"],
        json!([alice.hash])
    );
    f.request(
        &bob,
        "friend",
        json!({"action":"decline","account":alice.hash}),
    )
    .unwrap();
    f.request(
        &alice,
        "friend",
        json!({"action":"request","account":bob.hash}),
    )
    .unwrap();
    f.request(
        &bob,
        "friend",
        json!({"action":"accept","account":alice.hash}),
    )
    .unwrap();
    let friend = f.server.state.friendships[0].clone();
    let outsiders = f.request(&outsider, "keys", json!({})).unwrap();
    assert!(outsiders["channelKeys"].get(&friend.text_channel).is_none());
    status(
        f.request(
            &alice,
            "friend",
            json!({"action":"request","account":bob.hash}),
        ),
        409,
    );
    let call = f
        .request(&alice, "call", json!({"action":"ring","account":bob.hash}))
        .unwrap();
    status(
        f.request(&alice, "call", json!({"action":"ring","account":bob.hash})),
        409,
    );
    status(
        f.request(
            &outsider,
            "call",
            json!({"action":"ring","account":bob.hash}),
        ),
        403,
    );
    status(
        f.request(
            &outsider,
            "call",
            json!({"action":"accept","id":call["id"]}),
        ),
        403,
    );
    f.request(&bob, "call", json!({"action":"accept","id":call["id"]}))
        .unwrap();
    let call = f
        .request(&alice, "call", json!({"action":"ring","account":bob.hash}))
        .unwrap();
    f.request(&alice, "call", json!({"action":"cancel","id":call["id"]}))
        .unwrap();
    let call = f
        .request(&bob, "call", json!({"action":"ring","account":alice.hash}))
        .unwrap();
    f.request(&alice, "call", json!({"action":"decline","id":call["id"]}))
        .unwrap();
    let call = f
        .request(&alice, "call", json!({"action":"ring","account":bob.hash}))
        .unwrap();
    f.now += 30001;
    status(
        f.request(&bob, "call", json!({"action":"accept","id":call["id"]})),
        404,
    );
    f.request(
        &alice,
        "friend",
        json!({"action":"remove","account":bob.hash}),
    )
    .unwrap();
    assert!(f.server.state.friendships.is_empty());
    assert!(!f
        .server
        .state
        .channel_keys
        .contains_key(&friend.text_channel));
    status(
        f.request(
            &alice,
            "friend",
            json!({"action":"remove","account":bob.hash}),
        ),
        404,
    );
    status(
        f.request(
            &alice,
            "friend",
            json!({"action":"unknown","account":bob.hash}),
        ),
        400,
    );
    let old = f.server.state.channel_keys[&text].revision;
    f.server.ban(&alice.hash, false).unwrap();
    assert!(f.server.state.channel_keys[&text].revision > old);
    status(f.request(&alice, "poll", json!({})), 401);
    f.server.ban("127.0.0.3", true).unwrap();
    status(
        f.server
            .route("127.0.0.3", "poll", None, &json!({}), None, f.now),
        403,
    );
    status(
        f.server.route(
            "wrong-ip",
            "poll",
            Some(&bob.token),
            &json!({}),
            None,
            f.now,
        ),
        401,
    );
    status(f.request(&bob, "unknown", json!({})), 404);
    f.now += 8 * 3600000 + 1;
    status(f.request(&bob, "poll", json!({})), 401);
}

#[test]
fn role_hierarchy_admin_configuration_and_key_rotation() {
    let mut f = Fixture::new();
    let admin = f.join("admin");
    let manager = f.join("manager");
    let member = f.join("member");
    f.server.state.admins.insert(admin.hash.clone());
    let c = f.server.state.channels[0].id.clone();
    status(
        f.request(
            &member,
            "roles",
            json!({"roles":[],"everyonePermissions":[]}),
        ),
        403,
    );
    status(f.request(&member, "admin", json!({})), 403);
    let low = Role {
        name: "Members".into(),
        permissions: ["view".into()].into(),
        members: [member.hash.clone()].into(),
        ..Role::default()
    };
    let high = Role {
        name: "Managers".into(),
        permissions: ["manage_roles".into(), "send".into(), "connect".into()].into(),
        members: [manager.hash.clone()].into(),
        ..Role::default()
    };
    let top = Role {
        name: "Top".into(),
        ..Role::default()
    };
    let base = json!(["view", "send", "connect"]);
    f.request(
        &admin,
        "roles",
        json!({"roles":[low,high,top],"everyonePermissions":base}),
    )
    .unwrap();
    let revision = f.server.state.channel_keys[&c].revision;
    let mut updated = low.clone();
    updated.name = "Renamed".into();
    f.request(
        &manager,
        "roles",
        json!({"roles":[updated,high,top],"everyonePermissions":base}),
    )
    .unwrap();
    assert!(f.server.state.channel_keys[&c].revision > revision);
    let mut invalid_high = high.clone();
    invalid_high.name = "Changed own role".into();
    status(
        f.request(
            &manager,
            "roles",
            json!({"roles":[updated,invalid_high,top],"everyonePermissions":base}),
        ),
        403,
    );
    status(
        f.request(
            &manager,
            "roles",
            json!({"roles":[updated,high,top],"everyonePermissions":[]}),
        ),
        403,
    );
    let mut escalation = updated.clone();
    escalation.permissions.insert("unavailable".into());
    status(
        f.request(
            &admin,
            "roles",
            json!({"roles":[escalation,high,top],"everyonePermissions":base}),
        ),
        400,
    );
    let mut lower = updated.clone();
    lower.members.insert(manager.hash.clone());
    status(
        f.request(
            &manager,
            "roles",
            json!({"roles":[lower,high,top],"everyonePermissions":base}),
        ),
        403,
    );
    let mut channels = f.server.state.channels.clone();
    channels[0]
        .role_overrides
        .insert("everyone".into(), [("send".into(), false)].into());
    channels[0]
        .role_overrides
        .insert(high.id.clone(), [("send".into(), true)].into());
    let image = "";
    f.request(&admin,"admin",json!({"name":"Renamed community","description":"About us","image":image,"channels":channels})).unwrap();
    let e = f.envelope(&member, &c, "text", b"denied");
    status(
        f.request(&member, "send", serde_json::to_value(e).unwrap()),
        403,
    );
    let e = f.envelope(&manager, &c, "text", b"allowed");
    f.request(&manager, "send", serde_json::to_value(e).unwrap())
        .unwrap();
    let mut bad = channels.clone();
    bad[0].kind = "voice".into();
    status(
        f.request(&admin, "admin", json!({"name":"Valid","channels":bad})),
        400,
    );
    let mut bad = channels.clone();
    bad[0]
        .role_overrides
        .insert("unknown-role".into(), [("send".into(), true)].into());
    status(
        f.request(&admin, "admin", json!({"name":"Valid","channels":bad})),
        400,
    );
    channels[0].allow.insert(member.hash.clone());
    f.request(
        &admin,
        "admin",
        json!({"name":"Restricted","channels":channels}),
    )
    .unwrap();
    assert!(f.request(&admin, "keys", json!({})).unwrap()["channelKeys"]
        .get(&c)
        .is_none());
    assert!(
        f.request(&member, "keys", json!({})).unwrap()["channelKeys"]
            .get(&c)
            .is_some()
    );
    f.server
        .set_password(zeroize::Zeroizing::new("replacement join password".into()))
        .unwrap();
    status(f.request(&member, "poll", json!({})), 401);
}

#[test]
fn envelopes_revisions_limits_and_retention() {
    let mut f = Fixture::new();
    let a = f.join("a");
    let c = f.server.state.channels[0].id.clone();
    let stale = f.envelope(&a, &c, "text", b"stale");
    f.server.rotate_channel_key(&c).unwrap();
    status(
        f.request(&a, "send", serde_json::to_value(stale).unwrap()),
        409,
    );
    let mut e = f.envelope(&a, &c, "text", b"secret");
    e.author = "forged".into();
    status(f.request(&a, "send", serde_json::to_value(e).unwrap()), 400);
    let mut e = f.envelope(&a, &c, "text", b"secret");
    e.signature = crypto::b64(&[0; 3309]);
    status(f.request(&a, "send", serde_json::to_value(e).unwrap()), 400);
    let e = f.envelope(&a, &c, "file", b"blocked");
    status(f.request(&a, "send", serde_json::to_value(e).unwrap()), 400);
    let e = f.envelope(&a, &c, "text", b"expired");
    f.now += 300001;
    status(f.request(&a, "send", serde_json::to_value(e).unwrap()), 400);
    f.server.state.channels[0].slow_mode_seconds = 10;
    let e = f.envelope(&a, &c, "text", b"first");
    f.request(&a, "send", serde_json::to_value(e).unwrap())
        .unwrap();
    let e = f.envelope(&a, &c, "text", b"second");
    status(
        f.request(&a, "send", serde_json::to_value(&e).unwrap()),
        429,
    );
    f.now += 10000;
    f.request(&a, "send", serde_json::to_value(e).unwrap())
        .unwrap();
    f.server.state.messages = vec![Envelope::default(); 10000];
    f.now += 10000;
    let e = f.envelope(&a, &c, "text", b"bounded");
    f.request(&a, "send", serde_json::to_value(e).unwrap())
        .unwrap();
    assert_eq!(f.server.state.messages.len(), 10000);
    assert!(f.server.state.messages.last().unwrap().sequence > 0);
}

#[test]
fn join_admission_expiry_replay_and_rates() {
    let mut f = Fixture::new();
    let first = f.server.admit("ip", 0).unwrap();
    assert_eq!(f.server.admit("ip", 9999).unwrap_err().retry_after, 1);
    let second = f.server.admit("ip", 10000).unwrap();
    status(
        f.server
            .route("ip", "challenge", None, &json!({}), Some(&first), f.now),
        429,
    );
    let c = f
        .server
        .route("ip", "challenge", None, &json!({}), Some(&second), f.now)
        .unwrap();
    status(
        f.server.route(
            "ip",
            "auth",
            None,
            &json!({"nonce":c["nonce"],"A":"0","M1":"0"}),
            None,
            f.now,
        ),
        401,
    );
    status(
        f.server.route(
            "ip",
            "auth",
            None,
            &json!({"nonce":c["nonce"]}),
            None,
            f.now,
        ),
        429,
    );
    let m = f.join("member");
    for _ in 0..1200 {
        let _ = f.request(&m, "keys", json!({}));
    }
    status(f.request(&m, "keys", json!({})), 429);
    f.now += 60001;
    assert!(f.request(&m, "keys", json!({})).is_ok());
    status(
        f.server
            .route("unauth", "login", None, &json!({}), None, f.now),
        401,
    );
    status(
        f.server
            .route("unauth", "login", None, &json!({}), None, f.now),
        429,
    );
    status(
        f.server
            .route("free", "voice", None, &json!({}), None, f.now),
        401,
    );
    status(f.request(&m, "poll", Value::Null), 400);
    let t = f.server.admit("expire", 0).unwrap();
    let c = f
        .server
        .route("expire", "challenge", None, &json!({}), Some(&t), f.now)
        .unwrap();
    f.now += 120001;
    status(
        f.server.route(
            "expire",
            "auth",
            None,
            &json!({"nonce":c["nonce"]}),
            None,
            f.now,
        ),
        429,
    );
    assert!(vch_core::server::valid_hash(&m.hash));
    assert!(!vch_core::server::valid_hash("bad"));
    assert!(vch_core::server::valid_id(&id()));
    assert!(!vch_core::server::valid_id(
        "ffffffffffffffffffffffffffffffffffff"
    ));
}
