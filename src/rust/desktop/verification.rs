use super::*;
use egui::{epaint::Shape, Event as InputEvent, PointerButton, Pos2, RawInput, Rect, Vec2};

struct Ui {
    ctx: egui::Context,
    app: App,
    output: egui::FullOutput,
    time: f64,
}
impl Ui {
    fn new(app: App) -> Self {
        let mut ui = Self {
            ctx: Default::default(),
            app,
            output: Default::default(),
            time: 0.0,
        };
        ui.frame(vec![]);
        ui
    }
    fn frame(&mut self, events: Vec<InputEvent>) {
        self.time += 0.1;
        // Keep periodic polling separate from the action under test.
        self.app.last_poll = Instant::now();
        self.output = self.ctx.run(
            RawInput {
                screen_rect: Some(Rect::from_min_size(Pos2::ZERO, Vec2::new(1400.0, 2400.0))),
                focused: true,
                time: Some(self.time),
                events,
                ..Default::default()
            },
            |ctx| self.app.ui(ctx),
        );
    }
    fn point(&self, label: &str) -> Pos2 {
        fn locate(shape: &Shape, label: &str) -> Option<Pos2> {
            match shape {
                Shape::Text(t) if t.galley.text() == label => {
                    Some(t.galley.rect.translate(t.pos.to_vec2()).center())
                }
                Shape::Vec(shapes) => shapes.iter().find_map(|s| locate(s, label)),
                _ => None,
            }
        }
        self.output
            .shapes
            .iter()
            .find_map(|s| locate(&s.shape, label))
            .unwrap_or_else(|| panic!("Missing visible control: {label}"))
    }
    fn click(&mut self, label: &str) {
        self.frame(vec![]);
        let pos = self.point(label);
        self.frame(vec![
            InputEvent::PointerMoved(pos),
            InputEvent::PointerButton {
                pos,
                button: PointerButton::Primary,
                pressed: true,
                modifiers: Default::default(),
            },
        ]);
        self.frame(vec![InputEvent::PointerButton {
            pos,
            button: PointerButton::Primary,
            pressed: false,
            modifiers: Default::default(),
        }]);
        for _ in 0..3 {
            self.frame(vec![]);
        }
    }
    fn done(&mut self) {
        let end = Instant::now() + Duration::from_secs(20);
        while self.app.busy {
            assert!(Instant::now() < end, "Desktop action timed out");
            std::thread::sleep(Duration::from_millis(10));
            self.frame(vec![]);
        }
        self.frame(vec![]);
        assert!(self.app.error.is_empty(), "{}", self.app.error);
    }
}
fn vault(path: &std::path::Path) -> Vault {
    Vault::open(
        path.join("data.vault"),
        Zeroizing::new("desktop verification password".into()),
    )
    .unwrap()
}

#[test]
fn push_to_talk_release_focus_and_typing() {
    for (setting, key) in [
        ("SPACE", egui::Key::Space),
        ("V", egui::Key::V),
        ("B", egui::Key::B),
    ] {
        let ctx = egui::Context::default();
        let mut active = false;
        let mut run = |pressed: bool, focused: bool, typing: bool| {
            let _ = ctx.run(
                RawInput {
                    focused,
                    events: vec![InputEvent::Key {
                        key,
                        physical_key: None,
                        pressed,
                        repeat: false,
                        modifiers: Default::default(),
                    }],
                    ..Default::default()
                },
                |ctx| {
                    egui::CentralPanel::default().show(ctx, |ui| {
                        let button = ui.add(
                            egui::Button::new("Hold to talk").sense(egui::Sense::click_and_drag()),
                        );
                        if typing {
                            ui.text_edit_singleline(&mut String::new()).request_focus();
                        } else {
                            ctx.memory_mut(|m| {
                                if let Some(id) = m.focused() {
                                    m.surrender_focus(id);
                                }
                            });
                        }
                        active = talk_pressed(ctx, &button, setting);
                    });
                },
            );
            active
        };
        assert!(run(true, true, false));
        assert!(!run(false, true, false));
        assert!(!run(true, false, false));
        assert!(!run(true, true, true));
    }
}
fn server_app(dir: &std::path::Path) -> Ui {
    let mut server = ChatServer::open(vault(dir)).unwrap();
    server.state.port = 0;
    server.state.hash_bits = 8;
    server
        .set_password(Zeroizing::new("desktop join password".into()))
        .unwrap();
    let mut ui = Ui::new(App::from_server(server, dir.into(), "build/models".into()));
    ui.app.bind = "127.0.0.1".into();
    ui
}

#[test]
fn vault_ui_validation_lock_and_worker_isolation() {
    let dir = tempfile::tempdir().unwrap();
    let mut ui = Ui::new(App::new(
        Mode::Client,
        dir.path().into(),
        "build/models".into(),
    ));
    ui.app.password = "first".into();
    ui.app.confirm = "second".into();
    ui.click("Create vault");
    assert_eq!(ui.app.error, "Passwords do not match");
    ui.app.password = "short".into();
    ui.app.confirm = "short".into();
    ui.click("Create vault");
    while ui.app.busy {
        std::thread::sleep(Duration::from_millis(10));
        ui.frame(vec![]);
    }
    assert!(!ui.app.unlocked);
    assert!(!ui.app.error.is_empty());
    ui.app.password = "desktop verification password".into();
    ui.app.confirm = ui.app.password.clone();
    ui.click("Create vault");
    ui.done();
    assert!(ui.app.unlocked);
    assert!(ui.app.password.is_empty() && ui.app.confirm.is_empty());
    ui.app.message = "private draft".into();
    ui.app.target = "private account".into();
    ui.app.edit_description = "private description".into();
    let sender = ui.app.tx.clone();
    ui.app.busy = true;
    ui.click("Lock");
    assert!(!ui.app.unlocked);
    assert!(ui.app.client.is_none());
    assert!(
        ui.app.message.is_empty() && ui.app.target.is_empty() && ui.app.edit_description.is_empty()
    );
    assert!(sender.send(Event::Done(Ok("stale result".into()))).is_err());
    // The previous vault handle was released and the normal unlock flow still works.
    ui.app.password = "desktop verification password".into();
    ui.click("Unlock");
    ui.done();
    assert!(ui.app.unlocked);
}

#[test]
fn client_desktop_actions_persist_and_reconnect() {
    let dir = tempfile::tempdir().unwrap();
    let mut server = server_app(&dir.path().join("server"));
    server.click("Start server");
    let address = server.app.running.as_ref().unwrap().address;
    let mut core = ClientCore::open(vault(&dir.path().join("client"))).unwrap();
    let mut saved = SavedServer {
        address: address.to_string(),
        ..Default::default()
    };
    core.connect(
        &mut saved,
        Some(Zeroizing::new("desktop join password".into())),
    )
    .unwrap();
    let channel = saved
        .channels
        .iter()
        .find(|c| c.kind == "text")
        .unwrap()
        .id
        .clone();
    let mut ui = Ui::new(App::from_client(
        core,
        dir.path().join("client"),
        "build/models".into(),
    ));
    ui.app.channel = channel.clone();
    ui.app.message = "Sent through the native desktop action".into();
    let mut png = std::io::Cursor::new(Vec::new());
    image::DynamicImage::new_rgb8(32, 32)
        .write_to(&mut png, image::ImageFormat::Png)
        .unwrap();
    ui.app
        .staged
        .push(crate::images::prepare_bytes(png.get_ref(), 100000, 32).unwrap());
    ui.click("Send");
    ui.done();
    assert!(ui.app.message.is_empty() && ui.app.staged.is_empty());
    let server_core = server.app.server.as_ref().unwrap();
    assert_eq!(server_core.lock().unwrap().state.messages.len(), 2);
    ui.click("Profile");
    ui.app.profile.name = "Desktop profile".into();
    ui.app.profile.pronouns = "they/them".into();
    ui.app.profile.bio = "Persisted from the desktop".into();
    ui.click("Save profile");
    ui.done();
    assert_eq!(ui.app.view.name, "Desktop profile");
    assert!(server_core
        .lock()
        .unwrap()
        .state
        .accounts
        .values()
        .any(|a| a.bio == "Persisted from the desktop"));
    ui.click("Audio");
    ui.app.profile.input_gain = 125;
    ui.app.profile.output_gain = 85;
    ui.app.profile.ptt_key = "V".into();
    ui.click("Save audio preferences");
    ui.done();
    assert_eq!((ui.app.view.input_gain, ui.app.view.output_gain), (125, 85));
    assert_eq!(ui.app.view.ptt_key, "V");
    let previous = ui.app.view.dark_mode;
    ui.click(if previous { "Light mode" } else { "Dark mode" });
    ui.done();
    assert_eq!(ui.app.view.dark_mode, !previous);
    ui.click("Disconnect");
    ui.done();
    assert!(!ui
        .app
        .client
        .as_ref()
        .unwrap()
        .lock()
        .unwrap()
        .connected(&ui.app.saved().unwrap()));
    // Successful joins also activate the intentional per-IP admission cooldown.
    std::thread::sleep(Duration::from_secs(11));
    ui.click("Reconnect");
    ui.done();
    assert!(ui
        .app
        .client
        .as_ref()
        .unwrap()
        .lock()
        .unwrap()
        .connected(&ui.app.saved().unwrap()));
    ui.click("Connection");
    ui.app.socks_port = 19050;
    ui.click("Save connection settings");
    ui.done();
    assert_eq!(ui.app.saved().unwrap().tor_socks_port, 19050);
    ui.click("Lock");
    let restored = ClientCore::open(vault(&dir.path().join("client"))).unwrap();
    assert_eq!(restored.state.name, "Desktop profile");
    assert_eq!(restored.state.input_gain, 125);
    assert_eq!(restored.state.servers[0].tor_socks_port, 19050);
    server.click("Stop server");
    assert!(server.app.running.is_none());
}

#[test]
fn server_desktop_editors_save_rotate_and_stop() {
    let dir = tempfile::tempdir().unwrap();
    let mut ui = server_app(dir.path());
    ui.click("Overview");
    ui.app.edit_name = "Verified desktop community".into();
    ui.app.edit_description = "Local console settings".into();
    ui.app.edit_port = 15885;
    ui.click("Save overview");
    ui.done();
    assert_eq!(ui.app.server_view.name, "Verified desktop community");
    ui.click("Overview");
    ui.click("Channels");
    ui.click("Add text channel");
    assert_eq!(ui.app.edit_channels.len(), 3);
    ui.click("Add voice channel");
    assert_eq!(ui.app.edit_channels[3].kind, "voice");
    ui.app.edit_channels[2].name = "New text".into();
    ui.app.edit_channels[3].name = "New voice".into();
    ui.click("Save channels");
    ui.done();
    assert_eq!(ui.app.server_view.channels.len(), 4);
    let id = ui.app.server_view.channels[2].id.clone();
    let revision = ui.app.server_view.channel_keys[&id].revision;
    ui.click("Rotate key: New text");
    ui.done();
    assert_eq!(ui.app.server_view.channel_keys[&id].revision, revision + 1);
    ui.click("Channels");
    ui.click("Roles");
    ui.click("Add role");
    ui.app.edit_roles[0].name = "Verified role".into();
    ui.app.edit_roles[0].permissions.insert("send".into());
    ui.click("Save roles");
    ui.done();
    assert_eq!(ui.app.server_view.roles[0].name, "Verified role");
    ui.click("Roles");
    ui.click("Accounts & bans");
    ui.app.target = "192.0.2.99".into();
    ui.app.ban_ip = true;
    ui.click("Ban");
    ui.done();
    assert!(ui.app.server_view.banned_ips.contains("192.0.2.99"));
    ui.click("Unban");
    ui.done();
    assert!(!ui.app.server_view.banned_ips.contains("192.0.2.99"));
    ui.click("Start server");
    assert!(ui.app.running.is_some());
    ui.click("Lock");
    assert!(ui.app.running.is_none() && ui.app.server.is_none());
    let server = ChatServer::open(vault(dir.path())).unwrap();
    assert_eq!(server.state.name, "Verified desktop community");
    assert_eq!(server.state.channels.len(), 4);
    assert_eq!(server.state.roles[0].name, "Verified role");
}
