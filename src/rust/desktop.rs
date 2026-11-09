use crate::{
    client::ClientCore,
    models::*,
    server::{ChatServer, RunningServer},
    vault::Vault,
    voice::{VoiceBridge, VoiceSession},
};
use anyhow::Result;
use eframe::egui;
use serde_json::json;
use std::{
    collections::BTreeMap,
    path::PathBuf,
    sync::{mpsc, Arc, Mutex},
    time::{Duration, Instant},
};
use zeroize::Zeroizing;
mod carbon;
#[derive(Clone, Copy, PartialEq)]
pub enum Mode {
    Client,
    Server,
}
enum Event {
    Client(Result<Box<ClientCore>>),
    Server(Result<Box<ChatServer>>),
    Done(Result<String>),
}
#[derive(Clone, Copy, PartialEq, Debug)]
enum Page {
    Chat,
    Profile,
    Audio,
    Friends,
    Manage,
    Roles,
    Connection,
}
pub struct App {
    mode: Mode,
    data: PathBuf,
    models: PathBuf,
    password: String,
    confirm: String,
    unlocked: bool,
    busy: bool,
    error: String,
    status: String,
    client: Option<Arc<Mutex<ClientCore>>>,
    server: Option<Arc<Mutex<ChatServer>>>,
    running: Option<RunningServer>,
    tx: mpsc::Sender<Event>,
    rx: mpsc::Receiver<Event>,
    view: ClientState,
    server_view: ServerState,
    page: Page,
    selected: usize,
    channel: String,
    message: String,
    sending: bool,
    address: String,
    join_password: String,
    tor: bool,
    socks_port: u16,
    bind: String,
    join_setting: String,
    profile: ClientState,
    edit_channels: Vec<Channel>,
    edit_roles: Vec<Role>,
    everyone: std::collections::BTreeSet<String>,
    edit_name: String,
    edit_description: String,
    edit_image: String,
    edit_port: u16,
    target: String,
    ban_ip: bool,
    staged: Vec<Vec<u8>>,
    last_poll: Instant,
    textures: BTreeMap<String, egui::TextureHandle>,
    plaintext: BTreeMap<String, String>,
    bridge: Option<VoiceBridge>,
    #[cfg(feature = "audio")]
    voice: Option<crate::voice::NativeVoice>,
    voice_channel: String,
}
impl App {
    /// Build a desktop view from a vault that the caller has already unlocked.
    pub fn from_client(core: ClientCore, data: PathBuf, models: PathBuf) -> Self {
        let mut app = Self::new(Mode::Client, data, models);
        app.tx.send(Event::Client(Ok(Box::new(core)))).unwrap();
        app.events();
        app
    }
    /// Build a server console from an already authenticated local vault.
    pub fn from_server(server: ChatServer, data: PathBuf, models: PathBuf) -> Self {
        let mut app = Self::new(Mode::Server, data, models);
        app.tx.send(Event::Server(Ok(Box::new(server)))).unwrap();
        app.events();
        app
    }
    pub fn new(mode: Mode, data: PathBuf, models: PathBuf) -> Self {
        let (tx, rx) = mpsc::channel();
        Self {
            mode,
            data,
            models,
            password: String::new(),
            confirm: String::new(),
            unlocked: false,
            busy: false,
            error: String::new(),
            status: String::new(),
            client: None,
            server: None,
            running: None,
            tx,
            rx,
            view: ClientState::default(),
            server_view: ServerState::default(),
            page: Page::Chat,
            selected: 0,
            channel: String::new(),
            message: String::new(),
            sending: false,
            address: String::new(),
            join_password: String::new(),
            tor: false,
            socks_port: 9050,
            bind: "0.0.0.0".into(),
            join_setting: String::new(),
            profile: ClientState::default(),
            edit_channels: Vec::new(),
            edit_roles: Vec::new(),
            everyone: Default::default(),
            edit_name: String::new(),
            edit_description: String::new(),
            edit_image: String::new(),
            edit_port: 585,
            target: String::new(),
            ban_ip: false,
            staged: Vec::new(),
            last_poll: Instant::now(),
            textures: BTreeMap::new(),
            plaintext: BTreeMap::new(),
            bridge: None,
            #[cfg(feature = "audio")]
            voice: None,
            voice_channel: String::new(),
        }
    }
    fn work(&mut self, job: impl FnOnce() -> Result<String> + Send + 'static) {
        if self.busy {
            return;
        }
        self.busy = true;
        self.error.clear();
        let tx = self.tx.clone();
        std::thread::spawn(move || {
            let _ = tx.send(Event::Done(job()));
        });
    }
    fn saved(&self) -> Option<SavedServer> {
        self.view.servers.get(self.selected).cloned()
    }
    fn client_action(
        &mut self,
        job: impl FnOnce(&mut ClientCore, &mut SavedServer) -> Result<String> + Send + 'static,
    ) {
        if let (Some(core), Some(mut saved)) = (self.client.clone(), self.saved()) {
            self.work(move || {
                let mut core = core
                    .lock()
                    .map_err(|_| anyhow::anyhow!("Client unavailable"))?;
                let message = job(&mut core, &mut saved)?;
                core.store(&saved)?;
                Ok(message)
            });
        }
    }
    fn events(&mut self) {
        while let Ok(event) = self.rx.try_recv() {
            self.busy = false;
            match event {
                Event::Client(Ok(c)) => {
                    self.profile = c.state.clone();
                    self.view = c.state.clone();
                    self.client = Some(Arc::new(Mutex::new(*c)));
                    self.unlocked = true;
                }
                Event::Server(Ok(s)) => {
                    self.server_view = s.state.clone();
                    self.edit_name = s.state.name.clone();
                    self.edit_description = s.state.description.clone();
                    self.edit_image = s.state.image.clone();
                    self.edit_port = s.state.port;
                    self.edit_channels = s.state.channels.clone();
                    self.edit_roles = s.state.roles.clone();
                    self.everyone = s.state.everyone_permissions.clone();
                    self.server = Some(Arc::new(Mutex::new(*s)));
                    self.unlocked = true;
                }
                Event::Client(Err(e)) | Event::Server(Err(e)) | Event::Done(Err(e)) => {
                    self.sending = false;
                    self.error = format!("{e:#}")
                }
                Event::Done(Ok(status)) => {
                    if self.sending {
                        self.message.clear();
                        self.staged.clear();
                        self.sending = false;
                    }
                    self.status = status
                }
            }
        }
        if let Some(c) = &self.client {
            if let Ok(c) = c.try_lock() {
                self.view = c.state.clone();
            }
        }
        if let Some(s) = &self.server {
            if let Ok(s) = s.try_lock() {
                self.server_view = s.state.clone();
            }
        }
    }
    fn unlock_ui(&mut self, ui: &mut egui::Ui) {
        ui.set_max_width(400.);
        carbon::eyebrow(ui, "YOUR SPACE. YOUR COMMUNITY.");
        ui.add_space(16.);
        ui.label(egui::RichText::new("A private place\nto connect.").size(40.));
        ui.add_space(24.);
        let exists = self.data.join("data.vault").exists();
        carbon::title(
            ui,
            if exists {
                "Welcome back"
            } else {
                "Create your vault"
            },
            if exists {
                "Unlock your encrypted archive to continue."
            } else {
                "Your identity and archives, encrypted on this device."
            },
        );
        ui.label("Vault password");
        ui.add(
            egui::TextEdit::singleline(&mut self.password)
                .margin(egui::Margin::symmetric(16., 10.))
                .password(true)
                .desired_width(f32::INFINITY)
                .hint_text("Vault password"),
        );
        if !exists {
            ui.small("Use at least 12 characters.");
            ui.label("Confirm password");
            ui.add(
                egui::TextEdit::singleline(&mut self.confirm)
                    .margin(egui::Margin::symmetric(16., 10.))
                    .password(true)
                    .desired_width(f32::INFINITY)
                    .hint_text("Confirm password"),
            );
        }
        if ui
            .add_enabled(
                !self.busy,
                carbon::primary(if exists { "Unlock" } else { "Create vault" }),
            )
            .clicked()
        {
            self.error.clear();
            if !exists && self.password != self.confirm {
                self.error = "Passwords do not match".into();
                return;
            }
            let password = Zeroizing::new(std::mem::take(&mut self.password));
            self.confirm.clear();
            self.busy = true;
            let data = self.data.join("data.vault");
            let tx = self.tx.clone();
            let mode = self.mode;
            std::thread::spawn(move || {
                let event = if mode == Mode::Client {
                    Event::Client(
                        Vault::open(data, password)
                            .and_then(ClientCore::open)
                            .map(Box::new),
                    )
                } else {
                    Event::Server(
                        Vault::open(data, password)
                            .and_then(ChatServer::open)
                            .map(Box::new),
                    )
                };
                let _ = tx.send(event);
            });
        }
    }
    fn stop_voice(&mut self) {
        #[cfg(feature = "audio")]
        {
            self.voice = None;
        }
        self.bridge = None;
        self.voice_channel.clear();
    }
    fn lock(&mut self) {
        self.stop_voice();
        *self = Self::new(self.mode, self.data.clone(), self.models.clone());
    }
    fn join_voice(&mut self, c: Channel) {
        self.stop_voice();
        if let (Some(core), Some(saved)) = (self.client.clone(), self.saved()) {
            let session = Arc::new(Mutex::new(VoiceSession::new(
                core,
                saved,
                c.clone(),
                self.models.clone(),
            )));
            self.voice_channel = c.name;
            #[cfg(feature = "audio")]
            {
                self.voice = Some(crate::voice::NativeVoice::start(session.clone()));
            }
            match VoiceBridge::start(session) {
                Ok(bridge) => self.bridge = Some(bridge),
                Err(e) => self.error = e.to_string(),
            }
        }
    }
    fn nav(&mut self, page: Page) {
        self.page = page;
        if page == Page::Profile || page == Page::Audio {
            self.profile = self.view.clone();
        }
        if let Some(saved) = self.saved() {
            if page == Page::Manage {
                self.edit_name = saved.name;
                self.edit_description = saved.description;
                self.edit_channels = saved.admin_channels;
                self.edit_roles = saved.roles.clone();
                self.edit_image = saved.image;
            }
            if page == Page::Roles {
                self.edit_roles = saved.roles;
                self.everyone = saved.everyone_permissions;
            }
            if page == Page::Connection {
                self.tor = saved.tor_only;
                self.socks_port = saved.tor_socks_port;
            }
        }
    }
    fn client_ui(&mut self, ctx: &egui::Context) {
        egui::SidePanel::left("communities")
            .default_width(256.)
            .min_width(240.)
            .max_width(320.)
            .frame(
                egui::Frame::none()
                    .fill(if ctx.style().visuals.dark_mode {
                        egui::Color32::from_rgb(38, 38, 38)
                    } else {
                        egui::Color32::from_rgb(244, 244, 244)
                    })
                    .inner_margin(16.),
            )
            .show(ctx, |ui| {
                egui::ScrollArea::vertical()
                    .id_salt("navigation")
                    .show(ui, |ui| {
                        carbon::eyebrow(ui, "WORKSPACE");
                        ui.label(egui::RichText::new(&self.view.name).size(20.));
                        ui.add_space(8.);
                        carbon::eyebrow(ui, "COMMUNITIES");
                        if self.view.servers.is_empty() {
                            ui.small("Your communities will appear here.");
                        }
                        for (i, s) in self.view.servers.clone().iter().enumerate() {
                            if carbon::nav(
                                ui,
                                self.selected == i,
                                &format!("{}{}", s.name, if s.tor_only { " [Tor]" } else { "" }),
                            )
                            .clicked()
                            {
                                self.stop_voice();
                                self.selected = i;
                                self.channel.clear();
                                self.page = Page::Chat;
                            }
                        }
                        ui.separator();
                        if let Some(s) = self.saved() {
                            carbon::eyebrow(ui, "CHANNELS");
                            for c in s.channels {
                                if carbon::nav(
                                    ui,
                                    self.channel == c.id,
                                    &format!(
                                        "{} {}",
                                        if c.kind == "voice" { "Voice /" } else { "#" },
                                        c.name
                                    ),
                                )
                                .clicked()
                                {
                                    self.channel = c.id.clone();
                                    self.page = Page::Chat;
                                    if c.kind == "voice" {
                                        self.join_voice(c);
                                    }
                                }
                            }
                        }
                        egui::CollapsingHeader::new("Join a server")
                            .default_open(self.view.servers.is_empty())
                            .show(ui, |ui| {
                                ui.label("Server address");
                                ui.add(
                                    egui::TextEdit::singleline(&mut self.address)
                                        .margin(egui::Margin::symmetric(16., 10.))
                                        .hint_text("example.com:585")
                                        .desired_width(f32::INFINITY),
                                );
                                ui.label("Join password");
                                ui.add(
                                    egui::TextEdit::singleline(&mut self.join_password)
                                        .margin(egui::Margin::symmetric(16., 10.))
                                        .password(true)
                                        .hint_text("Join password"),
                                );
                                ui.checkbox(&mut self.tor, "Connect using Tor only");
                                if self.tor {
                                    ui.add(
                                        egui::DragValue::new(&mut self.socks_port)
                                            .range(1..=65535)
                                            .prefix("SOCKS port "),
                                    );
                                }
                                if ui
                                    .add_enabled(!self.busy, carbon::primary("Join"))
                                    .clicked()
                                {
                                    if let Some(core) = self.client.clone() {
                                        let mut s = SavedServer {
                                            address: self.address.clone(),
                                            tor_only: self.tor,
                                            tor_socks_port: self.socks_port,
                                            ..SavedServer::default()
                                        };
                                        let password =
                                            Zeroizing::new(std::mem::take(&mut self.join_password));
                                        self.work(move || {
                                            core.lock()
                                                .map_err(|_| anyhow::anyhow!("Client unavailable"))?
                                                .connect(&mut s, Some(password))?;
                                            Ok("Connected".into())
                                        });
                                    }
                                }
                            });
                        ui.separator();
                        ui.horizontal(|ui| {
                            if ui.button("Reconnect").clicked() {
                                self.client_action(|c, s| {
                                    c.connect(s, None)?;
                                    Ok("Connected".into())
                                });
                            }
                            if ui.button("Disconnect").clicked() {
                                self.stop_voice();
                                self.client_action(|c, s| {
                                    c.disconnect(s);
                                    Ok("Disconnected".into())
                                });
                            }
                        });
                        carbon::eyebrow(ui, "NAVIGATION");
                        for (page, label) in [
                            (Page::Chat, "Chat"),
                            (Page::Profile, "Profile"),
                            (Page::Audio, "Audio"),
                            (Page::Friends, "Friends"),
                            (Page::Manage, "Manage server"),
                            (Page::Roles, "Roles"),
                            (Page::Connection, "Connection"),
                        ] {
                            if carbon::nav(ui, self.page == page, label).clicked() {
                                self.nav(page);
                            }
                        }
                    });
            });
        if !self.voice_channel.is_empty() {
            egui::TopBottomPanel::bottom("voice").show(ctx, |ui| {
                ui.horizontal(|ui| {
                    ui.label(format!("Voice: {}", self.voice_channel));
                    let button = ui.add(
                        egui::Button::new("Hold to talk").sense(egui::Sense::click_and_drag()),
                    );
                    let pressed = talk_pressed(ctx, &button, &self.view.ptt_key);
                    #[cfg(feature = "audio")]
                    if let Some(voice) = &self.voice {
                        voice.press(pressed);
                        if let Ok(status) = voice.status.try_lock() {
                            ui.label(status.as_str());
                        }
                    }
                    #[cfg(not(feature = "audio"))]
                    let _ = pressed;
                    if ui.button("Video / screen share").clicked() {
                        if let Some(b) = &self.bridge {
                            if let Err(e) = webbrowser::open(&b.uri(true)) {
                                self.error = e.to_string();
                            }
                        }
                    }
                    if ui.button("Leave voice").clicked() {
                        self.stop_voice();
                    }
                });
            });
        }
        egui::CentralPanel::default()
            .frame(carbon::content(ctx))
            .show(ctx, |ui| {
                if self.page == Page::Chat {
                    self.chat_ui(ui, ctx);
                    return;
                }
                egui::ScrollArea::vertical()
                    .id_salt("settings")
                    .show(ui, |ui| {
                        ui.set_max_width(840.);
                        match self.page {
                            Page::Chat => self.chat_ui(ui, ctx),
                            Page::Profile => self.profile_ui(ui),
                            Page::Audio => self.audio_ui(ui),
                            Page::Friends => self.friends_ui(ui),
                            Page::Manage => self.manage_ui(ui),
                            Page::Roles => self.roles_ui(ui),
                            Page::Connection => {
                                carbon::title(
                                    ui,
                                    "Connection",
                                    "Choose how you connect to this community.",
                                );
                                ui.checkbox(&mut self.tor, "Connect using Tor only");
                                ui.add(
                                    egui::DragValue::new(&mut self.socks_port)
                                        .range(1..=65535)
                                        .prefix("Tor SOCKS port "),
                                );
                                ui.label("Start a trusted local Tor service before reconnecting.");
                                if ui.button("Save connection settings").clicked() {
                                    self.stop_voice();
                                    let (tor, port) = (self.tor, self.socks_port);
                                    self.client_action(move |c, s| {
                                        c.connection_settings(s, tor, port)?;
                                        Ok("Saved. Reconnect to apply.".into())
                                    });
                                }
                            }
                        }
                    });
            });
        let incoming = self
            .client
            .as_ref()
            .and_then(|core| core.try_lock().ok().map(|c| c.incoming_calls.clone()))
            .unwrap_or_default();
        for (server_id, calls) in incoming {
            for call in calls {
                if let Some(index) = self.view.servers.iter().position(|s| s.id == server_id) {
                    let s = self.view.servers[index].clone();
                    egui::Window::new("Incoming call")
                        .id(egui::Id::new(&call.id))
                        .collapsible(false)
                        .show(ctx, |ui| {
                            ui.label(format!(
                                "{} is calling",
                                s.accounts
                                    .get(&call.from)
                                    .map(|a| a.name.as_str())
                                    .unwrap_or("Member")
                            ));
                            for (action, label) in [("accept", "Answer"), ("decline", "Decline")] {
                                if ui.button(label).clicked() {
                                    self.selected = index;
                                    let id = call.id.clone();
                                    self.client_action(move |c, s| {
                                        c.request(
                                            s,
                                            "call",
                                            &json!({"action":action,"id":id}),
                                            true,
                                        )?;
                                        c.poll(s)?;
                                        Ok("Call updated".into())
                                    });
                                    if action == "accept" {
                                        if let Some(channel) =
                                            s.channels.iter().find(|c| c.id == call.channel)
                                        {
                                            self.join_voice(channel.clone());
                                        }
                                    }
                                }
                            }
                        });
                }
            }
        }
        if !self.busy && self.last_poll.elapsed() > Duration::from_secs(1) {
            self.last_poll = Instant::now();
            if let Some(core) = self.client.clone() {
                let servers = core
                    .try_lock()
                    .ok()
                    .map(|c| {
                        c.state
                            .servers
                            .iter()
                            .filter(|s| c.connected(s))
                            .cloned()
                            .collect::<Vec<_>>()
                    })
                    .unwrap_or_default();
                if !servers.is_empty() {
                    self.work(move || {
                        let mut c = core
                            .lock()
                            .map_err(|_| anyhow::anyhow!("Client unavailable"))?;
                        for mut s in servers {
                            c.poll(&mut s)?;
                        }
                        Ok(String::new())
                    });
                }
            }
        }
    }
    fn chat_ui(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        let Some(s) = self.saved() else {
            carbon::eyebrow(ui, "VCH / COMMUNITIES");
            ui.add_space(32.);
            ui.label(egui::RichText::new("Good conversations\nstart here.").size(40.));
            ui.add_space(16.);
            ui.label("Join a community using its server address and join password.");
            ui.label("Your saved messages stay available when you are offline.");
            ui.add_space(32.);
            egui::Frame::none()
                .fill(carbon::layer(ui))
                .inner_margin(24.)
                .show(ui, |ui| {
                    ui.strong("Your communities");
                    ui.label("Use Join a server in the sidebar to get connected.");
                });
            return;
        };
        carbon::eyebrow(ui, "COMMUNITY");
        ui.horizontal(|ui| {
            self.image_ui(ui, ctx, &s.image, 40.);
            ui.heading(&s.name);
        });
        if !s.description.is_empty() {
            ui.label(egui::RichText::new(&s.description).weak());
        }
        ui.add_space(8.);
        ui.separator();
        let Some(channel) = s.channels.iter().find(|c| c.id == self.channel).cloned() else {
            ui.add_space(32.);
            ui.heading("Make yourself at home.");
            ui.label("Select a text or voice channel from the sidebar.");
            return;
        };
        ui.add_space(8.);
        ui.label(
            egui::RichText::new(format!(
                "{} {}",
                if channel.kind == "voice" {
                    "Voice /"
                } else {
                    "#"
                },
                channel.name
            ))
            .size(20.),
        );
        if channel.kind == "voice" {
            ui.label("Connect with your community. Hold to talk when you are ready.");
            ui.add_space(16.);
            if ui.add(carbon::primary("Join voice")).clicked() {
                self.join_voice(channel);
            }
            return;
        }
        let selected_channel = self.channel.clone();
        let history_height = (ui.available_height() - 164.).max(48.);
        egui::ScrollArea::vertical()
            .id_salt(("messages", &s.id, &selected_channel))
            .max_height(history_height)
            .min_scrolled_height(history_height)
            .auto_shrink([false, false])
            .stick_to_bottom(true)
            .show(ui, |ui| {
                if !s.messages.iter().any(|m| m.channel == selected_channel) {
                    ui.add_space(24.);
                    ui.strong("Start the conversation");
                    ui.label("Say hello or share an image with this channel.");
                }
                for m in s.messages.iter().filter(|m| m.channel == selected_channel) {
                    let key = format!("{}/{}", s.id, m.id);
                    if !self.plaintext.contains_key(&key) {
                        if let Some(core) = &self.client {
                            if let Ok(c) = core.try_lock() {
                                match c.decrypt(&s, m) {
                                    Ok(bytes) => {
                                        if m.kind == "text" {
                                            self.plaintext.insert(
                                                key.clone(),
                                                String::from_utf8_lossy(&bytes).into(),
                                            );
                                        } else {
                                            let images: Vec<Vec<u8>> = if m.kind == "images" {
                                                serde_json::from_slice::<Vec<String>>(&bytes)
                                                    .unwrap_or_default()
                                                    .iter()
                                                    .filter_map(|s| crate::crypto::unb64(s).ok())
                                                    .collect()
                                            } else {
                                                vec![bytes]
                                            };
                                            for (i, bytes) in images.iter().enumerate() {
                                                if let Ok(image) =
                                                    crate::images::decode(bytes, 2048)
                                                {
                                                    let rgba = image.to_rgba8();
                                                    let texture = ctx.load_texture(
                                                        format!("{key}/{i}"),
                                                        egui::ColorImage::from_rgba_unmultiplied(
                                                            [
                                                                rgba.width() as usize,
                                                                rgba.height() as usize,
                                                            ],
                                                            rgba.as_raw(),
                                                        ),
                                                        Default::default(),
                                                    );
                                                    self.textures
                                                        .insert(format!("{key}/{i}"), texture);
                                                }
                                            }
                                            self.plaintext.insert(key.clone(), String::new());
                                        }
                                    }
                                    Err(e) => {
                                        self.plaintext.insert(key.clone(), format!("[{}]", e));
                                    }
                                }
                            }
                        }
                    }
                    ui.horizontal_top(|ui| {
                        self.image_ui(
                            ui,
                            ctx,
                            s.accounts
                                .get(&m.author)
                                .map(|a| a.avatar.as_str())
                                .unwrap_or(""),
                            32.,
                        );
                        ui.vertical(|ui| {
                            ui.strong(
                                s.accounts
                                    .get(&m.author)
                                    .map(|a| a.name.as_str())
                                    .unwrap_or("Member"),
                            );
                            if let Some(text) = self.plaintext.get(&key) {
                                ui.label(text);
                            }
                            for i in 0..3 {
                                if let Some(texture) = self.textures.get(&format!("{key}/{i}")) {
                                    ui.add(
                                        egui::Image::new(texture)
                                            .max_width(ui.available_width().min(480.)),
                                    );
                                }
                            }
                        });
                    });
                    ui.add_space(16.);
                }
            });
        ui.add_space(8.);
        let edit = ui.add_enabled(
            !self.sending,
            egui::TextEdit::multiline(&mut self.message)
                .margin(egui::Margin::symmetric(16., 10.))
                .desired_rows(2)
                .desired_width(f32::INFINITY)
                .hint_text(format!("Message #{}", channel.name)),
        );
        if edit.changed() && !self.busy {
            let id = self.channel.clone();
            self.client_action(move |c, s| {
                c.request(s, "typing", &json!({"channel":id}), true)?;
                Ok(String::new())
            });
        }
        ui.horizontal_wrapped(|ui| {
            if ui
                .add_enabled(
                    !self.sending && self.staged.len() < 3,
                    egui::Button::new("Add image"),
                )
                .clicked()
            {
                if let Some(file) = rfd::FileDialog::new()
                    .add_filter("Still images", &["png", "jpg", "jpeg", "webp"])
                    .pick_file()
                {
                    match crate::images::prepare(&file, 100000, 1600) {
                        Ok(b) => self.staged.push(b),
                        Err(e) => self.error = e.to_string(),
                    }
                }
            }
            if !self.staged.is_empty() {
                ui.label(format!("{} image(s) staged", self.staged.len()));
                if ui.button("Clear images").clicked() {
                    self.staged.clear();
                }
            }
            if ui
                .add_enabled(!self.busy, carbon::primary("Send"))
                .clicked()
            {
                let message = self.message.clone();
                let staged = self.staged.clone();
                self.sending = true;
                self.client_action(move |core, s| {
                    if !message.is_empty() {
                        core.send(s, &channel, "text", message.as_bytes())?;
                    }
                    if !staged.is_empty() {
                        core.send(s, &channel, "images", &crate::images::collection(&staged)?)?;
                    }
                    Ok("Sent".into())
                });
            }
        });
    }
    fn profile_ui(&mut self, ui: &mut egui::Ui) {
        carbon::title(
            ui,
            "Profile",
            "How you appear to the people in your communities.",
        );
        self.image_ui(ui, &ui.ctx().clone(), &self.profile.avatar.clone(), 96.);
        ui.label("Display name");
        carbon::input(ui, &mut self.profile.name);
        ui.label("Pronouns");
        carbon::input(ui, &mut self.profile.pronouns);
        ui.label("Bio");
        carbon::textarea(ui, &mut self.profile.bio);
        if ui.button("Choose profile picture").clicked() {
            if let Some(file) = rfd::FileDialog::new()
                .add_filter("Images", &["png", "jpg", "jpeg", "webp"])
                .pick_file()
            {
                match crate::images::prepare(&file, 10000, 256) {
                    Ok(b) => self.profile.avatar = crate::crypto::b64(&b),
                    Err(e) => self.error = e.to_string(),
                }
            }
        }
        if ui.add(carbon::primary("Save profile")).clicked() {
            let profile = self.profile.clone();
            if let Some(core) = self.client.clone() {
                self.work(move || {
                    let mut c = core
                        .lock()
                        .map_err(|_| anyhow::anyhow!("Client unavailable"))?;
                    c.state.name = profile.name;
                    c.state.bio = profile.bio;
                    c.state.avatar = profile.avatar;
                    c.state.pronouns = profile.pronouns;
                    c.save()?;
                    for s in c.state.servers.clone() {
                        if c.connected(&s) {
                            c.update_profile(&s)?;
                        }
                    }
                    Ok("Profile saved".into())
                });
            }
        }
    }
    fn audio_ui(&mut self, ui: &mut egui::Ui) {
        carbon::title(
            ui,
            "Audio",
            "Set up your devices, volume, and push-to-talk shortcut.",
        );
        #[cfg(feature = "audio")]
        if let Ok((inputs, outputs)) = crate::audio::devices() {
            for (label, value, devices) in [
                ("Microphone", &mut self.profile.microphone, inputs),
                ("Speakers", &mut self.profile.speakers, outputs),
            ] {
                egui::ComboBox::from_label(label)
                    .selected_text(value.as_str())
                    .show_ui(ui, |ui| {
                        ui.selectable_value(value, "Default".into(), "Default");
                        for device in devices {
                            ui.selectable_value(value, device.clone(), device);
                        }
                    });
            }
        }
        ui.add(egui::Slider::new(&mut self.profile.input_gain, 0..=300).text("Input gain %"));
        ui.add(egui::Slider::new(&mut self.profile.output_gain, 0..=300).text("Output gain %"));
        ui.add(
            egui::Slider::new(&mut self.profile.outgoing_bit_rate, 500..=6000).text("DAC bits/s"),
        );
        egui::ComboBox::from_label("Push-to-talk key")
            .selected_text(&self.profile.ptt_key)
            .show_ui(ui, |ui| {
                for key in ["SPACE", "V", "B"] {
                    ui.selectable_value(&mut self.profile.ptt_key, key.into(), key);
                }
            });
        if ui.add(carbon::primary("Save audio preferences")).clicked() {
            self.stop_voice();
            let p = self.profile.clone();
            if let Some(core) = self.client.clone() {
                self.work(move || {
                    let mut c = core
                        .lock()
                        .map_err(|_| anyhow::anyhow!("Client unavailable"))?;
                    c.state.microphone = p.microphone;
                    c.state.speakers = p.speakers;
                    c.state.input_gain = p.input_gain;
                    c.state.output_gain = p.output_gain;
                    c.state.outgoing_bit_rate = p.outgoing_bit_rate;
                    c.state.ptt_key = p.ptt_key;
                    c.save()?;
                    Ok("Audio saved. Join voice to apply.".into())
                });
            }
        }
    }
    fn friends_ui(&mut self, ui: &mut egui::Ui) {
        carbon::title(
            ui,
            "Friends",
            "Stay in touch with members across your community.",
        );
        let Some(s) = self.saved() else {
            return;
        };
        ui.label("Account hash");
        carbon::input(ui, &mut self.target);
        ui.collapsing("Registered accounts", |ui| {
            for a in s.accounts.values() {
                ui.collapsing(a.name.clone(), |ui| {
                    self.image_ui(ui, &ui.ctx().clone(), &a.avatar, 64.);
                    ui.label(&a.pronouns);
                    ui.label(&a.bio);
                    ui.monospace(&a.hash);
                    if ui.button("Use account hash").clicked() {
                        self.target = a.hash.clone();
                    }
                });
            }
        });
        if ui.button("Send friend request").clicked() {
            let target = self.target.clone();
            self.friend_action("request", target);
        }
        for target in &s.friend_requests {
            ui.horizontal(|ui| {
                ui.label(
                    s.accounts
                        .get(target)
                        .map(|a| a.name.as_str())
                        .unwrap_or(target),
                );
                for action in ["accept", "decline"] {
                    if ui.button(action).clicked() {
                        self.friend_action(action, target.clone());
                    }
                }
            });
        }
        let own = crate::crypto::account(&s.id, &self.view.public_key);
        for f in s.friendships.clone() {
            let target = if f.first == own {
                f.second.clone()
            } else {
                f.first.clone()
            };
            ui.horizontal(|ui| {
                ui.label(
                    s.accounts
                        .get(&target)
                        .map(|a| a.name.as_str())
                        .unwrap_or(&target),
                );
                if ui.button("Message").clicked() {
                    self.channel = f.text_channel.clone();
                    self.page = Page::Chat;
                }
                if ui.button("Call").clicked() {
                    let target = target.clone();
                    self.client_action(move |c, s| {
                        c.request(s, "call", &json!({"action":"ring","account":target}), true)?;
                        Ok("Ringing".into())
                    });
                    if let Some(c) = s.channels.iter().find(|c| c.id == f.voice_channel) {
                        self.join_voice(c.clone());
                    }
                }
                if ui.button("Remove").clicked() {
                    self.friend_action("remove", target);
                }
            });
        }
        let calls = self
            .client
            .as_ref()
            .and_then(|c| {
                c.try_lock()
                    .ok()
                    .map(|c| c.incoming_calls.get(&s.id).cloned().unwrap_or_default())
            })
            .unwrap_or_default();
        for call in calls {
            ui.horizontal(|ui| {
                ui.label(format!(
                    "Incoming call from {}",
                    s.accounts
                        .get(&call.from)
                        .map(|a| a.name.as_str())
                        .unwrap_or("Member")
                ));
                for action in ["accept", "decline"] {
                    if ui.button(action).clicked() {
                        let id = call.id.clone();
                        self.client_action(move |c, s| {
                            c.request(s, "call", &json!({"action":action,"id":id}), true)?;
                            Ok("Call updated".into())
                        });
                        if action == "accept" {
                            if let Some(channel) = s.channels.iter().find(|c| c.id == call.channel)
                            {
                                self.join_voice(channel.clone());
                            }
                        }
                    }
                }
            });
        }
    }
    fn friend_action(&mut self, action: &'static str, target: String) {
        self.client_action(move |c, s| {
            c.request(
                s,
                "friend",
                &json!({"action":action,"account":target}),
                true,
            )?;
            c.poll(s)?;
            Ok("Friends updated".into())
        });
    }
    fn manage_ui(&mut self, ui: &mut egui::Ui) {
        carbon::title(
            ui,
            "Manage server",
            "Shape your community and organize its channels.",
        );
        if !self.saved().is_some_and(|s| s.admin) {
            ui.label("Administrator permission required");
            return;
        }
        carbon::input(ui, &mut self.edit_name);
        carbon::textarea(ui, &mut self.edit_description);
        self.server_image_editor(ui);
        channel_editor(ui, &mut self.edit_channels, &self.edit_roles);
        if ui.button("Save server settings").clicked() {
            let (name, description, channels, image) = (
                self.edit_name.clone(),
                self.edit_description.clone(),
                self.edit_channels.clone(),
                self.edit_image.clone(),
            );
            self.client_action(move|c,s|{c.request(s,"admin",&json!({"name":name,"description":description,"image":image,"channels":channels}),true)?;c.poll(s)?;Ok("Server settings saved".into())});
        }
    }
    fn image_ui(&mut self, ui: &mut egui::Ui, ctx: &egui::Context, encoded: &str, size: f32) {
        if encoded.is_empty() {
            return;
        }
        let key = format!(
            "picture/{}",
            crate::crypto::hex(&crate::crypto::hash(encoded.as_bytes()))
        );
        if !self.textures.contains_key(&key) {
            let image = crate::crypto::unb64(encoded).and_then(|b| crate::images::decode(&b, 2048));
            if let Ok(image) = image {
                let rgba = image.to_rgba8();
                let texture = ctx.load_texture(
                    &key,
                    egui::ColorImage::from_rgba_unmultiplied(
                        [rgba.width() as usize, rgba.height() as usize],
                        rgba.as_raw(),
                    ),
                    Default::default(),
                );
                self.textures.insert(key.clone(), texture);
            }
        }
        if let Some(texture) = self.textures.get(&key) {
            ui.add(egui::Image::new(texture).max_size(egui::vec2(size, size)));
        }
    }
    fn server_image_editor(&mut self, ui: &mut egui::Ui) {
        self.image_ui(ui, &ui.ctx().clone(), &self.edit_image.clone(), 64.);
        if ui.button("Choose server picture").clicked() {
            if let Some(file) = rfd::FileDialog::new()
                .add_filter("Images", &["png", "jpg", "jpeg", "webp"])
                .pick_file()
            {
                match crate::images::prepare(&file, 10000, 256) {
                    Ok(b) => self.edit_image = crate::crypto::b64(&b),
                    Err(e) => self.error = e.to_string(),
                }
            }
        }
    }
    fn roles_ui(&mut self, ui: &mut egui::Ui) {
        carbon::title(
            ui,
            "Roles — lowest to highest",
            "Control what members can see and do in your community.",
        );
        if !self.saved().is_some_and(|s| s.role_manager) {
            ui.label("Manage roles permission required");
            return;
        }
        role_editor(ui, &mut self.edit_roles, &mut self.everyone);
        if ui.button("Save roles").clicked() {
            let (roles, everyone) = (self.edit_roles.clone(), self.everyone.clone());
            self.client_action(move |c, s| {
                c.request(
                    s,
                    "roles",
                    &json!({"roles":roles,"everyonePermissions":everyone}),
                    true,
                )?;
                c.poll(s)?;
                Ok("Roles saved".into())
            });
        }
    }
    fn server_ui(&mut self, ctx: &egui::Context) {
        egui::CentralPanel::default()
            .frame(carbon::content(ctx))
            .show(ctx, |ui| {
                carbon::eyebrow(ui, "ADMINISTRATION");
                carbon::title(
                    ui,
                    "Server console",
                    "Manage your community, access, and encrypted channels.",
                );
                egui::Frame::none()
                    .fill(carbon::layer(ui))
                    .inner_margin(16.)
                    .show(ui, |ui| {
                        ui.horizontal_wrapped(|ui| {
                            ui.colored_label(
                                if self.running.is_some() {
                                    egui::Color32::from_rgb(66, 190, 101)
                                } else {
                                    ui.visuals().weak_text_color()
                                },
                                if self.running.is_some() {
                                    "Running"
                                } else {
                                    "Stopped"
                                },
                            );
                            if self.running.is_none() {
                                ui.label("Listen address");
                                ui.add(
                                    egui::TextEdit::singleline(&mut self.bind)
                                        .margin(egui::Margin::symmetric(16., 10.))
                                        .desired_width(200.),
                                );
                                if ui.add(carbon::primary("Start server")).clicked() {
                                    if let Some(server) = self.server.clone() {
                                        match RunningServer::start(
                                            server,
                                            &self.bind,
                                            self.server_view.port,
                                        ) {
                                            Ok(r) => self.running = Some(r),
                                            Err(e) => self.error = e.to_string(),
                                        }
                                    }
                                }
                            } else if ui.button("Stop server").clicked() {
                                self.running = None;
                            }
                        });
                    });
                ui.add_space(16.);
                egui::ScrollArea::vertical().show(ui, |ui| {
                    ui.collapsing("Overview", |ui| {
                        ui.label("Server name");
                        carbon::input(ui, &mut self.edit_name);
                        ui.label("Description");
                        carbon::textarea(ui, &mut self.edit_description);
                        self.server_image_editor(ui);
                        ui.add(
                            egui::DragValue::new(&mut self.edit_port)
                                .range(1..=65535)
                                .prefix("Port "),
                        );
                        if ui.button("Save overview").clicked() {
                            let (name, description, port, image) = (
                                self.edit_name.clone(),
                                self.edit_description.clone(),
                                self.edit_port,
                                self.edit_image.clone(),
                            );
                            if let Some(core) = self.server.clone() {
                                self.work(move || {
                                    anyhow::ensure!(
                                        !name.trim().is_empty()
                                            && name.chars().count() <= 60
                                            && !name.chars().any(char::is_control)
                                            && description.chars().count() <= 500,
                                        "Invalid server details"
                                    );
                                    let mut s = core
                                        .lock()
                                        .map_err(|_| anyhow::anyhow!("Server unavailable"))?;
                                    s.state.name = name;
                                    s.state.description = description;
                                    s.state.port = port;
                                    s.state.image = image;
                                    s.save()?;
                                    Ok("Overview saved".into())
                                });
                            }
                        }
                        ui.label("Join password");
                        ui.add(
                            egui::TextEdit::singleline(&mut self.join_setting)
                                .margin(egui::Margin::symmetric(16., 10.))
                                .password(true),
                        );
                        if ui.button("Set join password").clicked() {
                            let password = Zeroizing::new(std::mem::take(&mut self.join_setting));
                            if let Some(core) = self.server.clone() {
                                self.work(move || {
                                    core.lock()
                                        .map_err(|_| anyhow::anyhow!("Server unavailable"))?
                                        .set_password(password)?;
                                    Ok("Join password updated".into())
                                });
                            }
                        }
                    });
                    ui.collapsing("Channels", |ui| {
                        channel_editor(ui, &mut self.edit_channels, &self.edit_roles);
                        if ui.button("Save channels").clicked() {
                            let channels = self.edit_channels.clone();
                            if let Some(core) = self.server.clone() {
                                self.work(move || {
                                    let mut s = core
                                        .lock()
                                        .map_err(|_| anyhow::anyhow!("Server unavailable"))?;
                                    s.state.channels = channels;
                                    s.save()?;
                                    Ok("Channels saved".into())
                                });
                            }
                        }
                        for c in self.server_view.channels.clone() {
                            if ui.button(format!("Rotate key: {}", c.name)).clicked() {
                                if let Some(core) = self.server.clone() {
                                    self.work(move || {
                                        core.lock()
                                            .map_err(|_| anyhow::anyhow!("Server unavailable"))?
                                            .rotate_channel_key(&c.id)?;
                                        Ok("Channel key rotated".into())
                                    });
                                }
                            }
                        }
                    });
                    ui.collapsing("Roles", |ui| {
                        role_editor(ui, &mut self.edit_roles, &mut self.everyone);
                        if ui.button("Save roles").clicked() {
                            let (roles, everyone) =
                                (self.edit_roles.clone(), self.everyone.clone());
                            if let Some(core) = self.server.clone() {
                                self.work(move || {
                                    let mut s = core
                                        .lock()
                                        .map_err(|_| anyhow::anyhow!("Server unavailable"))?;
                                    s.state.roles = roles;
                                    s.state.everyone_permissions = everyone;
                                    s.save()?;
                                    Ok("Roles saved".into())
                                });
                            }
                        }
                    });
                    ui.collapsing("Accounts & bans", |ui| {
                        for a in self
                            .server_view
                            .accounts
                            .values()
                            .cloned()
                            .collect::<Vec<_>>()
                        {
                            ui.horizontal(|ui| {
                                ui.label(&a.name);
                                ui.monospace(&a.hash);
                                let mut admin = self.server_view.admins.contains(&a.hash);
                                if ui.checkbox(&mut admin, "Administrator").changed() {
                                    if let Some(core) = self.server.clone() {
                                        self.work(move || {
                                            let mut s = core.lock().map_err(|_| {
                                                anyhow::anyhow!("Server unavailable")
                                            })?;
                                            if admin {
                                                s.state.admins.insert(a.hash);
                                            } else {
                                                s.state.admins.remove(&a.hash);
                                            }
                                            s.save()?;
                                            Ok("Administrator updated".into())
                                        });
                                    }
                                }
                            });
                        }
                        carbon::input(ui, &mut self.target);
                        ui.checkbox(&mut self.ban_ip, "Exact IP address");
                        if ui.button("Ban").clicked() {
                            let (value, ip) = (self.target.clone(), self.ban_ip);
                            if let Some(core) = self.server.clone() {
                                self.work(move || {
                                    core.lock()
                                        .map_err(|_| anyhow::anyhow!("Server unavailable"))?
                                        .ban(&value, ip)?;
                                    Ok("Ban saved".into())
                                });
                            }
                        }
                        for (ip, values) in [
                            (false, self.server_view.banned_hashes.clone()),
                            (true, self.server_view.banned_ips.clone()),
                        ] {
                            for value in values {
                                ui.horizontal(|ui| {
                                    ui.monospace(&value);
                                    if ui.button("Unban").clicked() {
                                        if let Some(core) = self.server.clone() {
                                            self.work(move || {
                                                let mut s = core.lock().map_err(|_| {
                                                    anyhow::anyhow!("Server unavailable")
                                                })?;
                                                if ip {
                                                    s.state.banned_ips.remove(&value);
                                                } else {
                                                    s.state.banned_hashes.remove(&value);
                                                }
                                                s.save()?;
                                                Ok("Ban removed".into())
                                            });
                                        }
                                    }
                                });
                            }
                        }
                    });
                });
            });
    }
}
impl App {
    fn ui(&mut self, ctx: &egui::Context) {
        self.events();
        ctx.request_repaint_after(Duration::from_millis(100));
        let dark = !self.unlocked || self.mode == Mode::Server || self.view.dark_mode;
        carbon::apply(ctx, dark);
        egui::TopBottomPanel::top("shell")
            .exact_height(48.)
            .frame(
                egui::Frame::none()
                    .fill(carbon::SHELL)
                    .inner_margin(egui::Margin::symmetric(16., 4.)),
            )
            .show(ctx, |ui| {
                ui.visuals_mut().override_text_color = Some(carbon::WHITE);
                ui.horizontal(|ui| {
                    ui.label(egui::RichText::new("VCH").size(18.).strong());
                    ui.separator();
                    ui.label(if self.mode == Mode::Client {
                        "Private communities"
                    } else {
                        "Server administration"
                    });
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if self.unlocked && ui.button("Lock").clicked() {
                            self.lock();
                        }
                        if self.unlocked
                            && self.mode == Mode::Client
                            && ui
                                .button(if dark { "Light mode" } else { "Dark mode" })
                                .clicked()
                        {
                            if let Some(core) = self.client.clone() {
                                self.work(move || {
                                    let mut c = core
                                        .lock()
                                        .map_err(|_| anyhow::anyhow!("Client unavailable"))?;
                                    c.state.dark_mode = !c.state.dark_mode;
                                    c.save()?;
                                    Ok(String::new())
                                });
                            }
                        }
                    });
                });
            });
        if self.busy || !self.error.is_empty() || !self.status.is_empty() {
            egui::TopBottomPanel::bottom("status")
                .frame(
                    egui::Frame::none()
                        .fill(if dark {
                            egui::Color32::from_rgb(38, 38, 38)
                        } else {
                            carbon::WHITE
                        })
                        .inner_margin(egui::Margin::symmetric(24., 8.)),
                )
                .show(ctx, |ui| {
                    ui.horizontal_wrapped(|ui| {
                        if self.busy {
                            ui.spinner();
                        }
                        if !self.error.is_empty() {
                            ui.label(
                                egui::RichText::new(&self.error).color(ui.visuals().error_fg_color),
                            );
                        } else if !self.status.is_empty() {
                            ui.label(&self.status);
                        } else if self.busy {
                            ui.label("Working…");
                        }
                    });
                });
        }
        if !self.unlocked {
            egui::CentralPanel::default()
                .frame(carbon::content(ctx))
                .show(ctx, |ui| {
                    egui::ScrollArea::vertical().show(ui, |ui| {
                        ui.add_space((ui.available_height() * 0.04).min(32.));
                        ui.horizontal(|ui| {
                            ui.add_space(((ui.available_width() - 400.) / 2.).max(0.));
                            ui.vertical(|ui| self.unlock_ui(ui));
                        });
                    });
                });
        } else if self.mode == Mode::Client {
            self.client_ui(ctx);
        } else {
            self.server_ui(ctx);
        }
    }
}
impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _: &mut eframe::Frame) {
        self.ui(ctx);
    }
}
fn talk_pressed(ctx: &egui::Context, button: &egui::Response, key: &str) -> bool {
    let ptt = ctx.input(|i| match key {
        "V" => i.key_down(egui::Key::V),
        "B" => i.key_down(egui::Key::B),
        _ => i.key_down(egui::Key::Space),
    });
    ctx.input(|i| i.focused)
        && (button.is_pointer_button_down_on() || ptt && !ctx.wants_keyboard_input())
}
fn channel_editor(ui: &mut egui::Ui, channels: &mut Vec<Channel>, roles: &[Role]) {
    let mut remove = None;
    let mut swap = None;
    let count = channels.len();
    for (i, c) in channels.iter_mut().enumerate() {
        ui.push_id(c.id.clone(), |ui| {
            ui.collapsing(c.name.clone(), |ui| {
                carbon::input(ui, &mut c.name);
                egui::ComboBox::from_label("Type")
                    .selected_text(&c.kind)
                    .show_ui(ui, |ui| {
                        ui.selectable_value(&mut c.kind, "text".into(), "Text");
                        ui.selectable_value(&mut c.kind, "voice".into(), "Voice");
                    });
                ui.add(egui::Slider::new(&mut c.bit_rate, 500..=6000).text("Voice bits/s"));
                ui.add(egui::Slider::new(&mut c.max_members, 2..=32).text("Maximum members"));
                ui.add(
                    egui::Slider::new(&mut c.slow_mode_seconds, 0..=3600).text("Slow mode seconds"),
                );
                let mut allow = c.allow.iter().cloned().collect::<Vec<_>>().join("\n");
                ui.label("Allowed account hashes (one per line; empty permits everyone)");
                if carbon::textarea(ui, &mut allow).changed() {
                    c.allow = allow
                        .lines()
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                        .map(str::to_owned)
                        .collect();
                }
                for (id, name) in std::iter::once(("everyone", "Everyone"))
                    .chain(roles.iter().map(|r| (r.id.as_str(), r.name.as_str())))
                {
                    ui.collapsing(format!("Permissions: {name}"), |ui| {
                        for p in ["view", "send", "connect"] {
                            let old = c.role_overrides.get(id).and_then(|o| o.get(p)).copied();
                            let mut v = old;
                            egui::ComboBox::from_id_salt((id, p))
                                .selected_text(match v {
                                    None => "Inherit",
                                    Some(true) => "Allow",
                                    Some(false) => "Deny",
                                })
                                .show_ui(ui, |ui| {
                                    ui.selectable_value(&mut v, None, "Inherit");
                                    ui.selectable_value(&mut v, Some(true), "Allow");
                                    ui.selectable_value(&mut v, Some(false), "Deny");
                                });
                            ui.label(p);
                            if old != v {
                                if let Some(v) = v {
                                    c.role_overrides
                                        .entry(id.into())
                                        .or_default()
                                        .insert(p.into(), v);
                                } else if let Some(o) = c.role_overrides.get_mut(id) {
                                    o.remove(p);
                                }
                            }
                        }
                    });
                }
                if i > 0 && ui.button("Move earlier").clicked() {
                    swap = Some((i, i - 1));
                }
                if i + 1 < count && ui.button("Move later").clicked() {
                    swap = Some((i, i + 1));
                }
                if ui.button("Remove channel").clicked() {
                    remove = Some(i);
                }
            });
        });
    }
    if let Some(i) = remove {
        channels.remove(i);
    } else if let Some((a, b)) = swap {
        channels.swap(a, b);
    }
    if ui.button("Add text channel").clicked() {
        channels.push(Channel::default());
    }
    if ui.button("Add voice channel").clicked() {
        channels.push(Channel {
            name: "Voice".into(),
            kind: "voice".into(),
            ..Channel::default()
        });
    }
}
fn role_editor(
    ui: &mut egui::Ui,
    roles: &mut Vec<Role>,
    everyone: &mut std::collections::BTreeSet<String>,
) {
    ui.collapsing("Everyone", |ui| {
        for p in ["view", "send", "connect"] {
            let mut enabled = everyone.contains(p);
            if ui.checkbox(&mut enabled, p).changed() {
                if enabled {
                    everyone.insert(p.into());
                } else {
                    everyone.remove(p);
                }
            }
        }
    });
    let (mut remove, mut swap) = (None, None);
    for (i, r) in roles.iter_mut().enumerate() {
        ui.push_id(r.id.clone(), |ui| {
            ui.collapsing(r.name.clone(), |ui| {
                carbon::input(ui, &mut r.name);
                for p in ["view", "send", "connect", "manage_roles"] {
                    let mut enabled = r.permissions.contains(p);
                    if ui.checkbox(&mut enabled, p).changed() {
                        if enabled {
                            r.permissions.insert(p.into());
                        } else {
                            r.permissions.remove(p);
                        }
                    }
                }
                let mut members = r.members.iter().cloned().collect::<Vec<_>>().join("\n");
                ui.label("Member account hashes (one per line)");
                if carbon::textarea(ui, &mut members).changed() {
                    r.members = members
                        .lines()
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                        .map(str::to_owned)
                        .collect();
                }
                if i > 0 && ui.button("Move lower").clicked() {
                    swap = Some((i, i - 1));
                }
                if ui.button("Remove role").clicked() {
                    remove = Some(i);
                }
            });
        });
    }
    if let Some(i) = remove {
        roles.remove(i);
    } else if let Some((a, b)) = swap {
        roles.swap(a, b);
    }
    if ui.button("Add role").clicked() {
        roles.push(Role::default());
    }
}
pub fn run(mode: Mode, data: PathBuf, models: PathBuf) -> Result<()> {
    run_app(App::new(mode, data, models))
}
/// Run the production desktop UI with an explicitly constructed application.
pub fn run_app(app: App) -> Result<()> {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1200., 840.])
            .with_min_inner_size([760., 600.]),
        ..Default::default()
    };
    eframe::run_native(
        if app.mode == Mode::Client {
            "VCH / Client"
        } else {
            "VCH / Server"
        },
        options,
        Box::new(move |cc| {
            let mut fonts = egui::FontDefinitions::default();
            fonts.font_data.insert(
                "IBM Plex Sans".into(),
                egui::FontData::from_static(include_bytes!(
                    "../main/resources/fonts/IBMPlexSans-Regular.ttf"
                )),
            );
            fonts
                .families
                .entry(egui::FontFamily::Proportional)
                .or_default()
                .insert(0, "IBM Plex Sans".into());
            fonts.font_data.insert(
                "IBM Plex Mono".into(),
                egui::FontData::from_static(include_bytes!(
                    "../main/resources/fonts/IBMPlexMono-Regular.ttf"
                )),
            );
            fonts
                .families
                .entry(egui::FontFamily::Monospace)
                .or_default()
                .insert(0, "IBM Plex Mono".into());
            cc.egui_ctx.set_fonts(fonts);
            Ok(Box::new(app))
        }),
    )
    .map_err(|e| anyhow::anyhow!(e.to_string()))
}
pub fn launch(mode: Mode) -> Result<()> {
    use clap::Parser;
    #[derive(Parser)]
    #[command(version)]
    struct Options {
        #[arg(long)]
        data: Option<PathBuf>,
        #[arg(long)]
        models: Option<PathBuf>,
    }
    let options = Options::parse();
    run(
        mode,
        options.data.unwrap_or_else(|| {
            crate::data_dir(if mode == Mode::Client {
                "client"
            } else {
                "server"
            })
        }),
        options.models.unwrap_or_else(crate::models_dir),
    )
}
#[cfg(test)]
mod verification;
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn initial_state_and_navigation() {
        let mut a = App::new(Mode::Client, PathBuf::from("test"), PathBuf::from("models"));
        assert!(!a.unlocked);
        assert!(a.saved().is_none());
        a.view.servers.push(SavedServer {
            id: "s".into(),
            ..SavedServer::default()
        });
        assert_eq!(a.saved().unwrap().id, "s");
        for p in [
            Page::Chat,
            Page::Profile,
            Page::Audio,
            Page::Friends,
            Page::Manage,
            Page::Roles,
            Page::Connection,
        ] {
            a.nav(p);
            assert_eq!(a.page, p);
        }
        a.stop_voice();
        let ctx = egui::Context::default();
        let _ = ctx.run(Default::default(), |ctx| {
            egui::CentralPanel::default().show(ctx, |ui| {
                a.unlock_ui(ui);
                let mut channels = vec![Channel::default()];
                let mut roles = vec![Role::default()];
                channel_editor(ui, &mut channels, &roles);
                role_editor(ui, &mut roles, &mut a.everyone);
            });
        });
    }
    #[test]
    fn background_work_events() {
        let mut a = App::new(Mode::Client, PathBuf::new(), PathBuf::new());
        a.work(|| Ok("done".into()));
        for _ in 0..100 {
            a.events();
            if !a.busy {
                break;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(a.status, "done");
        a.tx.send(Event::Done(Err(anyhow::anyhow!("failure"))))
            .unwrap();
        a.events();
        assert_eq!(a.error, "failure");
    }
    #[test]
    fn all_client_and_server_views_render_real_archives() {
        let dir = tempfile::tempdir_in("target").unwrap();
        let password = || Zeroizing::new("desktop fixture password".into());
        let mut client =
            ClientCore::open(Vault::open(dir.path().join("client.vault"), password()).unwrap())
                .unwrap();
        let text = Channel::default();
        let voice = Channel {
            kind: "voice".into(),
            name: "Voice".into(),
            ..Channel::default()
        };
        let mut saved = SavedServer {
            id: id(),
            name: "Render community".into(),
            address: "localhost".into(),
            admin: true,
            role_manager: true,
            channels: vec![text.clone(), voice.clone()],
            admin_channels: vec![text.clone(), voice],
            roles: vec![Role::default()],
            everyone_permissions: ["view".into(), "send".into(), "connect".into()].into(),
            ..SavedServer::default()
        };
        saved.channel_keys.insert(
            text.id.clone(),
            crate::crypto::b64(&crate::crypto::random(32)),
        );
        saved.key_revisions.insert(text.id.clone(), 1);
        let account = client.account(&saved);
        saved.accounts.insert(
            account.clone(),
            Account {
                hash: account,
                public_key: client.state.public_key.clone(),
                name: "Render member".into(),
                ..Account::default()
            },
        );
        saved.messages.push(
            client
                .envelope(&saved, &text.id, "text", b"A verified archived message")
                .unwrap(),
        );
        let mut png = std::io::Cursor::new(Vec::new());
        image::DynamicImage::new_rgb8(128, 64)
            .write_to(&mut png, image::ImageFormat::Png)
            .unwrap();
        let webp = crate::images::prepare_bytes(png.get_ref(), 100000, 128).unwrap();
        saved.messages.push(
            client
                .envelope(
                    &saved,
                    &text.id,
                    "images",
                    &crate::images::collection(&[webp]).unwrap(),
                )
                .unwrap(),
        );
        client.store(&saved).unwrap();
        let mut app = App::new(Mode::Client, dir.path().into(), "build/models".into());
        app.tx.send(Event::Client(Ok(Box::new(client)))).unwrap();
        app.events();
        app.channel = text.id.clone();
        let ctx = egui::Context::default();
        ctx.memory_mut(|m| m.set_everything_is_visible(true));
        for page in [
            Page::Chat,
            Page::Profile,
            Page::Audio,
            Page::Friends,
            Page::Manage,
            Page::Roles,
            Page::Connection,
        ] {
            app.nav(page);
            let output = ctx.run(
                egui::RawInput {
                    screen_rect: Some(egui::Rect::from_min_size(
                        egui::Pos2::ZERO,
                        egui::vec2(1200., 1600.),
                    )),
                    ..Default::default()
                },
                |ctx| app.client_ui(ctx),
            );
            assert!(!output.shapes.is_empty());
        }
        assert!(app
            .plaintext
            .values()
            .any(|t| t == "A verified archived message"));
        assert_eq!(app.textures.len(), 1);
        app.client_action(|c, _| {
            c.state.bio = "Saved through background UI action".into();
            Ok("Saved".into())
        });
        for _ in 0..200 {
            app.events();
            if !app.busy {
                break;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(app.status, "Saved");
        let server =
            ChatServer::open(Vault::open(dir.path().join("server.vault"), password()).unwrap())
                .unwrap();
        app.mode = Mode::Server;
        app.tx.send(Event::Server(Ok(Box::new(server)))).unwrap();
        app.events();
        let output = ctx.run(
            egui::RawInput {
                screen_rect: Some(egui::Rect::from_min_size(
                    egui::Pos2::ZERO,
                    egui::vec2(1200., 2000.),
                )),
                ..Default::default()
            },
            |ctx| app.server_ui(ctx),
        );
        assert!(!output.shapes.is_empty());
    }
}
