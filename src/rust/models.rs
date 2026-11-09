use crate::crypto;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap as Map, BTreeSet as Set};

pub fn id() -> String {
    uuid::Uuid::new_v4().to_string()
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default, rename_all = "camelCase")]
pub struct Channel {
    pub id: String,
    pub name: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub bit_rate: u32,
    pub max_members: usize,
    pub slow_mode_seconds: u64,
    pub allow: Set<String>,
    pub role_overrides: Map<String, Map<String, bool>>,
}
impl Default for Channel {
    fn default() -> Self {
        Self {
            id: id(),
            name: "general".into(),
            kind: "text".into(),
            bit_rate: 6000,
            max_members: 16,
            slow_mode_seconds: 0,
            allow: Set::new(),
            role_overrides: Map::new(),
        }
    }
}
impl Channel {
    pub fn permits(&self, account: &str) -> bool {
        self.allow.is_empty() || self.allow.contains(account)
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default, rename_all = "camelCase")]
pub struct Account {
    pub hash: String,
    pub public_key: String,
    pub name: String,
    pub bio: String,
    pub avatar: String,
    pub pronouns: String,
}
impl Default for Account {
    fn default() -> Self {
        Self {
            hash: String::new(),
            public_key: String::new(),
            name: "Member".into(),
            bio: String::new(),
            avatar: String::new(),
            pronouns: String::new(),
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default)]
pub struct Role {
    pub id: String,
    pub name: String,
    pub permissions: Set<String>,
    pub members: Set<String>,
}
impl Default for Role {
    fn default() -> Self {
        Self {
            id: id(),
            name: "New role".into(),
            permissions: Set::new(),
            members: Set::new(),
        }
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default, rename_all = "camelCase")]
pub struct Envelope {
    pub id: String,
    pub server: String,
    pub channel: String,
    pub author: String,
    pub kind: String,
    pub cipher: String,
    pub signature: String,
    pub time: u64,
    pub sequence: u64,
    pub key_revision: u64,
}
impl Envelope {
    pub fn signed_bytes(&self) -> Vec<u8> {
        format!(
            "VCH/message/{}|{}|{}|{}|{}|{}|{}|{}{}",
            if self.key_revision > 0 { 2 } else { 1 },
            self.server,
            self.channel,
            self.author,
            self.id,
            self.time,
            self.kind,
            self.cipher,
            self.revision_suffix()
        )
        .into_bytes()
    }
    pub fn aad(&self) -> Vec<u8> {
        format!(
            "VCH/payload/{}|{}|{}|{}|{}|{}{}",
            if self.key_revision > 0 { 2 } else { 1 },
            self.server,
            self.channel,
            self.author,
            self.id,
            self.kind,
            self.revision_suffix()
        )
        .into_bytes()
    }
    fn revision_suffix(&self) -> String {
        if self.key_revision > 0 {
            format!("|{}", self.key_revision)
        } else {
            String::new()
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default, rename_all = "camelCase")]
pub struct ChannelKeySet {
    pub current: String,
    pub access_policy: Option<String>,
    pub revision: u64,
    pub previous: Vec<String>,
}
impl Default for ChannelKeySet {
    fn default() -> Self {
        Self {
            current: crypto::b64(&crypto::random(32)),
            access_policy: None,
            revision: 1,
            previous: Vec::new(),
        }
    }
}
impl ChannelKeySet {
    pub fn rotate(&mut self) {
        self.previous.push(std::mem::replace(
            &mut self.current,
            crypto::b64(&crypto::random(32)),
        ));
        self.revision += 1;
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct CallInvite {
    pub id: String,
    pub from: String,
    pub to: String,
    pub channel: String,
    pub expires: u64,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default, rename_all = "camelCase")]
pub struct Friendship {
    pub first: String,
    pub second: String,
    pub text_channel: String,
    pub voice_channel: String,
}
impl Friendship {
    pub fn includes(&self, account: &str) -> bool {
        self.first == account || self.second == account
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct ServerState {
    pub id: String,
    pub name: String,
    pub description: String,
    pub image: String,
    pub password_salt: Option<String>,
    pub password_verifier: Option<String>,
    pub tls_store: String,
    pub admins: Set<String>,
    pub roles: Vec<Role>,
    pub everyone_permissions: Set<String>,
    pub friend_requests: Map<String, Set<String>>,
    pub friendships: Vec<Friendship>,
    pub port: u16,
    pub hash_bits: u8,
    pub join_protocol: u32,
    pub channels: Vec<Channel>,
    pub channel_keys: Map<String, ChannelKeySet>,
    pub accounts: Map<String, Account>,
    pub banned_ips: Set<String>,
    pub banned_hashes: Set<String>,
    pub messages: Vec<Envelope>,
    pub next_sequence: u64,
}
impl Default for ServerState {
    fn default() -> Self {
        Self {
            id: id(),
            name: "My community".into(),
            description: String::new(),
            image: String::new(),
            password_salt: None,
            password_verifier: None,
            tls_store: String::new(),
            admins: Set::new(),
            roles: Vec::new(),
            everyone_permissions: ["view", "send", "connect"].map(str::to_owned).into(),
            friend_requests: Map::new(),
            friendships: Vec::new(),
            port: 585,
            hash_bits: 16,
            join_protocol: 2,
            channels: Vec::new(),
            channel_keys: Map::new(),
            accounts: Map::new(),
            banned_ips: Set::new(),
            banned_hashes: Set::new(),
            messages: Vec::new(),
            next_sequence: 1,
        }
    }
}
impl ServerState {
    pub fn permits(&self, c: &Channel, account: &str, permission: &str) -> bool {
        if !c.permits(account) {
            return false;
        }
        if self
            .friendships
            .iter()
            .any(|f| f.includes(account) && (c.id == f.text_channel || c.id == f.voice_channel))
            || self.admins.contains(account)
        {
            return true;
        }
        let mut allowed = self.everyone_permissions.contains(permission)
            || self
                .roles
                .iter()
                .any(|r| r.members.contains(account) && r.permissions.contains(permission));
        if let Some(v) = c
            .role_overrides
            .get("everyone")
            .and_then(|o| o.get(permission))
        {
            allowed = *v;
        }
        let values: Vec<bool> = self
            .roles
            .iter()
            .filter(|r| r.members.contains(account))
            .filter_map(|r| {
                c.role_overrides
                    .get(&r.id)
                    .and_then(|o| o.get(permission))
                    .copied()
            })
            .collect();
        if values.contains(&false) {
            allowed = false;
        }
        if values.contains(&true) {
            allowed = true;
        }
        allowed
    }
    pub fn highest_role(&self, account: &str) -> Option<usize> {
        self.roles.iter().rposition(|r| r.members.contains(account))
    }
    pub fn global_permissions(&self, account: &str) -> Set<String> {
        let mut p = self.everyone_permissions.clone();
        for r in &self.roles {
            if r.members.contains(account) {
                p.extend(r.permissions.clone());
            }
        }
        p
    }
    pub fn can_manage_roles(&self, account: &str) -> bool {
        self.admins.contains(account) || self.global_permissions(account).contains("manage_roles")
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default, rename_all = "camelCase")]
pub struct SavedServer {
    pub address: String,
    pub fingerprint: Option<String>,
    pub name: String,
    pub id: String,
    pub password_key: Option<String>,
    pub description: String,
    pub image: String,
    pub admin: bool,
    pub role_manager: bool,
    pub tor_only: bool,
    pub tor_socks_port: u16,
    pub friendships: Vec<Friendship>,
    pub friend_requests: Set<String>,
    pub join_protocol: u32,
    pub channel_keys: Map<String, String>,
    pub key_revisions: Map<String, u64>,
    pub previous_channel_keys: Map<String, Vec<String>>,
    pub channels: Vec<Channel>,
    pub admin_channels: Vec<Channel>,
    pub roles: Vec<Role>,
    pub everyone_permissions: Set<String>,
    pub accounts: Map<String, Account>,
    pub messages: Vec<Envelope>,
    pub last_sequence: u64,
}
impl Default for SavedServer {
    fn default() -> Self {
        Self {
            address: String::new(),
            fingerprint: None,
            name: String::new(),
            id: String::new(),
            password_key: None,
            description: String::new(),
            image: String::new(),
            admin: false,
            role_manager: false,
            tor_only: false,
            tor_socks_port: 9050,
            friendships: Vec::new(),
            friend_requests: Set::new(),
            join_protocol: 2,
            channel_keys: Map::new(),
            key_revisions: Map::new(),
            previous_channel_keys: Map::new(),
            channels: Vec::new(),
            admin_channels: Vec::new(),
            roles: Vec::new(),
            everyone_permissions: Set::new(),
            accounts: Map::new(),
            messages: Vec::new(),
            last_sequence: 0,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct ClientState {
    pub name: String,
    pub bio: String,
    pub avatar: String,
    pub pronouns: String,
    pub public_key: String,
    pub private_key: String,
    pub microphone: String,
    pub speakers: String,
    pub ptt_key: String,
    pub input_gain: u32,
    pub output_gain: u32,
    pub outgoing_bit_rate: u32,
    pub dark_mode: bool,
    pub servers: Vec<SavedServer>,
}
impl Default for ClientState {
    fn default() -> Self {
        Self {
            name: "New member".into(),
            bio: String::new(),
            avatar: String::new(),
            pronouns: String::new(),
            public_key: String::new(),
            private_key: String::new(),
            microphone: "Default".into(),
            speakers: "Default".into(),
            ptt_key: "SPACE".into(),
            input_gain: 100,
            output_gain: 100,
            outgoing_bit_rate: 6000,
            dark_mode: true,
            servers: Vec::new(),
        }
    }
}
